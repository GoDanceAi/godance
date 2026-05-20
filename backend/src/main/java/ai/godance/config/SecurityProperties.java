package ai.godance.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Cross-cutting security knobs not specific to JWT / providers.
 *
 * <p>{@code corsAllowedOrigins} controls which browser origins may call
 * {@code /v1/**} from JavaScript. Defaults in {@code application.yml}
 * permit local dev (Vite on :5173 + preview on :4173). Production
 * {@code application-prod.yml} narrows to {@code https://app.godance.ai}
 * plus whatever else is set via {@code CORS_ALLOWED_ORIGINS} env var.
 */
@ConfigurationProperties(prefix = "godance.security")
public record SecurityProperties(
        List<String> corsAllowedOrigins
) {
    public SecurityProperties {
        corsAllowedOrigins = corsAllowedOrigins == null ? List.of() : List.copyOf(corsAllowedOrigins);
        if (corsAllowedOrigins.isEmpty()) {
            throw new IllegalStateException(
                    "godance.security.cors-allowed-origins must list at least one origin");
        }
        for (String o : corsAllowedOrigins) {
            if (o == null || o.isBlank() || !(o.startsWith("http://") || o.startsWith("https://"))) {
                throw new IllegalStateException(
                        "CORS origins must be absolute http(s) URLs; got '" + o + "'");
            }
        }
    }
}
