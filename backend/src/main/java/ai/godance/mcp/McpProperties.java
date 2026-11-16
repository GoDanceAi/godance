package ai.godance.mcp;

import java.net.URI;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import ai.godance.config.RateLimitProperties.Limit;

/**
 * The MCP server at {@code /mcp} (round 13 brief 01), {@code godance.mcp.*}.
 * Every setting has a default, so the server needs no configuration.
 *
 * @param enabled         {@code false} takes {@code /mcp} away (404); on by default
 * @param rateLimit       requests per API key per window, counted by the
 *                        existing {@link ai.godance.security.RateLimiter}
 *                        (and switched off with it by
 *                        {@code godance.rate-limit.enabled=false})
 * @param galleryUrl      where the published gallery is, for
 *                        {@code search_recipes} and {@code get_recipe}:
 *                        {@code <galleryUrl>/index.json} and
 *                        {@code <galleryUrl>/<slug>.json}
 * @param galleryCacheTtl how long the gallery index is kept before it is read again
 * @param galleryTimeout  how long a read of the gallery may take
 */
@ConfigurationProperties(prefix = "godance.mcp")
public record McpProperties(
        Boolean enabled,
        Limit rateLimit,
        URI galleryUrl,
        Duration galleryCacheTtl,
        Duration galleryTimeout
) {

    /** Two calls a second, sustained: an agent polling a job every few seconds stays far below it. */
    static final Limit DEFAULT_RATE_LIMIT = new Limit(120, Duration.ofMinutes(1));

    public McpProperties {
        if (enabled == null) enabled = Boolean.TRUE;
        if (rateLimit == null) rateLimit = DEFAULT_RATE_LIMIT;
        if (rateLimit.requests() < 1 || rateLimit.window() == null || !rateLimit.window().isPositive()) {
            throw new IllegalStateException("godance.mcp.rate-limit needs requests >= 1 and a positive window");
        }
        if (galleryUrl == null) galleryUrl = URI.create("https://godance.ai/gallery");
        if (galleryCacheTtl == null) galleryCacheTtl = Duration.ofMinutes(5);
        if (galleryTimeout == null) galleryTimeout = Duration.ofSeconds(5);
    }
}
