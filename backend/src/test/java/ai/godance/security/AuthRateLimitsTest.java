package ai.godance.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import ai.godance.config.RateLimitProperties;
import ai.godance.config.RateLimitProperties.Limit;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.publisher.PublisherProbe;

/**
 * Which buckets each auth endpoint is counted against, in which order,
 * with which subject. Counting itself is {@link RateLimiterTest}'s job.
 */
class AuthRateLimitsTest {

    // Distinct values so a mix-up between limits fails the stubbing.
    private static final Limit LOGIN_PER_IP = new Limit(30, Duration.ofMinutes(15));
    private static final Limit LOGIN_PER_EMAIL = new Limit(10, Duration.ofMinutes(15));
    private static final Limit SIGNUP_PER_IP = new Limit(10, Duration.ofHours(1));
    private static final Limit VERIFY_PER_IP = new Limit(20, Duration.ofMinutes(15));
    private static final String IP = "203.0.113.7";

    RateLimiter limiter;
    AuthRateLimits rateLimits;

    @BeforeEach
    void setUp() {
        limiter = mock(RateLimiter.class);
        rateLimits = new AuthRateLimits(limiter, new RateLimitProperties(
                true, LOGIN_PER_IP, LOGIN_PER_EMAIL, SIGNUP_PER_IP, VERIFY_PER_IP));
    }

    @Test
    void login_givenIpWithinLimit_expectsNormalizedEmailCountedToo() {
        PublisherProbe<Void> ipCheck = PublisherProbe.empty();
        PublisherProbe<Void> emailCheck = PublisherProbe.empty();
        when(limiter.check("login-ip", IP, LOGIN_PER_IP)).thenReturn(ipCheck.mono());
        when(limiter.check("login-email", "alice@example.com", LOGIN_PER_EMAIL)).thenReturn(emailCheck.mono());

        StepVerifier.create(rateLimits.login(IP, "  Alice@Example.COM ")).verifyComplete();

        ipCheck.assertWasSubscribed();
        emailCheck.assertWasSubscribed();
    }

    @Test
    void login_givenIpOverLimit_expects429AndEmailNotCounted() {
        PublisherProbe<Void> emailCheck = PublisherProbe.empty();
        when(limiter.check("login-ip", IP, LOGIN_PER_IP))
                .thenReturn(Mono.error(new RateLimitExceededException(Duration.ofMinutes(5))));
        when(limiter.check(eq("login-email"), any(), any())).thenReturn(emailCheck.mono());

        StepVerifier.create(rateLimits.login(IP, "alice@example.com"))
                .verifyError(RateLimitExceededException.class);

        emailCheck.assertWasNotSubscribed();
    }

    @Test
    void login_givenEmailOverLimit_expects429() {
        when(limiter.check("login-ip", IP, LOGIN_PER_IP)).thenReturn(Mono.empty());
        when(limiter.check("login-email", "alice@example.com", LOGIN_PER_EMAIL))
                .thenReturn(Mono.error(new RateLimitExceededException(Duration.ofMinutes(5))));

        StepVerifier.create(rateLimits.login(IP, "alice@example.com"))
                .verifyError(RateLimitExceededException.class);
    }

    @Test
    void signup_givenClientIp_expectsCountedPerIp() {
        PublisherProbe<Void> check = PublisherProbe.empty();
        when(limiter.check("signup-ip", IP, SIGNUP_PER_IP)).thenReturn(check.mono());

        StepVerifier.create(rateLimits.signup(IP)).verifyComplete();

        check.assertWasSubscribed();
    }

    @Test
    void verify_givenClientIp_expectsCountedPerIp() {
        PublisherProbe<Void> check = PublisherProbe.empty();
        when(limiter.check("verify-ip", IP, VERIFY_PER_IP)).thenReturn(check.mono());

        StepVerifier.create(rateLimits.verify(IP)).verifyComplete();

        check.assertWasSubscribed();
    }
}
