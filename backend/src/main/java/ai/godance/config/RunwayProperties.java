package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Runway (Gen-4) video generation — async submit + poll. Western
 * provider: {@code RUNWAY} MUST NEVER appear on the Alicloud allowlist.
 *
 * <p>Bearer-token auth plus a pinned {@code X-Runway-Version} header
 * (the header value is a constant in {@code RunwayVideoProvider}).
 */
@ConfigurationProperties(prefix = "godance.providers.runway")
public record RunwayProperties(
        String apiKey,
        String apiBase,
        Duration pollInterval,
        Duration pollTimeout
) {
    public RunwayProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.runway.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.runway.api-base must be set");
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero())
            throw new IllegalStateException("godance.providers.runway.poll-interval must be positive");
        if (pollTimeout == null || pollTimeout.isNegative() || pollTimeout.isZero())
            throw new IllegalStateException("godance.providers.runway.poll-timeout must be positive");
        if (pollTimeout.compareTo(pollInterval) < 0)
            throw new IllegalStateException("godance.providers.runway.poll-timeout must exceed poll-interval");
    }
}
