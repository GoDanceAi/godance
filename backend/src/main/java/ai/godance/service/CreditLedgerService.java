package ai.godance.service;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;

/**
 * Owns all balance mutations. Two guarantees:
 *
 * <ol>
 *   <li><b>Idempotent top-ups.</b> Replays of the same Stripe webhook
 *       (same event id) credit the user exactly once. The unique
 *       constraint on {@code credit_ledger.idempotency_key} is the
 *       hard guard; a soft pre-check keeps the happy path lock-free.</li>
 *   <li><b>Atomic balance updates.</b> The {@code User.credit_balance_cents}
 *       cache is mutated via a single UPDATE — no read-modify-write —
 *       so concurrent calls can't lose writes. Debits use a conditional
 *       UPDATE that returns 0 on insufficient funds.</li>
 * </ol>
 *
 * The {@code credit_ledger} table is the source of truth; the User
 * column is a denormalized cache. Reconcile via
 * {@link CreditLedgerRepository#sumDeltaForUser(UUID)}.
 */
@Service
public class CreditLedgerService {

    private static final Logger log = LoggerFactory.getLogger(CreditLedgerService.class);

    private final CreditLedgerRepository ledger;
    private final UserRepository users;
    /**
     * Optional auto-recharge debit-trigger. Injected lazily because (a)
     * unit tests that don't load the full ApplicationContext shouldn't
     * have to stand up Quartz; (b) future profile flags may disable it.
     */
    private final java.util.Optional<AutoRechargeTrigger> autoRecharge;

    public CreditLedgerService(CreditLedgerRepository ledger, UserRepository users,
                               java.util.Optional<AutoRechargeTrigger> autoRecharge) {
        this.ledger = ledger;
        this.users = users;
        this.autoRecharge = autoRecharge;
    }

    public enum TopUpResult { APPLIED, DUPLICATE }

    /**
     * Credit the user with {@code deltaCents}. Returns {@link TopUpResult#DUPLICATE}
     * iff the {@code idempotencyKey} has already been applied — caller should
     * treat that as success (Stripe will keep retrying on non-2xx).
     */
    @Transactional
    public TopUpResult topUp(UUID userId, long deltaCents, CreditSource source,
                             String sourceRef, String idempotencyKey, String note) {
        if (deltaCents <= 0) {
            throw new IllegalArgumentException("topUp delta must be positive; got " + deltaCents);
        }
        if (ledger.findByIdempotencyKey(idempotencyKey).isPresent()) {
            return TopUpResult.DUPLICATE;
        }
        CreditLedger entry = new CreditLedger();
        entry.setUserId(userId);
        entry.setDeltaCents(deltaCents);
        entry.setSource(source);
        entry.setSourceRef(sourceRef);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setNote(note);
        try {
            ledger.save(entry);
        } catch (DataIntegrityViolationException dup) {
            // Lost the race against a concurrent webhook delivery — the other
            // delivery did the credit. Honour idempotency.
            log.info("topUp idempotency race for key={}, treating as DUPLICATE", idempotencyKey);
            return TopUpResult.DUPLICATE;
        }
        users.incrementBalance(userId, deltaCents);
        return TopUpResult.APPLIED;
    }

    /**
     * Debit {@code costCents} from the user iff they can afford it.
     * Returns true on success, false on insufficient funds (no ledger
     * row is written in that case).
     */
    @Transactional
    public boolean debit(UUID userId, long costCents, String requestId) {
        if (costCents <= 0) {
            throw new IllegalArgumentException("debit cost must be positive; got " + costCents);
        }
        int updated = users.decrementBalanceIfSufficient(userId, costCents);
        if (updated == 0) {
            return false;
        }
        CreditLedger entry = new CreditLedger();
        entry.setUserId(userId);
        entry.setDeltaCents(-costCents);
        entry.setSource(CreditSource.DEBIT);
        entry.setSourceRef(requestId);
        entry.setIdempotencyKey("debit-" + requestId);
        ledger.save(entry);
        scheduleAutoRechargeAfterCommit(userId);
        return true;
    }

    /**
     * True if the user already holds a ledger entry from {@code source} for
     * the upstream object {@code sourceRef} (e.g. a Stripe Checkout Session
     * id), whatever idempotency key it was written under.
     */
    @Transactional(readOnly = true)
    public boolean hasEntryFrom(UUID userId, CreditSource source, String sourceRef) {
        return ledger.existsByUserIdAndSourceAndSourceRef(userId, source, sourceRef);
    }

    /** Authoritative balance via SUM (bypasses the denormalized cache). */
    @Transactional(readOnly = true)
    public long authoritativeBalance(UUID userId) {
        return ledger.sumDeltaForUser(userId);
    }

