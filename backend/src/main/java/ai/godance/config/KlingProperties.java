package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Kling (Kuaishou) video generation — async submit + poll. China-hosted,
 * so {@code KLING} is on the Alicloud provider allowlist.
 *
 * <p>Bearer-token auth for v1. The production Kling API also accepts a
 * JWT signed from an access-key/secret pair; WireMock-recorded cassettes
 * will lock in the real auth + payload shape when production keys land.
 */
@ConfigurationProperties(prefix = "godance.providers.kling")
public record KlingProperties(
        String apiKey,
        String apiBase,
        Duration pollInterval,
        Duration pollTimeout
) {
    public KlingProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.kling.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.kling.api-base must be set");
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero())
            throw new IllegalStateException("godance.providers.kling.poll-interval must be positive");
        if (pollTimeout == null || pollTimeout.isNegative() || pollTimeout.isZero())
            throw new IllegalStateException("godance.providers.kling.poll-timeout must be positive");
        if (pollTimeout.compareTo(pollInterval) < 0)
            throw new IllegalStateException("godance.providers.kling.poll-timeout must exceed poll-interval");
    }
}
