package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Moonshot AI (Kimi) — OpenAI-compatible {@code /chat/completions}
 * endpoint. China-hosted; on the Alicloud provider allowlist.
 */
@ConfigurationProperties(prefix = "godance.providers.moonshot")
public record MoonshotProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public MoonshotProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.moonshot.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.moonshot.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.moonshot.timeout must be positive");
    }
}
