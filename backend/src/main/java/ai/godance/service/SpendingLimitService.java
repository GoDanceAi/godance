package ai.godance.service;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.ApiKey;
import ai.godance.domain.User;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UsageRecordRepository;

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
 *   <li><b>Soft cap</b> (80%): a warning email (stubbed as a log line,
 *       matching the signup-verification stub) fired at most once per
 *       period via the {@code *_cap_warned_at} bookkeeping columns.</li>
 * </ul>
 *
 * Deliberately separate from {@code CreditLedgerService} — caps gate
 * requests; the ledger moves money. This does not touch the ledger.
 */
@Service
public class SpendingLimitService {

    private static final Logger log = LoggerFactory.getLogger(SpendingLimitService.class);
    private static final ZoneOffset UTC = ZoneOffset.UTC;
    /** Soft-warning threshold, as a percentage of the cap. */
    private static final int SOFT_PERCENT = 80;

    private final UserRepository users;
    private final ApiKeyRepository apiKeys;
    private final UsageRecordRepository usage;

    public SpendingLimitService(UserRepository users, ApiKeyRepository apiKeys,
                                UsageRecordRepository usage) {
        this.users = users;
        this.apiKeys = apiKeys;
        this.usage = usage;
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
                warn("user " + userId, "daily", userDay, user.getDailyCreditCapCents());
                warned = true;
            }
            if (softCrossed(user.getMonthlyCreditCapCents(), userMonth)
                    && stale(user.getMonthlyCapWarnedAt(), monthStart)) {
                user.setMonthlyCapWarnedAt(Instant.now());
                warn("user " + userId, "monthly", userMonth, user.getMonthlyCreditCapCents());
                warned = true;
            }
            if (warned) users.save(user);
        }
        if (key != null) {
            boolean warned = false;
            if (softCrossed(key.getDailyCreditCapCents(), keyDay)
                    && stale(key.getDailyCapWarnedAt(), dayStart)) {
                key.setDailyCapWarnedAt(Instant.now());
                warn("api-key " + apiKeyId, "daily", keyDay, key.getDailyCreditCapCents());
                warned = true;
            }
            if (softCrossed(key.getMonthlyCreditCapCents(), keyMonth)
                    && stale(key.getMonthlyCapWarnedAt(), monthStart)) {
                key.setMonthlyCapWarnedAt(Instant.now());
                warn("api-key " + apiKeyId, "monthly", keyMonth, key.getMonthlyCreditCapCents());
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

    /** 80% threshold without floating point: spent/cap ≥ 0.8 ⇔ spent*100 ≥ cap*80. */
    private static boolean softCrossed(Long capCents, long spentCents) {
        return capCents != null && capCents > 0
                && spentCents * 100 >= capCents * (long) SOFT_PERCENT;
    }

    /** A warning is due when none was sent this period (timestamp null or before the period start). */
    private static boolean stale(Instant warnedAt, Instant periodStart) {
        return warnedAt == null || warnedAt.isBefore(periodStart);
    }

    private static void warn(String subject, String period, long spentCents, long capCents) {
        log.info("[email-stub] Spending warning for {}: {} usage at {} of {} cents "
                + "({}% soft cap reached)", subject, period, spentCents, capCents, SOFT_PERCENT);
    }

    /** Thrown on a hard-cap (100%) breach; the spending-limit filter maps it to 402. */
    public static final class SpendingLimitExceededException extends RuntimeException {
        public SpendingLimitExceededException(String message) {
            super(message);
        }
    }
}
