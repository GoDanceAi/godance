package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Volcengine Ark — multimodal Chinese provider hosting Doubao (text),
 * Seedream (image), Seedance (video). Bearer-token API. The Alicloud
 * region's strategic anchor — Ark is explicitly on the Alicloud allowlist.
 *
 * <p>Video task pattern is async submit + poll; settings here mirror
 * {@link BflProperties}.
 */
@ConfigurationProperties(prefix = "godance.providers.volcengine")
public record VolcengineProperties(
        String apiKey,
        String apiBase,
        Duration pollInterval,
        Duration pollTimeout
) {
    public VolcengineProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.volcengine.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.volcengine.api-base must be set");
        if (pollInterval == null || pollInterval.isNegative() || pollInterval.isZero())
            throw new IllegalStateException("godance.providers.volcengine.poll-interval must be positive");
        if (pollTimeout == null || pollTimeout.isNegative() || pollTimeout.isZero())
            throw new IllegalStateException("godance.providers.volcengine.poll-timeout must be positive");
    }
}
