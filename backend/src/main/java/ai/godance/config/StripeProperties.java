package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code godance.stripe.*}. Test/dev defaults are dummy values
 * that won't reach Stripe — the prod profile requires real env-driven
 * keys (no fallback).
 */
@ConfigurationProperties(prefix = "godance.stripe")
public record StripeProperties(
        String secretKey,
        String webhookSecret,
        String successUrl,
        String cancelUrl,
        String apiBase           // null in prod; set in tests to point at WireMock
) {
    public StripeProperties {
        // Null = YAML didn't bind; throw. Empty allowed (env-var-shadows-default
        // pattern); upstream call surfaces the misconfig at request time.
        if (secretKey == null) throw new IllegalStateException("godance.stripe.secret-key must be configured");
        if (webhookSecret == null) throw new IllegalStateException("godance.stripe.webhook-secret must be configured");
        if (successUrl == null || successUrl.isBlank()) {
            throw new IllegalStateException("godance.stripe.success-url must be set");
        }
        if (cancelUrl == null || cancelUrl.isBlank()) {
            throw new IllegalStateException("godance.stripe.cancel-url must be set");
        }
    }
}
