package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "godance.providers.anthropic")
public record AnthropicProperties(
        String apiKey,
        String apiBase,
        String apiVersion,
        Duration timeout
) {
    public AnthropicProperties {
        // Null = YAML didn't bind (structural problem). Empty is tolerated so
        // dev envs with `ANTHROPIC_API_KEY=` set don't fail context startup —
        // the upstream call will fail with 401 instead, surfaced at first use.
        if (apiKey == null) throw new IllegalStateException("godance.providers.anthropic.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.anthropic.api-base must be set");
        if (apiVersion == null || apiVersion.isBlank())
            throw new IllegalStateException("godance.providers.anthropic.api-version must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.anthropic.timeout must be positive");
    }
}