    /**
     * Manual operator credit/debit from the admin console. Signed delta —
     * positive credits, negative debits. A negative adjustment may push the
     * balance below zero (support clawback of a mistaken credit); it does
     * NOT trigger auto-recharge — surprising a user with a card charge from
     * a support action would be worse than a temporarily negative balance.
     *
     * <p>Same idempotency contract as {@link #topUp}: a replay of the same
     * {@code idempotencyKey} returns the original entry without applying
     * the delta twice. The caller (admin console) writes the audit event.
     */
    @Transactional
    public CreditLedger adminAdjust(UUID userId, long deltaCents, String idempotencyKey,
                                    String note, String adjustedBy) {
        if (deltaCents == 0) {
            throw new IllegalArgumentException("adminAdjust delta must be non-zero");
        }
        var existing = ledger.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            return existing.get();
        }
        CreditLedger entry = new CreditLedger();
        entry.setUserId(userId);
        entry.setDeltaCents(deltaCents);
        entry.setSource(CreditSource.ADMIN_ADJUSTMENT);
        entry.setSourceRef(adjustedBy);
        entry.setIdempotencyKey(idempotencyKey);
        entry.setNote(note);
        try {
            ledger.save(entry);
        } catch (DataIntegrityViolationException dup) {
            log.info("adminAdjust idempotency race for key={}, returning original", idempotencyKey);
            return ledger.findByIdempotencyKey(idempotencyKey).orElseThrow();
        }
        users.incrementBalance(userId, deltaCents);
        return entry;
    }

    // ===== Gateway pre-debit / reconcile flow ========================

    /**
     * Atomically pre-debit {@code estimateCents}. Returns false if the
     * user can't afford it — caller surfaces that as 402 Payment Required.
     * Writes a ledger entry keyed {@code estimate-<requestId>}.
     */
    @Transactional
    public boolean preDebit(UUID userId, long estimateCents, String requestId) {
        if (estimateCents <= 0) {
            throw new IllegalArgumentException("estimate must be positive; got " + estimateCents);
        }
        int updated = users.decrementBalanceIfSufficient(userId, estimateCents);
        if (updated == 0) return false;
        CreditLedger entry = new CreditLedger();
        entry.setUserId(userId);
        entry.setDeltaCents(-estimateCents);
        entry.setSource(CreditSource.DEBIT);
        entry.setSourceRef(requestId);
        entry.setIdempotencyKey("estimate-" + requestId);
        entry.setNote("estimated cost (pre-debit)");
        ledger.save(entry);
        scheduleAutoRechargeAfterCommit(userId);
        return true;
    }

    /**
     * After upstream call, settle the difference between estimate and actual.
     * <ul>
     *   <li>actual &lt; estimate → refund the delta as a positive ledger entry
     *       (source ADJUSTMENT, key {@code refund-<requestId>}).</li>
     *   <li>actual &gt; estimate → debit the additional delta. Allowed to take
     *       the balance negative — the call already happened; the next request's
     *       {@link #preDebit} will 402 until the user tops up.</li>
     *   <li>actual = estimate → no-op.</li>
     * </ul>
     */
    @Transactional
    public void reconcile(UUID userId, long estimateCents, long actualCents, String requestId) {
        if (actualCents < estimateCents) {
            long refund = estimateCents - actualCents;
            topUp(userId, refund, CreditSource.ADJUSTMENT, requestId,
                    "refund-" + requestId,
                    "reconcile overestimate (est=" + estimateCents + ", actual=" + actualCents + ")");
        } else if (actualCents > estimateCents) {
            long extra = actualCents - estimateCents;
            users.incrementBalance(userId, -extra);   // may push balance < 0
            CreditLedger entry = new CreditLedger();
            entry.setUserId(userId);
            entry.setDeltaCents(-extra);
            entry.setSource(CreditSource.DEBIT);
            entry.setSourceRef(requestId);
            entry.setIdempotencyKey("topup-" + requestId);
            entry.setNote("reconcile underestimate (est=" + estimateCents
                    + ", actual=" + actualCents + ")");
            ledger.save(entry);
            scheduleAutoRechargeAfterCommit(userId);
        }
    }

    /**
     * Enqueue the debit-driven auto-recharge check, but only after the
     * surrounding transaction commits — otherwise the Quartz worker would
     * read the pre-debit balance and skip charging.
     */
    private void scheduleAutoRechargeAfterCommit(UUID userId) {
        if (autoRecharge.isEmpty()) return;
        AutoRechargeTrigger trigger = autoRecharge.get();
        if (org.springframework.transaction.support.TransactionSynchronizationManager
                .isSynchronizationActive()) {
            org.springframework.transaction.support.TransactionSynchronizationManager
                    .registerSynchronization(
                            new org.springframework.transaction.support.TransactionSynchronization() {
                                @Override public void afterCommit() {
                                    trigger.maybeEnqueue(userId);
                                }
                            });
        } else {
            // Outside any tx (shouldn't happen on the normal paths, but be safe).
            trigger.maybeEnqueue(userId);
        }
    }

    /** Refund the entire pre-debit (used when the upstream call fails). */
    @Transactional
    public void refundEstimate(UUID userId, long estimateCents, String requestId, String reason) {
        if (estimateCents <= 0) return;
        topUp(userId, estimateCents, CreditSource.ADJUSTMENT, requestId,
                "refund-" + requestId, "full refund: " + reason);
    }
}
