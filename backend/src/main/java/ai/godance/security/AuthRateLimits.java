package ai.godance.security;

import java.util.Locale;

import org.springframework.stereotype.Component;

import ai.godance.config.RateLimitProperties;
import reactor.core.publisher.Mono;

/**
 * Rate-limit policy for the pre-auth endpoints in
 * {@link ai.godance.web.AuthController}. Each check runs before any
 * password hashing, database lookup or mail send. Login is also limited
 * per email whether or not the address is registered, so a 429 never
 * reveals that an account exists.
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

    /** Same normalization as {@code AuthService}, so "A@x.io " and "a@x.io" share a counter. */
    private static String normalize(String email) {
        return email == null ? null : email.trim().toLowerCase(Locale.ROOT);
    }
}
