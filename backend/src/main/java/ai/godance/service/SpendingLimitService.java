package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.ApiKey;
import ai.godance.domain.SpendHold;
import ai.godance.domain.User;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.SpendHoldRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.service.mail.MailService;

/**
 * Enforces per-user and per-API-key spending caps (outcome-brief
 * Feature #5). The cap <em>limits</em> already exist as columns on
 * {@code users} / {@code api_keys}; this fills the enforcement gap.
 *
 * <p>Spend for a period is the {@code SUM(cost_cents)} of
 * {@code usage_records} since the start of the current UTC day / month —
 * so the counter "resets" automatically as the window rolls forward; no
 * stored counter to reset.
 *
 * <ul>
 *   <li><b>Hard cap</b> (100%): {@link SpendingLimitExceededException}
 *       → the filter surfaces 402 Payment Required.</li>
 *   <li><b>Soft cap</b> (80%): a warning email (via {@link MailService};
 *       the dev sender logs it as an {@code [email-stub]} line) fired at
 *       most once per period via the {@code *_cap_warned_at} bookkeeping
 *       columns.</li>
 * </ul>
 *
 * <p>The check above runs before the request, on completed spend only. The
 * hard guarantee is {@link #reserve}: inside the pre-debit transaction a
 * capped account's request holds its estimate, and is refused if completed
 * spend, other requests' open holds and this estimate together pass a cap
 * (spec risk R-10).
 *
 * Deliberately separate from {@code CreditLedgerService} — caps gate
 * requests; the ledger moves money. This does not touch the ledger.
 */
@Service
public class SpendingLimitService {

    private static final ZoneOffset UTC = ZoneOffset.UTC;
    /** Soft-warning threshold, as a percentage of the cap. */
    private static final int SOFT_PERCENT = 80;
    /** Longest a hold counts: past any request's lifetime, so a lost request can't block an account. */
    static final Duration HOLD_TTL = Duration.ofHours(1);

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final UsageRecordRepository usage;
    private final MailService mail;
    private final SpendHoldRepository holds;

