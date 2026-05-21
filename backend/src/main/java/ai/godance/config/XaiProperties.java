package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * xAI (Grok) — OpenAI-compatible {@code /chat/completions} endpoint.
 * Western provider: MUST NOT be added to the Alicloud allowlist.
 */
@ConfigurationProperties(prefix = "godance.providers.xai")
public record XaiProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public XaiProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.xai.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.xai.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.xai.timeout must be positive");
    }
}
