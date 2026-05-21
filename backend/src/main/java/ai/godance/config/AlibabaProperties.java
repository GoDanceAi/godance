package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Alibaba DashScope (Model Studio) — hosts Qwen text models and
 * Qwen-Image. The OpenAI-compatible mode serves {@code /chat/completions}.
 * China-hosted; on the Alicloud provider allowlist.
 *
 * <p>Shared block: the image workstream reuses this for Qwen-Image —
 * extend it, do not create a second {@code alibaba} block.
 */
@ConfigurationProperties(prefix = "godance.providers.alibaba")
public record AlibabaProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public AlibabaProperties {
        if (apiKey == null) throw new IllegalStateException("godance.providers.alibaba.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.alibaba.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.alibaba.timeout must be positive");
    }
}
