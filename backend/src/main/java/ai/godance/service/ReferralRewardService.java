package ai.godance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
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

import ai.godance.config.ReferralProperties;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.domain.Referral;
import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.ReferralRepository;
import ai.godance.repository.UserRepository;

/**
 * The referral reward: when an invited user first tops up, one
 * {@link CreditSource#REFERRAL} credit to each side, and the referral is
 * marked {@link Referral.Status#REWARDED}, all in one transaction.
 *
 * <p>It follows the signup credit's pattern ({@link SignupCreditService}):
 * <ul>
 *   <li><b>Never in the payment's way.</b> {@link #afterTopUp} is called by
 *       the two top-up paths in {@link BillingService} once a top-up is
 *       newly applied, and only waits for that transaction to commit. The
 *       reward then runs in a transaction of its own; a failure is logged
 *       and audited ({@code referral.failed}) and never touches the top-up.</li>
 *   <li><b>Once.</b> The ledger keys are built from the referral's id
 *       ({@link #KEY_PREFIX}{@code <id>-referrer} / {@code -referee}), and the
 *       referral row is locked while it is settled, so a redelivered webhook,
 *       the sweep and the top-up path together still pay once.</li>
 *   <li><b>Not lost.</b> {@link #sweep} pays any pending referral whose invited
 *       user already has a qualifying top-up: a reward that failed, or one
 *       whose top-up committed in a process that stopped before it ran.</li>
 * </ul>
 *
 * <p>A top-up qualifies when it is a payment from one of
 * {@link ReferralProperties#qualifyingSources()}, of at least
 * {@link ReferralProperties#minTopupCents()}, and, for Stripe, a Checkout
 * Session ({@code cs_…}): an automatic recharge credits a PaymentIntent
 * ({@code pi_…}) and never counts. A parental consent payment never counts
 * either: the top-up path asks only after it has returned for one, and its
 * account is never an adult's, which every reward requires. The reward is
 * paid on the invited user's earliest qualifying top-up; a smaller one
 * leaves the referral pending for a later one.
 *
 * <p>Past {@link ReferralProperties#monthlyRewardLimit()} rewards for one
 * referrer in a UTC month, the invited user is still credited and the
 * referrer gets nothing for that referral.
 */
@Service
@EnableConfigurationProperties(ReferralProperties.class)
public class ReferralRewardService {

    private static final Logger log = LoggerFactory.getLogger(ReferralRewardService.class);

    /** Ledger idempotency keys of a reward: this prefix, the referral id, then the side. */
    public static final String KEY_PREFIX = "referral-";
    static final String REFERRER_KEY_SUFFIX = "-referrer";
    static final String REFEREE_KEY_SUFFIX = "-referee";
    static final String REFERRER_NOTE = "Referral reward: someone you invited made their first top-up";
    static final String REFEREE_NOTE = "Referral reward: your first top-up";
    /** Key space of the per-referrer advisory lock (two-key form). */
    static final int REFERRER_LOCK_SPACE = 7_000_026;
    /** The most pending referrals one sweep pays; the next run takes the rest. */
    static final int SWEEP_BATCH = 500;

    /** What one reward attempt did. */
    public enum Outcome { NO_PENDING_REFERRAL, NOT_DUE, REWARDED, VOIDED, FAILED }

    private final ReferralProperties props;
    private final ReferralRepository referrals;
    private final CreditLedgerRepository ledgerEntries;
    private final UserRepository users;
    private final CreditLedgerService ledger;
    private final AuditLogService audit;
    private final TransactionTemplate ownTransaction;

    public ReferralRewardService(ReferralProperties props, ReferralRepository referrals,
                                 CreditLedgerRepository ledgerEntries, UserRepository users,
                                 CreditLedgerService ledger, AuditLogService audit,
                                 PlatformTransactionManager txManager) {
        this.props = props;
        this.referrals = referrals;
        this.ledgerEntries = ledgerEntries;
        this.users = users;
        this.ledger = ledger;
        this.audit = audit;
        this.ownTransaction = new TransactionTemplate(txManager);
        this.ownTransaction.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
    }

