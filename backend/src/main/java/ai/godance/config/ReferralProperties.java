package ai.godance.config;

import java.time.Duration;
import java.util.EnumSet;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

import ai.godance.domain.CreditSource;

/**
 * The referral reward ({@link ai.godance.service.ReferralRewardService}):
 * when an invited user first tops up, both sides get a REFERRAL credit.
 * Every cent of it is real provider spend, so both amounts default to 0,
 * and while both are 0 the feature is off: no code is made, every claim is
 * refused and no top-up path does anything new.
 *
 * @param referrerRewardCents   credit to the inviting user, in cents
 * @param refereeRewardCents    credit to the invited user, in cents
 * @param minTopupCents         the smallest top-up that qualifies; a smaller
 *                              one leaves the referral pending
 * @param monthlyRewardLimit    the most rewards one referrer earns in a UTC
 *                              month; past it the invited user is still
 *                              credited and the referrer is not
 * @param claimWindowHours      how long after signing up a code can be claimed
 * @param qualifyingSources     the top-up rails a reward is paid on:
 *                              {@code STRIPE} (card, ACH, SEPA), {@code NOWPAYMENTS} (USDT)
 * @param claimLimitPerAccount  claims one account may try per hour
 * @param claimLimitPerIp       claims one client IP may try per hour
 */
@ConfigurationProperties(prefix = "godance.promo.referral")
public record ReferralProperties(
        @DefaultValue("0") long referrerRewardCents,
        @DefaultValue("0") long refereeRewardCents,
        @DefaultValue("1000") long minTopupCents,
        @DefaultValue("20") int monthlyRewardLimit,
        @DefaultValue("168") int claimWindowHours,
        @DefaultValue("STRIPE") Set<CreditSource> qualifyingSources,
        @DefaultValue("5") int claimLimitPerAccount,
        @DefaultValue("20") int claimLimitPerIp
) {
    /** The payment rails that credit a top-up today; a reward can be paid only on these. */
    public static final Set<CreditSource> PAYMENT_RAILS =
            Set.copyOf(EnumSet.of(CreditSource.STRIPE, CreditSource.NOWPAYMENTS));

    private static final String PREFIX = "godance.promo.referral.";

    public ReferralProperties {
        atLeast("referrer-reward-cents", referrerRewardCents, 0);
        atLeast("referee-reward-cents", refereeRewardCents, 0);
        atLeast("min-topup-cents", minTopupCents, 0);
        atLeast("monthly-reward-limit", monthlyRewardLimit, 0);
        atLeast("claim-window-hours", claimWindowHours, 0);
        atLeast("claim-limit-per-account", claimLimitPerAccount, 1);
        atLeast("claim-limit-per-ip", claimLimitPerIp, 1);
        if (qualifyingSources == null || qualifyingSources.isEmpty()) {
            throw new IllegalStateException(PREFIX + "qualifying-sources must name at least one of "
                    + PAYMENT_RAILS);
        }
        for (CreditSource source : qualifyingSources) {
            if (!PAYMENT_RAILS.contains(source)) {
                throw new IllegalStateException(PREFIX + "qualifying-sources may name only "
                        + PAYMENT_RAILS + "; got " + source);
            }
        }
        qualifyingSources = Set.copyOf(qualifyingSources);
    }

    /** True when either reward is above zero: the feature is switched on. */
    public boolean enabled() {
        return referrerRewardCents > 0 || refereeRewardCents > 0;
    }

    /** {@link #claimWindowHours} as a duration. */
    public Duration claimWindow() {
        return Duration.ofHours(claimWindowHours);
    }

    private static void atLeast(String name, long value, long min) {
        if (value < min) {
            throw new IllegalStateException(PREFIX + name + " must be >= " + min + "; got " + value);
        }
    }
}
