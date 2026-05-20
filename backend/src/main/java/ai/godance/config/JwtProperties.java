package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Bound from {@code godance.jwt.*} in application.yml.
 * HS256 v1 — RS256 upgrade path noted in PROGRESS.md for v1.5 once
 * Alicloud is hot and we want secret-separated cross-region issuance.
 */
@ConfigurationProperties(prefix = "godance.jwt")
public record JwtProperties(
        String secret,
        long expiryMinutes,
        String issuer
) {
    public JwtProperties {
        if (secret == null || secret.getBytes().length < 32) {
            throw new IllegalStateException(
                    "godance.jwt.secret must be at least 32 bytes (256 bits) for HS256");
        }
        if (expiryMinutes <= 0) {
            throw new IllegalStateException("godance.jwt.expiry-minutes must be positive");
        }
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalStateException("godance.jwt.issuer must be set");
        }
    }
}
