package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Google Generative AI ("Gemini Developer API") config — uses an API
 * key on {@code generativelanguage.googleapis.com}. The brand-name is
 * Vertex (see {@link ai.godance.domain.Provider#GOOGLE_VERTEX}), but the
 * adapter authenticates via API key for v1 simplicity. True Vertex AI
 * OAuth + service account is a polish task.
 */
@ConfigurationProperties(prefix = "godance.providers.google")
public record GoogleProperties(
        String apiKey,
        String apiBase,
        Duration timeout
) {
    public GoogleProperties {
        // Null = YAML didn't bind; throw. Empty tolerated (env-var-shadows-default).
        if (apiKey == null) throw new IllegalStateException("godance.providers.google.api-key must be configured");
        if (apiBase == null || apiBase.isBlank())
            throw new IllegalStateException("godance.providers.google.api-base must be set");
        if (timeout == null || timeout.isNegative() || timeout.isZero())
            throw new IllegalStateException("godance.providers.google.timeout must be positive");
    }
}
