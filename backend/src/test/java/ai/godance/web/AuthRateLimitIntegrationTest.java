package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.security.JwtService;
import ai.godance.service.mail.LoggingEmailSender;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

/**
 * Auth rate limits end to end, against the same Redis every integration
 * test uses (localhost:6380). Limits are off in the test profile; this
 * class turns them on, small. The client IP comes from
 * {@code X-Forwarded-For} under the framework forward-headers strategy,
 * exactly as behind Caddy in prod. Every test uses fresh random IPs and
 * unique emails, so counters never carry over between tests or runs.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.forward-headers-strategy=framework",
        "godance.rate-limit.enabled=true",
        "godance.rate-limit.login-per-ip.requests=6",
        "godance.rate-limit.login-per-email.requests=3",
        "godance.rate-limit.signup-per-ip.requests=2",
        "godance.rate-limit.verify-per-ip.requests=2",
        "godance.rate-limit.forgot-per-ip.requests=4",
        "godance.rate-limit.forgot-per-email.requests=2",
        "godance.rate-limit.resend-per-ip.requests=4",
        "godance.rate-limit.resend-per-account.requests=2",
        "godance.rate-limit.release-link-per-ip.requests=4",
        "godance.rate-limit.release-link-per-email.requests=2",
        "godance.rate-limit.two-factor-per-ip.requests=4",
        "godance.rate-limit.two-factor-per-account.requests=2"})
@AutoConfigureWebTestClient
@Testcontainers
class AuthRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired JwtService jwt;

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    private static String randomClientIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    /** A challenge for a new account with 2FA on, as a password sign-in would get. */
    private String twoFactorChallenge(String email) {
        signup(randomClientIp(), email).expectStatus().isCreated();
        User u = users.findByEmail(email).orElseThrow();
        TwoFactorFixture.turnOn(twoFactors, u.getId());
        return "Bearer " + jwt.issueTwoFactorChallenge(u).token();
    }

    private ResponseSpec twoFactorVerify(String clientIp, String challenge) {
        return http.post().uri("/v1/auth/2fa/verify")
                .header("X-Forwarded-For", clientIp)
                .header("Authorization", challenge)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", "AAAA-BBBB-CCCC-DDDD"))
                .exchange();
    }

    private ResponseSpec signup(String clientIp, String email) {
        return http.post().uri("/v1/auth/signup")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", "Test User"))
                .exchange();
    }

    private String signupBearer(String clientIp, String email) {
        AuthResponse out = signup(clientIp, email).expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + out.accessToken();
    }

    private ResponseSpec login(String clientIp, String email, String password) {
        return http.post().uri("/v1/auth/login")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange();
    }

    private ResponseSpec verify(String clientIp, String bearer, String token) {
        return http.get().uri("/v1/auth/verify?token={t}", token)
                .header("X-Forwarded-For", clientIp)
                .header("Authorization", bearer)
                .exchange();
    }

    private ResponseSpec resend(String clientIp, String bearer) {
        return http.post().uri("/v1/auth/verify/resend")
                .header("X-Forwarded-For", clientIp)
                .header("Authorization", bearer)
                .exchange();
    }

    private ResponseSpec forgot(String clientIp, String email) {
        return http.post().uri("/v1/auth/password/forgot")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email))
                .exchange();
    }

    private ResponseSpec releaseLink(String clientIp, String email) {
        return http.post().uri("/v1/auth/email/release-link")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email))
                .exchange();
    }

    /** Push the last verification email past AuthService's one-minute resend cooldown. */
    private void backdateVerificationEmail(String email) {
        var user = users.findByEmail(email).orElseThrow();
        user.setEmailVerificationSentAt(Instant.now().minus(Duration.ofMinutes(5)));
        users.save(user);
    }

    /** Push the last reset email past AuthService's one-minute resend cooldown. */
    private void backdateResetEmail(String email) {
        var user = users.findByEmail(email).orElseThrow();
        user.setPasswordResetSentAt(Instant.now().minus(Duration.ofMinutes(5)));
        users.save(user);
    }

    /** Reset emails the [email-stub] sender logs for {@code email} while {@code requests} run. */
    private static long resetEmailsSent(String email, Runnable requests) {
        Logger stubLog = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        stubLog.addAppender(appender);
        try {
            requests.run();
        } finally {
            stubLog.detachAppender(appender);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                // The INFO stub line only; the DEBUG line repeats it inside the full body.
                .filter(m -> m.startsWith("[email-stub] Password reset URL for " + email + ":"))
                .count();
    }

    /** Verification emails the [email-stub] sender logs for {@code email} while {@code requests} run. */
    private static long verificationEmailsSent(String email, Runnable requests) {
        Logger stubLog = (Logger) LoggerFactory.getLogger(LoggingEmailSender.class);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        stubLog.addAppender(appender);
        try {
            requests.run();
        } finally {
            stubLog.detachAppender(appender);
        }
        return appender.list.stream()
                .map(ILoggingEvent::getFormattedMessage)
                // The INFO stub line only; the DEBUG line repeats it inside the full body.
                .filter(m -> m.startsWith("[email-stub] Verification URL for " + email + ":"))
                .count();
    }

    private static void expectTooManyRequests(ResponseSpec response) {
        response.expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value(HttpHeaders.RETRY_AFTER,
                        v -> assertThat(Long.parseLong(v)).isBetween(1L, 86_400L))
                .expectBody()
                .jsonPath("$.status").isEqualTo(429)
                .jsonPath("$.detail").value(String.class,
                        d -> assertThat(d).startsWith("too many requests, try again in"));
    }

    // ---------- /v1/auth/login ----------------------------------------

    @Test
    void login_givenTooManyAttemptsOnOneEmail_expects429FromAnyIp() {
        String email = uniqueEmail("rl-login-email");
        signup(randomClientIp(), email).expectStatus().isCreated();
        for (int i = 0; i < 3; i++) {
            login(randomClientIp(), email, "wrong-password-here").expectStatus().isUnauthorized();
        }

        // Fourth attempt: fresh IP, email in another case, right password.
        // Refused before the password is even checked.
        String ip = randomClientIp();
        expectTooManyRequests(login(ip, email.toUpperCase(Locale.ROOT), PASSWORD));

        // The limit is per email: another account from that IP is unaffected.
        login(ip, uniqueEmail("rl-login-other"), "anything-long-enough").expectStatus().isUnauthorized();
    }

    @Test
    void login_givenTooManyAttemptsFromOneIp_expects429ForAnyEmail() {
        String ip = randomClientIp();
        for (int i = 0; i < 6; i++) {
            login(ip, uniqueEmail("rl-login-spray"), "anything-long-enough").expectStatus().isUnauthorized();
        }

        String fresh = uniqueEmail("rl-login-fresh");
        expectTooManyRequests(login(ip, fresh, "anything-long-enough"));

        // Other clients are unaffected.
        login(randomClientIp(), fresh, "anything-long-enough").expectStatus().isUnauthorized();
    }

    @Test
    void login_givenAttemptsUnderLimit_expectsNormalLogin() {
        String ip = randomClientIp();
        String email = uniqueEmail("rl-login-ok");
        signup(ip, email).expectStatus().isCreated();
        login(ip, email, "wrong-password-here").expectStatus().isUnauthorized();

        login(ip, email, PASSWORD).expectStatus().isOk();
    }

    // ---------- /v1/auth/signup ---------------------------------------

    @Test
    void signup_givenTooManyFromOneIp_expects429AndNoAccountCreated() {
        String ip = randomClientIp();
        signup(ip, uniqueEmail("rl-signup-1")).expectStatus().isCreated();
        signup(ip, uniqueEmail("rl-signup-2")).expectStatus().isCreated();

        String third = uniqueEmail("rl-signup-3");
        expectTooManyRequests(signup(ip, third));
        assertThat(users.findByEmail(third)).isEmpty();
    }

    // ---------- /v1/auth/verify ---------------------------------------

    @Test
    void verify_givenTooManyFromOneIp_expects429() {
        String ip = randomClientIp();
        String bearer = signupBearer(randomClientIp(), uniqueEmail("rl-verify"));
        verify(ip, bearer, "no-such-token").expectStatus().isBadRequest();
        verify(ip, bearer, "no-such-token").expectStatus().isBadRequest();

        expectTooManyRequests(verify(ip, bearer, "no-such-token"));
    }

    // ---------- /v1/auth/verify/resend --------------------------------

    @Test
    void resendVerification_givenTooManyForOneAccount_expects429() {
        String email = uniqueEmail("rl-resend");
        String bearer = signupBearer(randomClientIp(), email);
        for (int i = 0; i < 2; i++) {
            backdateVerificationEmail(email);
            resend(randomClientIp(), bearer).expectStatus().isNoContent();
        }

        // Past AuthService's cooldown, but over the per-account limit: the
        // limiter's 429 (with Retry-After), not the cooldown's.
        backdateVerificationEmail(email);
        expectTooManyRequests(resend(randomClientIp(), bearer));
    }

    // ---------- /v1/auth/password/forgot ------------------------------

    @Test
    void forgotPassword_givenTooManyForOneEmail_expects202ButNoMoreEmails() {
        String email = uniqueEmail("rl-forgot-email");
        signup(randomClientIp(), email).expectStatus().isCreated();

        long sent = resetEmailsSent(email, () -> {
            forgot(randomClientIp(), email).expectStatus().isAccepted();
            backdateResetEmail(email);
            forgot(randomClientIp(), email.toUpperCase(Locale.ROOT)).expectStatus().isAccepted();
            backdateResetEmail(email);
            // Past AuthService's cooldown, but over the per-email limit.
            forgot(randomClientIp(), email).expectStatus().isAccepted().expectBody().isEmpty();
        });

        assertThat(sent).isEqualTo(2);
    }

    @Test
    void forgotPassword_givenTooManyFromOneIp_expects202ButNoEmail() {
        String ip = randomClientIp();
        String email = uniqueEmail("rl-forgot-ip");
        signup(randomClientIp(), email).expectStatus().isCreated();
        for (int i = 0; i < 4; i++) {
            forgot(ip, uniqueEmail("rl-forgot-spray")).expectStatus().isAccepted();
        }

        assertThat(resetEmailsSent(email, () -> forgot(ip, email).expectStatus().isAccepted())).isZero();

        // Other clients are unaffected, and the refused request did not count against the email.
        assertThat(resetEmailsSent(email, () -> forgot(randomClientIp(), email).expectStatus().isAccepted()))
                .isEqualTo(1);
    }

    // ---------- /v1/auth/email/release-link ---------------------------

    @Test
    void releaseLink_givenTooManyForOneEmail_expects202ButNoMoreEmails() {
        String email = uniqueEmail("rl-release-email");
        signup(randomClientIp(), email).expectStatus().isCreated();

        long sent = verificationEmailsSent(email, () -> {
            backdateVerificationEmail(email);
            releaseLink(randomClientIp(), email).expectStatus().isAccepted();
            backdateVerificationEmail(email);
            releaseLink(randomClientIp(), email.toUpperCase(Locale.ROOT)).expectStatus().isAccepted();
            backdateVerificationEmail(email);
            // Past AuthService's cooldown, but over the per-email limit.
            releaseLink(randomClientIp(), email).expectStatus().isAccepted().expectBody().isEmpty();
        });

        assertThat(sent).isEqualTo(2);
    }

    @Test
    void twoFactorVerify_givenTooManyOnOneAccount_expects429FromAnyIp() {
        String challenge = twoFactorChallenge(uniqueEmail("rl-2fa-account"));
        twoFactorVerify(randomClientIp(), challenge).expectStatus().isForbidden();
        twoFactorVerify(randomClientIp(), challenge).expectStatus().isForbidden();

        twoFactorVerify(randomClientIp(), challenge).expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void twoFactorVerify_givenTooManyFromOneIp_expects429AcrossAccounts() {
        String ip = randomClientIp();
        for (int i = 0; i < 4; i++) {
            twoFactorVerify(ip, twoFactorChallenge(uniqueEmail("rl-2fa-ip"))).expectStatus().isForbidden();
        }

        twoFactorVerify(ip, twoFactorChallenge(uniqueEmail("rl-2fa-ip")))
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void releaseLink_givenTooManyFromOneIp_expects202ButNoEmail() {
        String ip = randomClientIp();
        String email = uniqueEmail("rl-release-ip");
        signup(randomClientIp(), email).expectStatus().isCreated();
        backdateVerificationEmail(email);
        for (int i = 0; i < 4; i++) {
            releaseLink(ip, uniqueEmail("rl-release-spray")).expectStatus().isAccepted();
        }

        assertThat(verificationEmailsSent(email, () -> releaseLink(ip, email).expectStatus().isAccepted()))
                .isZero();

        // Other clients are unaffected, and the refused request did not count against the address.
        assertThat(verificationEmailsSent(email,
                () -> releaseLink(randomClientIp(), email).expectStatus().isAccepted()))
                .isEqualTo(1);
    }
}
