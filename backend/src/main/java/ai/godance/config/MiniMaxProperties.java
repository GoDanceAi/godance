package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * MiniMax — OpenAI-compatible {@code /chat/completions} endpoint.
 * China-hosted; on the Alicloud provider allowlist.
 */
@ConfigurationProperties(prefix = "godance.providers.minimax")
public record MiniMaxProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public MiniMaxProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.minimax.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.minimax.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.minimax.timeout must be positive");
    }
}
