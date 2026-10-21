package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.Test;
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

import ai.godance.repository.UserRepository;

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
        "godance.rate-limit.verify-per-ip.requests=2"})
@AutoConfigureWebTestClient
@Testcontainers
class AuthRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String PASSWORD = "correct-horse-battery-staple";

    @Autowired WebTestClient http;
    @Autowired UserRepository users;

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    private static String randomClientIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    private ResponseSpec signup(String clientIp, String email) {
        return http.post().uri("/v1/auth/signup")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", "Test User"))
                .exchange();
    }

    private ResponseSpec login(String clientIp, String email, String password) {
        return http.post().uri("/v1/auth/login")
                .header("X-Forwarded-For", clientIp)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", password))
                .exchange();
    }

    private ResponseSpec verify(String clientIp, String token) {
        return http.get().uri("/v1/auth/verify?token={t}", token)
                .header("X-Forwarded-For", clientIp)
                .exchange();
    }

    private static void expectTooManyRequests(ResponseSpec response) {
        response.expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectHeader().value(HttpHeaders.RETRY_AFTER,
                        v -> assertThat(Long.parseLong(v)).isBetween(1L, 3600L))
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
        verify(ip, "no-such-token").expectStatus().isBadRequest();
        verify(ip, "no-such-token").expectStatus().isBadRequest();

        expectTooManyRequests(verify(ip, "no-such-token"));
    }
}
