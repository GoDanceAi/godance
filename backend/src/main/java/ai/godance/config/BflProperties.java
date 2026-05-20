package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Black Forest Labs (Flux) — async image gen via submit + polling.
 * Auth header is {@code x-key}, not Bearer. See bfl.ai/docs.
 */
@ConfigurationProperties(prefix = "godance.providers.bfl")
public record BflProperties(
        String apiKey,
        String apiBase,
        Duration pollInterval,
        Duration pollTimeout
) {
    public BflProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.bfl.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.bfl.api-base must be set");
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero())
            throw new IllegalStateException("godance.providers.bfl.poll-interval must be positive");
        if (pollTimeout == null || pollTimeout.isNegative() || pollTimeout.isZero())
            throw new IllegalStateException("godance.providers.bfl.poll-timeout must be positive");
        if (pollTimeout.compareTo(pollInterval) < 0)
            throw new IllegalStateException("godance.providers.bfl.poll-timeout must exceed poll-interval");
    }
}
