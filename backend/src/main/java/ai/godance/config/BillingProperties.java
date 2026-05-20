package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Top-up bounds + platform markup (Feature #4 of the outcome brief —
 * 5% text default; 10–15% reserved for the image/video tiers when
 * those land in Session 8). Per-model overrides live in
 * {@link ai.godance.gateway.ModelCatalog}.
 */
@ConfigurationProperties(prefix = "godance.billing")
public record BillingProperties(
        long topupMinCents,
        long topupMaxCents,
        String currency,          // ISO 4217 (lowercase per Stripe), e.g. "usd"
        double textMarkupPercent,
        double imageMarkupPercent,
        double videoMarkupPercent
) {
    public BillingProperties {
        if (topupMinCents < 50) {
            // Stripe enforces a $0.50 minimum on most card brands; reject smaller in our config.
            throw new IllegalStateException("godance.billing.topup-min-cents must be >= 50");
        }
        if (topupMaxCents <= topupMinCents) {
            throw new IllegalStateException("godance.billing.topup-max-cents must exceed topup-min-cents");
        }
        if (currency == null || currency.length() != 3) {
            throw new IllegalStateException("godance.billing.currency must be 3-letter ISO 4217");
        }
        if (textMarkupPercent < 0 || imageMarkupPercent < 0 || videoMarkupPercent < 0) {
            throw new IllegalStateException("godance.billing.*-markup-percent must be non-negative");
        }
    }
}