    /**
     * A payment top-up was newly applied to {@code userId}'s ledger under
     * {@code topUpKey}. If the user has a pending referral, check it once
     * the caller's transaction commits (dropped if that rolls back; at once
     * outside a transaction). Does nothing while the feature is off. Never
     * throws.
     *
     * <p>Call it only for a top-up the user paid for: not for a parental
     * consent payment, and not for an automatic recharge.
     */
    public void afterTopUp(UUID userId, String topUpKey) {
        if (!props.enabled() || userId == null || topUpKey == null) {
            return;
        }
        try {
            if (TransactionSynchronizationManager.isSynchronizationActive()) {
                TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        rewardIfDue(userId, topUpKey);
                    }
                });
            } else {
                rewardIfDue(userId, topUpKey);
            }
        } catch (RuntimeException e) {
            // The top-up is unaffected; the sweep pays the reward instead.
            log.error("Could not check the referral of user {} after top-up {}", userId, topUpKey, e);
        }
    }

    /**
     * Pay every pending referral whose invited user already has a
     * qualifying top-up, oldest claim first, each in its own transaction.
     * Returns how many it rewarded. Does nothing while the feature is off.
     */
    public int sweep() {
        if (!props.enabled()) {
            return 0;
        }
        List<UUID> due = referrals.findInvitedUsersDue(sourceNames(), props.minTopupCents(), SWEEP_BATCH);
        int rewarded = 0;
        for (UUID invitedUserId : due) {
            if (rewardIfDue(invitedUserId, null) == Outcome.REWARDED) {
                rewarded++;
            }
        }
        return rewarded;
    }

    /**
     * One attempt to settle the referral of {@code invitedUserId}, in its own
     * transaction. {@code topUpKey} is the top-up that asked, or null from
     * the sweep; when it doesn't qualify, the skip is audited with the
     * reason. Package-private for tests. Never throws.
     */
    Outcome rewardIfDue(UUID invitedUserId, String topUpKey) {
        try {
            return ownTransaction.execute(status -> rewardInOwnTransaction(invitedUserId, topUpKey));
        } catch (RuntimeException e) {
            log.error("Referral reward for user {} failed; the top-up stands and the sweep will retry",
                    invitedUserId, e);
            try {
                Map<String, Object> detail = new HashMap<>();
                detail.put("error", e.getClass().getSimpleName());
                if (topUpKey != null) {
                    detail.put("topUpKey", topUpKey);
                }
                audit.recordInNewTransaction(AuditLogService.EVT_REFERRAL_FAILED, AuditOutcome.FAILURE,
                        invitedUserId, null, detail);
            } catch (RuntimeException auditFailure) {
                log.error("Could not audit the failed referral reward for user {}", invitedUserId, auditFailure);
            }
            return Outcome.FAILED;
        }
    }

    private Outcome rewardInOwnTransaction(UUID invitedUserId, String topUpKey) {
        Referral referral = referrals.findByInvitedUserIdForUpdate(invitedUserId).orElse(null);
        if (referral == null || referral.getStatus() != Referral.Status.PENDING) {
            return Outcome.NO_PENDING_REFERRAL;
        }
        Optional<CreditLedger> topUp = referrals
                .findFirstQualifyingTopUp(invitedUserId, sourceNames(), props.minTopupCents())
                .flatMap(ledgerEntries::findById);
        if (topUp.isEmpty()) {
            if (topUpKey != null) {
                auditTopUpSkipped(referral, topUpKey);
            }
            return Outcome.NOT_DUE;
        }
        User invited = users.findById(invitedUserId).orElse(null);
        if (invited == null || !invited.isActive() || invited.getAgeStatus() != AgeStatus.ADULT) {
            // Can't be rewarded now or later.
            referral.markVoid();
            referrals.save(referral);
            audit.record(AuditLogService.EVT_REFERRAL_SKIPPED, AuditOutcome.DENIED, invitedUserId, null,
                    Map.of("referralId", referral.getId().toString(), "reason", "invited-account-ineligible"));
            return Outcome.VOIDED;
        }
        return pay(referral, topUp.get());
    }

    /** Both credits, the referral settled and the audit rows, in the caller's transaction. */
    private Outcome pay(Referral referral, CreditLedger topUp) {
        UUID referrerId = referral.getReferrerId();
        UUID invitedUserId = referral.getInvitedUserId();
        // One reward per referrer at a time from here to commit, so two
        // invited users topping up at once can't both take the last place
        // under the monthly limit.
        referrals.lockForReferrer(REFERRER_LOCK_SPACE, referrerId.hashCode());

        long refereeCents = props.refereeRewardCents();
        long referrerCents = props.referrerRewardCents();
        String referrerSkipped = null;
        if (referrerCents > 0) {
            User referrer = users.findById(referrerId).orElse(null);
            if (referrer == null || !referrer.isActive()) {
                referrerSkipped = "referrer-inactive";
            } else if (referrals.countPaidReferrerRewardsSince(referrerId, Referral.Status.REWARDED,
                    startOfMonth()) >= props.monthlyRewardLimit()) {
                referrerSkipped = "monthly-limit";
            }
            if (referrerSkipped != null) {
                referrerCents = 0;
            }
        }

        String referralId = referral.getId().toString();
        if (refereeCents > 0) {
            ledger.topUp(invitedUserId, refereeCents, CreditSource.REFERRAL, referralId,
                    KEY_PREFIX + referralId + REFEREE_KEY_SUFFIX, REFEREE_NOTE);
        }
        if (referrerCents > 0) {
            ledger.topUp(referrerId, referrerCents, CreditSource.REFERRAL, referralId,
                    KEY_PREFIX + referralId + REFERRER_KEY_SUFFIX, REFERRER_NOTE);
        }
        referral.markRewarded(Instant.now(), topUp.getId(), referrerCents, refereeCents);
        referrals.save(referral);

        audit.record(AuditLogService.EVT_REFERRAL_REWARDED, AuditOutcome.SUCCESS, invitedUserId, null, Map.of(
                "referralId", referralId,
                "referrerId", referrerId.toString(),
                "topUpId", topUp.getId().toString(),
                "topUpCents", topUp.getDeltaCents(),
                "referrerRewardCents", referrerCents,
                "refereeRewardCents", refereeCents));
        if (referrerSkipped != null) {
            // On the referrer's own trail, which never names who they invited.
            audit.record(AuditLogService.EVT_REFERRAL_SKIPPED, AuditOutcome.DENIED, referrerId, null, Map.of(
                    "referralId", referralId,
                    "reason", referrerSkipped,
                    "monthlyRewardLimit", props.monthlyRewardLimit()));
        }
        log.info("Referral {} rewarded on top-up {}: referrer {} cents, invited user {} cents",
                referralId, topUp.getId(), referrerCents, refereeCents);
        return Outcome.REWARDED;
    }

    /** Why the top-up under {@code topUpKey} earned nothing, on the invited user's trail. */
    private void auditTopUpSkipped(Referral referral, String topUpKey) {
        CreditLedger entry = ledgerEntries.findByIdempotencyKey(topUpKey).orElse(null);
        if (entry == null) {
            return;
        }
        String reason;
        if (!props.qualifyingSources().contains(entry.getSource())) {
            reason = "source-not-qualifying";
        } else if (entry.getDeltaCents() < props.minTopupCents()) {
            reason = "below-minimum";
        } else {
            reason = "not-a-checkout-top-up";
        }
        audit.record(AuditLogService.EVT_REFERRAL_SKIPPED, AuditOutcome.DENIED, referral.getInvitedUserId(), null,
                Map.of("referralId", referral.getId().toString(),
                        "reason", reason,
                        "source", entry.getSource().name(),
                        "topUpCents", entry.getDeltaCents(),
                        "minTopupCents", props.minTopupCents()));
    }

    private List<String> sourceNames() {
        return props.qualifyingSources().stream().map(Enum::name).sorted().toList();
    }

    private static Instant startOfMonth() {
        return LocalDate.now(ZoneOffset.UTC).withDayOfMonth(1).atStartOfDay(ZoneOffset.UTC).toInstant();
    }
}
