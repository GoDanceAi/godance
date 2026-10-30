package ai.godance.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.ConversationRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.OAuthIdentityRepository;
import ai.godance.repository.UserRepository;

/**
 * Closes an account on its owner's request. Operator-run, from
 * {@code POST /v1/admin/users/{id}/close}.
 *
 * <p>The {@code users} row is anonymised, never deleted: the credit ledger,
 * usage records and auto-recharge attempts reference it, and V2 declares
 * those foreign keys {@code ON DELETE CASCADE}, so deleting the row would
 * delete the billing records with it. Closing:
 * <ul>
 *   <li>replaces the email with {@code closed-<id>@closed.invalid} and the
 *       name with "Closed account"; removes the password hash, social
 *       logins, the pending email change, email-verification and
 *       password-reset tokens, and the webhook signing secrets;</li>
 *   <li>disables the account and bumps its session version, so its JWTs
 *       fail at once, drops any staff role, and deletes its API keys;</li>
 *   <li>turns auto-recharge off and forgets the saved payment method, so the
 *       card is never charged again;</li>
 *   <li>deletes every conversation and its messages;</li>
 *   <li>ends any job still running (FAILED, estimate refunded, as the sweep
 *       would) and purges every job now: media objects deleted, prompt and
 *       URLs redacted, costs kept;</li>
 *   <li>writes an {@code admin.account.closed} audit row.</li>
 * </ul>
 * Kept: credit ledger, usage records, auto-recharge attempts, the Stripe
 * customer id, the balance, and audit rows (deleted after 365 days by
 * {@link DataRetentionJob}).
 *
 * <p>Idempotent: closing a closed account changes nothing more, and retries
 * any job whose media could not be deleted the first time.
 */
@Service
public class AccountClosureService {

    private static final Logger log = LoggerFactory.getLogger(AccountClosureService.class);

    public static final String CLOSED_FULL_NAME = "Closed account";

    private static final List<JobStatus> OPEN = List.of(JobStatus.QUEUED, JobStatus.RUNNING);

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final OAuthIdentityRepository identities;
    private final ConversationRepository conversations;
    private final GenerationJobRepository jobs;
    private final GenerationJobSettlement settlement;
    private final DataRetentionService retention;
    private final AuditLogService audit;
    private final TransactionTemplate tx;

    public AccountClosureService(UserRepository users, ApiKeyRepository apiKeys,
                                 OAuthIdentityRepository identities, ConversationRepository conversations,
                                 GenerationJobRepository jobs, GenerationJobSettlement settlement,
                                 DataRetentionService retention, AuditLogService audit,
                                 PlatformTransactionManager txManager) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.identities = identities;
        this.conversations = conversations;
        this.jobs = jobs;
        this.settlement = settlement;
        this.retention = retention;
        this.audit = audit;
        this.tx = new TransactionTemplate(txManager);
    }

    /** The address a closed account is given; reserved (RFC 2606), so nothing is ever delivered to it. */
    public static String closedEmail(UUID userId) {
        return "closed-" + userId + "@closed.invalid";
    }

    /** What a closure did. {@code jobsNotPurged > 0} means media deletion failed for some jobs; close again to retry. */
    public record Result(
            UUID userId,
            boolean alreadyClosed,
            int apiKeysDeleted,
            int oauthIdentitiesDeleted,
            int conversationsDeleted,
            int openJobsEnded,
            int jobsPurged,
            int jobsNotPurged
    ) { }

    private record AccountStep(boolean alreadyClosed, int apiKeys, int identities, int conversations) { }

    /**
     * Closes {@code userId}'s account; {@code actorId} is the operator,
     * {@code ip} theirs. 404 when there is no such user.
     */
    public Result close(UUID userId, UUID actorId, String ip) {
        // 1. Lock the owner out and anonymise, with the audit row, in one transaction.
        AccountStep account = tx.execute(status -> closeAccount(userId, actorId, ip));

        // 2. End any job still running, so no worker can complete it after the purge.
        int ended = 0;
        for (GenerationJob open : jobs.findByUserIdAndStatusIn(userId, OPEN)) {
            if (settlement.fail(open, "account closed", "account closed")) ended++;
        }

        // 3. Purge every job now. Object storage is outside any transaction;
        //    a job whose media can't be deleted stays unpurged for a retry.
        Instant now = Instant.now();
        int purged = 0;
        int notPurged = 0;
        for (GenerationJob job : jobs.findUnpurgedEndedByUserId(userId)) {
            if (retention.purgeJob(job.getId(), now)) purged++;
            else notPurged++;
        }

        Result result = new Result(userId, account.alreadyClosed(), account.apiKeys(), account.identities(),
                account.conversations(), ended, purged, notPurged);
        if (notPurged > 0) {
            log.warn("Account {} closed, but {} job(s) could not be purged; close it again to retry", userId, notPurged);
        } else {
            log.info("Account {} closed by {}: {}", userId, actorId, result);
        }
        return result;
    }

    private AccountStep closeAccount(UUID userId, UUID actorId, String ip) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "user not found"));
        String closedEmail = closedEmail(userId);
        boolean alreadyClosed = closedEmail.equals(u.getEmail()) && !u.isActive();
        UserRole heldRole = u.getRole();

        u.setRole(UserRole.USER);
        u.setEmail(closedEmail);
        u.setPendingEmail(null);
        u.setFullName(CLOSED_FULL_NAME);
        u.setPasswordHash(null);
        u.setEmailVerificationToken(null);
        u.setEmailVerificationSentAt(null);
        u.setPasswordResetTokenHash(null);
        u.setPasswordResetSentAt(null);
        u.setWebhookSigningSecret(null);
        u.setWebhookSigningSecretPrevious(null);
        u.setWebhookSigningSecretRotatedAt(null);
        // A child's account: the birth month and the parent's email go too.
        u.setBirthMonth(null);
        u.setParentEmail(null);
        u.setParentalConsentTokenHash(null);
        // The auto-recharge cron does not look at is_active; this is what stops it charging.
        u.setAutoRechargeEnabled(false);
        u.setStripePaymentMethodId(null);
        u.setActive(false);
        u.setSessionVersion(u.getSessionVersion() + 1);
        users.save(u);

        int keys = apiKeys.deleteByUserId(userId);
        int links = identities.deleteByUserId(userId);
        int convs = conversations.deleteByUserId(userId);

        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("actorUserId", actorId == null ? null : actorId.toString());
        detail.put("alreadyClosed", alreadyClosed);
        detail.put("role", heldRole.name());
        detail.put("apiKeysDeleted", keys);
        detail.put("oauthIdentitiesDeleted", links);
        detail.put("conversationsDeleted", convs);
        audit.record(AuditLogService.EVT_ADMIN_ACCOUNT_CLOSED, AuditOutcome.SUCCESS, userId, ip, detail);
        return new AccountStep(alreadyClosed, keys, links, convs);
    }
}
