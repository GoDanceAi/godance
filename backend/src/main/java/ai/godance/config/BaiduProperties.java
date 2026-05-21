package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Baidu (ERNIE) — OpenAI-compatible {@code /chat/completions} endpoint
 * on the Qianfan v2 platform. China-hosted; on the Alicloud allowlist.
 */
@ConfigurationProperties(prefix = "godance.providers.baidu")
public record BaiduProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public BaiduProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.baidu.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.baidu.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.baidu.timeout must be positive");
    }
}
