package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Single platform OpenAI key per the reseller model — no per-user keys
 * in v1. Tests redirect to WireMock via {@code api-base}.
 */
@ConfigurationProperties(prefix = "godance.providers.openai")
public record OpenAIProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public OpenAIProperties {
        // Null = YAML didn't bind; throw. Empty tolerated (env-var-shadows-default).
        if (apiKey == null) throw new IllegalStateException("godance.providers.openai.api-key must be configured");
        if (apiBase == null || apiBase.isBlank()) {
            throw new IllegalStateException("godance.providers.openai.api-base must be set");
        }
        if (timeout == null || timeout.isNegative() || timeout.isZero()) {
            throw new IllegalStateException("godance.providers.openai.timeout must be positive");
        }
    }
}
