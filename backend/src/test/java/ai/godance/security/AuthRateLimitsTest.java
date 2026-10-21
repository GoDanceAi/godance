package ai.godance.security;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;

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
    private static final Limit FORGOT_PER_IP = new Limit(11, Duration.ofHours(1));
    private static final Limit FORGOT_PER_EMAIL = new Limit(5, Duration.ofDays(1));
    private static final Limit RESEND_PER_IP = new Limit(12, Duration.ofHours(1));
    private static final Limit RESEND_PER_ACCOUNT = new Limit(6, Duration.ofDays(1));
    private static final Limit RELEASE_LINK_PER_IP = new Limit(13, Duration.ofHours(1));
    private static final Limit RELEASE_LINK_PER_EMAIL = new Limit(7, Duration.ofDays(1));
    private static final String IP = "203.0.113.7";
    private static final UUID USER_ID = UUID.fromString("5b0c8a4e-2f6d-4c1a-9e3b-7d2f1a6c8e90");

    RateLimiter limiter;
    AuthRateLimits rateLimits;

    @BeforeEach
    void setUp() {
        limiter = mock(RateLimiter.class);
        rateLimits = new AuthRateLimits(limiter, new RateLimitProperties(
                true, LOGIN_PER_IP, LOGIN_PER_EMAIL, SIGNUP_PER_IP, VERIFY_PER_IP,
                FORGOT_PER_IP, FORGOT_PER_EMAIL, RESEND_PER_IP, RESEND_PER_ACCOUNT,
                RELEASE_LINK_PER_IP, RELEASE_LINK_PER_EMAIL));
    }

    private static Mono<Void> over() {
        return Mono.error(new RateLimitExceededException(Duration.ofMinutes(5)));
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

    // ---------- forgot password: never a 429 -------------------------

    @Test
    void forgotPassword_givenWithinLimits_expectsTrueAndNormalizedEmailCounted() {
        PublisherProbe<Void> ipCheck = PublisherProbe.empty();
        PublisherProbe<Void> emailCheck = PublisherProbe.empty();
        when(limiter.check("forgot-ip", IP, FORGOT_PER_IP)).thenReturn(ipCheck.mono());
        when(limiter.check("forgot-email", "alice@example.com", FORGOT_PER_EMAIL)).thenReturn(emailCheck.mono());

        StepVerifier.create(rateLimits.forgotPassword(IP, " Alice@Example.com")).expectNext(true).verifyComplete();

        ipCheck.assertWasSubscribed();
        emailCheck.assertWasSubscribed();
    }

    @Test
    void forgotPassword_givenIpOverLimit_expectsFalseAndEmailNotCounted() {
        PublisherProbe<Void> emailCheck = PublisherProbe.empty();
        when(limiter.check("forgot-ip", IP, FORGOT_PER_IP)).thenReturn(over());
        when(limiter.check(eq("forgot-email"), any(), any())).thenReturn(emailCheck.mono());

        StepVerifier.create(rateLimits.forgotPassword(IP, "alice@example.com")).expectNext(false).verifyComplete();

        emailCheck.assertWasNotSubscribed();
    }

    @Test
    void forgotPassword_givenEmailOverLimit_expectsFalse() {
        when(limiter.check("forgot-ip", IP, FORGOT_PER_IP)).thenReturn(Mono.empty());
        when(limiter.check("forgot-email", "alice@example.com", FORGOT_PER_EMAIL)).thenReturn(over());

        StepVerifier.create(rateLimits.forgotPassword(IP, "alice@example.com")).expectNext(false).verifyComplete();
    }

    // ---------- resend verification ----------------------------------

    @Test
    void resendVerification_givenWithinLimits_expectsCountedPerIpAndPerAccount() {
        PublisherProbe<Void> ipCheck = PublisherProbe.empty();
        PublisherProbe<Void> accountCheck = PublisherProbe.empty();
        when(limiter.check("resend-ip", IP, RESEND_PER_IP)).thenReturn(ipCheck.mono());
        when(limiter.check("resend-account", USER_ID.toString(), RESEND_PER_ACCOUNT))
                .thenReturn(accountCheck.mono());

        StepVerifier.create(rateLimits.resendVerification(IP, USER_ID)).verifyComplete();

        ipCheck.assertWasSubscribed();
        accountCheck.assertWasSubscribed();
    }

    @Test
    void resendVerification_givenIpOverLimit_expects429AndAccountNotCounted() {
        PublisherProbe<Void> accountCheck = PublisherProbe.empty();
        when(limiter.check("resend-ip", IP, RESEND_PER_IP)).thenReturn(over());
        when(limiter.check(eq("resend-account"), any(), any())).thenReturn(accountCheck.mono());

        StepVerifier.create(rateLimits.resendVerification(IP, USER_ID))
                .verifyError(RateLimitExceededException.class);

        accountCheck.assertWasNotSubscribed();
    }

    @Test
    void resendVerification_givenAccountOverLimit_expects429() {
        when(limiter.check("resend-ip", IP, RESEND_PER_IP)).thenReturn(Mono.empty());
        when(limiter.check("resend-account", USER_ID.toString(), RESEND_PER_ACCOUNT)).thenReturn(over());

        StepVerifier.create(rateLimits.resendVerification(IP, USER_ID))
                .verifyError(RateLimitExceededException.class);
    }

    // ---------- release link: never a 429 ----------------------------

    @Test
    void releaseLink_givenWithinLimits_expectsTrueAndNormalizedEmailCounted() {
        PublisherProbe<Void> ipCheck = PublisherProbe.empty();
        PublisherProbe<Void> emailCheck = PublisherProbe.empty();
        when(limiter.check("release-link-ip", IP, RELEASE_LINK_PER_IP)).thenReturn(ipCheck.mono());
        when(limiter.check("release-link-email", "alice@example.com", RELEASE_LINK_PER_EMAIL))
                .thenReturn(emailCheck.mono());

        StepVerifier.create(rateLimits.releaseLink(IP, " Alice@Example.com")).expectNext(true).verifyComplete();

        ipCheck.assertWasSubscribed();
        emailCheck.assertWasSubscribed();
    }

    @Test
    void releaseLink_givenIpOverLimit_expectsFalseAndEmailNotCounted() {
        PublisherProbe<Void> emailCheck = PublisherProbe.empty();
        when(limiter.check("release-link-ip", IP, RELEASE_LINK_PER_IP)).thenReturn(over());
        when(limiter.check(eq("release-link-email"), any(), any())).thenReturn(emailCheck.mono());

        StepVerifier.create(rateLimits.releaseLink(IP, "alice@example.com")).expectNext(false).verifyComplete();

        emailCheck.assertWasNotSubscribed();
    }

    @Test
    void releaseLink_givenEmailOverLimit_expectsFalse() {
        when(limiter.check("release-link-ip", IP, RELEASE_LINK_PER_IP)).thenReturn(Mono.empty());
        when(limiter.check("release-link-email", "alice@example.com", RELEASE_LINK_PER_EMAIL)).thenReturn(over());

        StepVerifier.create(rateLimits.releaseLink(IP, "alice@example.com")).expectNext(false).verifyComplete();
    }
}
