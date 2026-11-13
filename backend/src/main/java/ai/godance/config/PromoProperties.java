package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Promotional credit, granted from the platform's own pocket: every cent
 * of it is real provider spend. Each promotion is off until its amount is
 * set above zero, so a fresh deployment of this public repo grants nothing.
 *
 * <p>The signup credit ({@link ai.godance.service.SignupCreditService}) is
 * granted once per account, when its email is first verified. Production
 * turns it on with {@code PROMO_SIGNUP_CREDIT_CENTS}.
 *
 * @param signupCreditCents      the signup credit in cents; 0 turns the
 *                               feature off entirely
 * @param signupCreditDailyLimit the most signup credits granted in one UTC
 *                               day; past it, none until the next day
 */
@ConfigurationProperties(prefix = "godance.promo")
public record PromoProperties(
        @DefaultValue("0") long signupCreditCents,
        @DefaultValue("200") int signupCreditDailyLimit
) {
    public PromoProperties {
        if (signupCreditCents < 0) {
            throw new IllegalStateException("godance.promo.signup-credit-cents must be >= 0");
        }
        if (signupCreditDailyLimit < 0) {
            throw new IllegalStateException("godance.promo.signup-credit-daily-limit must be >= 0");
        }
    }

    /** True when the signup credit is switched on. */
    public boolean signupCreditEnabled() {
        return signupCreditCents > 0;
    }
}
