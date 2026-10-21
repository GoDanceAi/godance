package ai.godance.security;

import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ai.godance.config.RateLimitProperties;
import reactor.core.publisher.Mono;

/**
 * Rate-limit policy for the auth endpoints in
 * {@link ai.godance.web.AuthController}. Each check runs before any
 * password hashing, database lookup or mail send. Login and forgot-password
 * are also limited per email whether or not the address is registered, so
 * a limit never reveals that an account exists.
 */
@Component
public class AuthRateLimits {

    private final RateLimiter limiter;
    private final RateLimitProperties props;

    public AuthRateLimits(RateLimiter limiter, RateLimitProperties props) {
        this.limiter = limiter;
        this.props = props;
    }

    /**
     * Per client IP, then per normalized email. A request refused per IP
     * is not counted against the email.
     */
    public Mono<Void> login(String clientIp, String email) {
        return limiter.check("login-ip", clientIp, props.loginPerIp())
                .then(limiter.check("login-email", normalize(email), props.loginPerEmail()));
    }

    /** Per client IP: each signup creates an account and sends a verification email. */
    public Mono<Void> signup(String clientIp) {
        return limiter.check("signup-ip", clientIp, props.signupPerIp());
    }

    public Mono<Void> verify(String clientIp) {
        return limiter.check("verify-ip", clientIp, props.verifyPerIp());
    }

    /**
     * Per client IP, then per normalized email, counted whether or not the
     * address is registered. Never fails: emits {@code false} once a limit
     * is reached, so the forgot-password endpoint skips the email and still
     * answers 202, the same as for every other request.
     */
    public Mono<Boolean> forgotPassword(String clientIp, String email) {
        return allowed(limiter.check("forgot-ip", clientIp, props.forgotPerIp())
                .then(limiter.check("forgot-email", normalize(email), props.forgotPerEmail())));
    }

    /** Per client IP, then per signed-in account. Fails with a 429 over either limit. */
    public Mono<Void> resendVerification(String clientIp, UUID userId) {
        return limiter.check("resend-ip", clientIp, props.resendPerIp())
                .then(limiter.check("resend-account", userId == null ? null : userId.toString(),
                        props.resendPerAccount()));
    }

    /**
     * Per client IP, then per normalized email, counted whether or not an
     * account holds the address. Never fails, like {@link #forgotPassword}:
     * the release-link endpoint answers 202 either way and just skips the
     * email once a limit is reached.
     */
    public Mono<Boolean> releaseLink(String clientIp, String email) {
        return allowed(limiter.check("release-link-ip", clientIp, props.releaseLinkPerIp())
                .then(limiter.check("release-link-email", normalize(email), props.releaseLinkPerEmail())));
    }

    /** {@code true} if every check passed, {@code false} instead of a 429. */
    private static Mono<Boolean> allowed(Mono<Void> checks) {
        return checks.thenReturn(true).onErrorReturn(RateLimitExceededException.class, false);
    }

    /** Same normalization as {@code AuthService}, so "A@x.io " and "a@x.io" share a counter. */
    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
