package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Stability AI — image generation (SDXL). Bearer-token API; the v1
 * {@code text-to-image} endpoint returns base64 artifacts synchronously,
 * so a single request {@code timeout} is enough (no poll loop). Western
 * provider — never on the Alicloud allowlist.
 */
@ConfigurationProperties(prefix = "godance.providers.stability")
public record StabilityProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public StabilityProperties {
        // Null = YAML didn't bind; throw. Empty tolerated (env-var-shadows-default).
        if (apiKey == null) throw new IllegalStateException("godance.providers.stability.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.stability.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.stability.timeout must be positive");
    }
}
