package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Fixed-window limits for the pre-auth endpoints, enforced against Redis
 * by {@link ai.godance.security.RateLimiter}. Each {@link Limit} allows
 * {@code requests} calls per {@code window} per subject (client IP or
 * normalized email).
 *
 * <p>{@code enabled=false} switches every limit off. The test profile does
 * that so integration suites, which share one Redis and one client IP,
 * cannot trip each other's limits.
 */
@ConfigurationProperties(prefix = "godance.rate-limit")
public record RateLimitProperties(
        boolean enabled,
        Limit loginPerIp,
        Limit loginPerEmail,
        Limit signupPerIp,
        Limit verifyPerIp
) {
    public RateLimitProperties {
        require("login-per-ip", loginPerIp);
        require("login-per-email", loginPerEmail);
        require("signup-per-ip", signupPerIp);
        require("verify-per-ip", verifyPerIp);
    }

    public record Limit(int requests, Duration window) { }

    private static void require(String name, Limit limit) {
        if (limit == null) {
            throw new IllegalStateException("godance.rate-limit." + name + " must be set");
        }
        if (limit.requests() < 1) {
            throw new IllegalStateException("godance.rate-limit." + name + ".requests must be >= 1");
        }
        if (limit.window() == null || !limit.window().isPositive()) {
            throw new IllegalStateException("godance.rate-limit." + name + ".window must be positive");
        }
    }
}
