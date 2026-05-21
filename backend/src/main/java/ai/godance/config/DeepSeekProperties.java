package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * DeepSeek — OpenAI-compatible {@code /chat/completions} endpoint.
 * China-hosted; on the Alicloud provider allowlist.
 */
@ConfigurationProperties(prefix = "godance.providers.deepseek")
public record DeepSeekProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public DeepSeekProperties {
        // Null = YAML didn't bind; throw. Empty tolerated (env-var-shadows-default).
        if (apiKey == null) throw new IllegalStateException("godance.providers.deepseek.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.deepseek.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.deepseek.timeout must be positive");
    }
}
