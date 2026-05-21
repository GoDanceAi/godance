package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code godance.nowpayments.*}. Mirrors {@link StripeProperties}
 * — required in prod (no fallback), dev gets clearly-marked dummy
 * defaults, {@code apiBase} can be set to a WireMock URL in tests.
 *
 * <p>{@code ipnSecret} is the IPN-callback secret from the NOWPayments
 * dashboard — used to verify the {@code x-nowpayments-sig} HMAC-SHA512
 * over each webhook body. Distinct from {@code apiKey}, which authenticates
 * outbound REST calls.
 */
@ConfigurationProperties(prefix = "godance.nowpayments")
public record NowPaymentsProperties(
        String apiKey,
        String ipnSecret,
        String apiBase,
        String successUrl,
        String cancelUrl
) {
    public NowPaymentsProperties {
        // Null = YAML didn't bind; throw. Empty tolerated (env-var-shadows-default).
        if (apiKey == null) {
            throw new IllegalStateException("godance.nowpayments.api-key must be configured");
        }
        if (ipnSecret == null) {
            throw new IllegalStateException("godance.nowpayments.ipn-secret must be configured");
        }
        if (apiBase == null || apiBase.isBlank()) {
            throw new IllegalStateException("godance.nowpayments.api-base must be set");
        }
        if (successUrl == null || successUrl.isBlank()) {
            throw new IllegalStateException("godance.nowpayments.success-url must be set");
        }
        if (cancelUrl == null || cancelUrl.isBlank()) {
            throw new IllegalStateException("godance.nowpayments.cancel-url must be set");
        }
    }
}
