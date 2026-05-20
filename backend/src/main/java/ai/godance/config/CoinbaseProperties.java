package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code godance.coinbase.*}. Mirrors {@link StripeProperties}
 * — required in prod (no fallback), dev gets clearly-marked dummy
 * defaults, {@code apiBase} can be set to a WireMock URL in tests.
 */
@ConfigurationProperties(prefix = "godance.coinbase")
public record CoinbaseProperties(
        String apiKey,
        String webhookSecret,
        String apiBase,
        String apiVersion
) {
    public CoinbaseProperties {
        // Null = YAML didn't bind; throw. Empty tolerated (env-var-shadows-default).
        if (apiKey == null) throw new IllegalStateException("godance.coinbase.api-key must be configured");
        if (webhookSecret == null) throw new IllegalStateException("godance.coinbase.webhook-secret must be configured");
        if (apiBase == null || apiBase.isBlank()) {
            throw new IllegalStateException("godance.coinbase.api-base must be set");
        }
        if (apiVersion == null || apiVersion.isBlank()) {
            throw new IllegalStateException("godance.coinbase.api-version must be set");
        }
    }
}