    public SpendingLimitService(UserRepository users, ApiKeyRepository apiKeys,
                                UsageRecordRepository usage, MailService mail,
                                SpendHoldRepository holds) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.usage = usage;
        this.mail = mail;
        this.holds = holds;
    }

    /**
     * Holds a request's estimate against the caller's caps (spec risk R-10).
     *
     * <p>Runs inside the pre-debit transaction, after the balance update has
     * locked the user's row, so requests from one account reserve one at a
     * time and each sees the others' holds. A hold counts until the
     * request's usage row exists or the request is refunded ({@link #release}),
     * and for {@link #HOLD_TTL} at most. An account with no cap, on the user
     * or on the key, writes no hold.
     *
     * @throws SpendingLimitExceededException when completed spend, open holds
     *         and this estimate together would pass a hard cap
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void reserve(UUID userId, UUID apiKeyId, long cents, String requestId) {
        User user = users.findById(userId).orElse(null);
        ApiKey key = (apiKeyId != null) ? apiKeys.findById(apiKeyId).orElse(null) : null;
        boolean userCapped = hasCap(user, true) || hasCap(user, false);
        boolean keyCapped = hasCap(key, true) || hasCap(key, false);
        if (!userCapped && !keyCapped) return;

        Instant now = Instant.now();
        Instant holdsSince = now.minus(HOLD_TTL);
        Instant dayStart = LocalDate.now(UTC).atStartOfDay(UTC).toInstant();
        Instant monthStart = YearMonth.now(UTC).atDay(1).atStartOfDay(UTC).toInstant();
        holds.deleteClosedForUser(userId, holdsSince);

        if (userCapped) {
            long held = holds.sumOpenForUser(userId, holdsSince);
            reserveCheck("daily spending cap", user.getDailyCreditCapCents(),
                    () -> usage.sumCostCentsForUserSince(userId, dayStart), held, cents);
            reserveCheck("monthly spending cap", user.getMonthlyCreditCapCents(),
                    () -> usage.sumCostCentsForUserSince(userId, monthStart), held, cents);
        }
        if (keyCapped) {
            long held = holds.sumOpenForApiKey(apiKeyId, holdsSince);
            reserveCheck("API-key daily spending cap", key.getDailyCreditCapCents(),
                    () -> usage.sumCostCentsForApiKeySince(apiKeyId, dayStart), held, cents);
            reserveCheck("API-key monthly spending cap", key.getMonthlyCreditCapCents(),
                    () -> usage.sumCostCentsForApiKeySince(apiKeyId, monthStart), held, cents);
        }

        SpendHold hold = new SpendHold();
        hold.setRequestId(requestId);
        hold.setUserId(userId);
        hold.setApiKeyId(apiKeyId);
        hold.setCents(cents);
        holds.save(hold);
    }

    /** Ends a refunded request's hold. A request with no hold is a no-op. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void release(String requestId) {
        if (holds.existsById(requestId)) {
            holds.deleteById(requestId);
        }
    }

    /**
     * Check the caller against their daily + monthly caps before a
     * billable request runs. {@code apiKeyId} is non-null only when the
     * request authenticated with an API key.
     *
     * @throws SpendingLimitExceededException on a hard-cap (100%) breach
     */
    @Transactional
    public void enforce(UUID userId, UUID apiKeyId) {
        Instant dayStart = LocalDate.now(UTC).atStartOfDay(UTC).toInstant();
        Instant monthStart = YearMonth.now(UTC).atDay(1).atStartOfDay(UTC).toInstant();

        User user = users.findById(userId).orElse(null);
        ApiKey key = (apiKeyId != null) ? apiKeys.findById(apiKeyId).orElse(null) : null;

        // Spend is only queried for windows that actually carry a cap.
        long userDay = (hasCap(user, true)) ? usage.sumCostCentsForUserSince(userId, dayStart) : 0;
        long userMonth = (hasCap(user, false)) ? usage.sumCostCentsForUserSince(userId, monthStart) : 0;
        long keyDay = (hasCap(key, true)) ? usage.sumCostCentsForApiKeySince(apiKeyId, dayStart) : 0;
        long keyMonth = (hasCap(key, false)) ? usage.sumCostCentsForApiKeySince(apiKeyId, monthStart) : 0;

        // Hard caps first — any breach blocks the request before any soft state is written.
        if (user != null) {
            hardCheck("daily spending cap", user.getDailyCreditCapCents(), userDay);
            hardCheck("monthly spending cap", user.getMonthlyCreditCapCents(), userMonth);
        }
        if (key != null) {
            hardCheck("API-key daily spending cap", key.getDailyCreditCapCents(), keyDay);
            hardCheck("API-key monthly spending cap", key.getMonthlyCreditCapCents(), keyMonth);
        }

        // Soft (80%) warnings — fired at most once per period.
        if (user != null) {
            boolean warned = false;
            if (softCrossed(user.getDailyCreditCapCents(), userDay)
                    && stale(user.getDailyCapWarnedAt(), dayStart)) {
                user.setDailyCapWarnedAt(Instant.now());
                warn(user.getEmail(), "user " + userId, "daily", userDay, user.getDailyCreditCapCents());
                warned = true;
            }
            if (softCrossed(user.getMonthlyCreditCapCents(), userMonth)
                    && stale(user.getMonthlyCapWarnedAt(), monthStart)) {
                user.setMonthlyCapWarnedAt(Instant.now());
                warn(user.getEmail(), "user " + userId, "monthly", userMonth, user.getMonthlyCreditCapCents());
                warned = true;
            }
            if (warned) users.save(user);
        }
        if (key != null) {
            boolean warned = false;
            // API-key warnings go to the key owner — the authenticated user loaded above.
            String ownerEmail = (user != null) ? user.getEmail() : null;
            if (softCrossed(key.getDailyCreditCapCents(), keyDay)
                    && stale(key.getDailyCapWarnedAt(), dayStart)) {
                key.setDailyCapWarnedAt(Instant.now());
                warn(ownerEmail, "api-key " + apiKeyId, "daily", keyDay, key.getDailyCreditCapCents());
                warned = true;
            }
            if (softCrossed(key.getMonthlyCreditCapCents(), keyMonth)
                    && stale(key.getMonthlyCapWarnedAt(), monthStart)) {
                key.setMonthlyCapWarnedAt(Instant.now());
                warn(ownerEmail, "api-key " + apiKeyId, "monthly", keyMonth, key.getMonthlyCreditCapCents());
                warned = true;
            }
            if (warned) apiKeys.save(key);
        }
    }

    private static boolean hasCap(User user, boolean daily) {
        if (user == null) return false;
        return (daily ? user.getDailyCreditCapCents() : user.getMonthlyCreditCapCents()) != null;
    }

    private static boolean hasCap(ApiKey key, boolean daily) {
        if (key == null) return false;
        return (daily ? key.getDailyCreditCapCents() : key.getMonthlyCreditCapCents()) != null;
    }

    private static void hardCheck(String label, Long capCents, long spentCents) {
        if (capCents != null && capCents > 0 && spentCents >= capCents) {
            throw new SpendingLimitExceededException(
                    label + " reached (" + spentCents + " of " + capCents
                    + " cents used) — resets at the start of the next period");
        }
    }

    /** Refuses the estimate when spent + held + estimate would pass the cap; reads spend only for a set cap. */
    private static void reserveCheck(String label, Long capCents, java.util.function.LongSupplier spent,
                                     long heldCents, long estimateCents) {
        if (capCents == null || capCents <= 0) return;
        long committed = spent.getAsLong() + heldCents;
        if (committed + estimateCents > capCents) {
            throw new SpendingLimitExceededException(
                    label + " would be exceeded (" + committed + " of " + capCents
                    + " cents used or held; this request needs up to " + estimateCents + ")");
        }
    }

    /** 80% threshold without floating point: spent/cap ≥ 0.8 ⇔ spent*100 ≥ cap*80. */
    private static boolean softCrossed(Long capCents, long spentCents) {
        return capCents != null && capCents > 0
                && spentCents * 100 >= capCents * (long) SOFT_PERCENT;
    }

    /** A warning is due when none was sent this period (timestamp null or before the period start). */
    private static boolean stale(Instant warnedAt, Instant periodStart) {
        return warnedAt == null || warnedAt.isBefore(periodStart);
    }

    /** Mail failures are swallowed inside MailService — a warning must never block the request. */
    private void warn(String toEmail, String subject, String period, long spentCents, long capCents) {
        mail.sendSpendWarning(toEmail, subject, period, spentCents, capCents);
    }

    /**
     * Thrown on a hard-cap breach. A 402 wherever it surfaces: the
     * spending-limit filter writes it itself, and from a controller (a
     * reservation refused at pre-debit) WebFlux renders the status.
     */
    public static final class SpendingLimitExceededException extends ResponseStatusException {
        public SpendingLimitExceededException(String message) {
            super(HttpStatus.PAYMENT_REQUIRED, message);
        }
    }
}
