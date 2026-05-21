package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Zhipu AI (GLM) — OpenAI-compatible {@code /chat/completions} endpoint
 * on the BigModel platform. China-hosted; on the Alicloud allowlist.
 */
@ConfigurationProperties(prefix = "godance.providers.zhipu")
public record ZhipuProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public ZhipuProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.zhipu.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.zhipu.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.zhipu.timeout must be positive");
    }
}
