package ai.godance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

import ai.godance.config.PromoProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService.TopUpResult;

/**
 * The one-time signup credit: a {@link CreditSource#PROMO} ledger entry
 * for an account whose email has just been verified, so a new account can
 * try a model before it tops up. Off unless
 * {@code godance.promo.signup-credit-cents} is above zero.
 *
 * <p>The pattern for a promotional credit, which later promotions follow:
 * <ul>
 *   <li><b>Settings</b> in {@link PromoProperties}, amount defaulting to 0.</li>
 *   <li><b>Ledger entry</b> through {@link CreditLedgerService#topUp} with
 *       source {@code PROMO} and one idempotency key per account and
 *       promotion ({@link #KEY_PREFIX} + account id), so the unique key
 *       makes a second grant impossible, whichever path asks and however
 *       often.</li>
 *   <li><b>Audit events</b> {@code promo.<promotion>.granted} (amount),
 *       {@code .skipped} (reason) and {@code .failed}.</li>
 *   <li><b>Never in the caller's way.</b> The grant runs after the
 *       caller's transaction commits, in a transaction of its own, against
 *       the committed account. A failure is logged and audited, and
 *       never undoes or fails the sign-in or verification that asked for it.</li>
 * </ul>
 *
 * <p>Accounts verified before the feature was turned on get nothing: the
 * grant is asked for only where an email becomes verified (and where
 * parental consent completes a verified account), never on an ordinary
 * sign-in, and there is no backfill.
 */
@Service
@EnableConfigurationProperties(PromoProperties.class)
public class SignupCreditService {

    private static final Logger log = LoggerFactory.getLogger(SignupCreditService.class);

    /** Ledger idempotency key of the signup credit: this prefix, then the account id. */
    public static final String KEY_PREFIX = "promo-signup-";
    /** {@code credit_ledger.source_ref} of a signup credit: which promotion it came from. */
    public static final String SOURCE_REF = "signup-credit";
    static final String NOTE = "Signup credit";
    /** Advisory lock taken while a grant is counted against the daily limit and written. */
    static final long DAILY_LIMIT_LOCK_ID = 7_000_003L;

    /** What one grant attempt did. */
    public enum Outcome { NOT_ELIGIBLE, ALREADY_GRANTED, DAILY_LIMIT, GRANTED, FAILED }

    private final PromoProperties promo;
    private final UserRepository users;
    private final CreditLedgerRepository ledgerEntries;
    private final CreditLedgerService ledger;
    private final AuditLogService audit;
    private final TransactionTemplate ownTransaction;

    public SignupCreditService(PromoProperties promo, UserRepository users,
                               CreditLedgerRepository ledgerEntries, CreditLedgerService ledger,
                               AuditLogService audit, PlatformTransactionManager txManager) {
        this.promo = promo;
        this.users = users;
        this.ledgerEntries = ledgerEntries;
        this.ledger = ledger;
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(txManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * Grant the signup credit to {@code user} if it qualifies. Call it once
     * the account is saved, wherever its email becomes verified. Inside a
     * transaction the grant waits for that transaction to commit (and is
     * dropped if it rolls back); outside one it runs at once. It never
     * throws: a failed grant is logged and audited.
     *
     * <p>An account qualifies when the amount is above zero, its email is
     * verified and is a real address (not the placeholder a social sign-in
     * without an email gets), it is active, its age status is
     * {@link AgeStatus#ADULT} or {@link AgeStatus#CONSENT_GRANTED}, it has
     * never had the credit, and fewer than the daily limit have been
     * granted today (UTC).
     */
    public void grantIfEligible(User user) {
        if (!promo.signupCreditEnabled() || user == null || user.getId() == null) {
            return;
        }
        UUID userId = user.getId();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    grantNow(userId);
                }
            });
        } else {
            grantNow(userId);
        }
    }

    /**
     * One grant attempt, in its own transaction, against the committed
     * account. Package-private for tests; callers use {@link #grantIfEligible}.
     */
    Outcome grantNow(UUID userId) {
        try {
            return ownTransaction.execute(status -> grantInOwnTransaction(userId));
        } catch (RuntimeException e) {
            log.error("Signup credit for user {} failed; the account is unaffected", userId, e);
            try {
                audit.recordInNewTransaction(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_FAILED,
                        AuditOutcome.FAILURE, userId, null, Map.of(
                                "amountCents", promo.signupCreditCents(),
                                "error", e.getClass().getSimpleName()));
            } catch (RuntimeException auditFailure) {
                log.error("Could not audit the failed signup credit for user {}", userId, auditFailure);
            }
            return Outcome.FAILED;
        }
    }

    private Outcome grantInOwnTransaction(UUID userId) {
        User u = users.findById(userId).orElse(null);
        if (u == null || !isEligible(u)) {
            return Outcome.NOT_ELIGIBLE;
        }
        String key = KEY_PREFIX + userId;
        if (ledgerEntries.findByIdempotencyKey(key).isPresent()) {
            return Outcome.ALREADY_GRANTED;
        }
        // One grant at a time from here to commit, so two sign-ups at once
        // cannot both take the last place under the daily limit.
        ledgerEntries.lockForTransaction(DAILY_LIMIT_LOCK_ID);
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        long grantedToday = ledgerEntries.countBySourceAndIdempotencyKeyStartingWithAndOccurredAtGreaterThanEqual(
                CreditSource.PROMO, KEY_PREFIX, dayStart);
        if (grantedToday >= promo.signupCreditDailyLimit()) {
            log.info("Signup credit for user {} skipped: daily limit of {} reached",
                    userId, promo.signupCreditDailyLimit());
            audit.record(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_SKIPPED, AuditOutcome.DENIED,
                    userId, null, Map.of(
                            "reason", "daily-limit",
                            "dailyLimit", promo.signupCreditDailyLimit()));
            return Outcome.DAILY_LIMIT;
        }
        long amount = promo.signupCreditCents();
        TopUpResult result = ledger.topUp(userId, amount, CreditSource.PROMO, SOURCE_REF, key, NOTE);
        if (result == TopUpResult.DUPLICATE) {
            return Outcome.ALREADY_GRANTED;
        }
        audit.record(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_GRANTED, AuditOutcome.SUCCESS,
                userId, null, Map.of("amountCents", amount));
        return Outcome.GRANTED;
    }

    /** Everything about the account itself that the grant needs; the amount and limit are checked apart. */
    static boolean isEligible(User u) {
        return u.isEmailVerified()
                && !u.hasSyntheticEmail()
                && u.isActive()
                && (u.getAgeStatus() == AgeStatus.ADULT || u.getAgeStatus() == AgeStatus.CONSENT_GRANTED);
    }
}
