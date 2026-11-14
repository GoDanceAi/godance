package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Recording a sign-up's first touch ({@link ai.godance.service.AttributionService}).
 *
 * @param firstTouchWindowHours      how long after an account is created
 *                                   its first touch can still be written;
 *                                   0 means it never is
 * @param firstTouchLimitPerAccount  calls one account may make per hour;
 *                                   the app makes one per sign-in
 */
@ConfigurationProperties(prefix = "godance.attribution")
public record AttributionProperties(
        @DefaultValue("168") int firstTouchWindowHours,
        @DefaultValue("20") int firstTouchLimitPerAccount
) {
    private static final String PREFIX = "godance.attribution.";

    public AttributionProperties {
        if (firstTouchWindowHours < 0) {
            throw new IllegalStateException(PREFIX + "first-touch-window-hours must be >= 0; got "
                    + firstTouchWindowHours);
        }
        if (firstTouchLimitPerAccount < 1) {
            throw new IllegalStateException(PREFIX + "first-touch-limit-per-account must be >= 1; got "
                    + firstTouchLimitPerAccount);
        }
    }

    /** {@link #firstTouchWindowHours} as a duration. */
    public Duration firstTouchWindow() {
        return Duration.ofHours(firstTouchWindowHours);
    }
}
