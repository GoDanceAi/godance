package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.UserRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * The first-touch route is limited per account with the shared limiter.
 * Limits are off in the test profile; this class turns them on, small,
 * against the shared Redis. Each test uses a new account, so no counter
 * carries over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.rate-limit.enabled=true",
        "godance.attribution.first-touch-limit-per-account=2"})
@AutoConfigureWebTestClient
@Testcontainers
class FirstTouchRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    @Test
    void firstTouch_givenAnAccountPastItsLimit_expects429AndAnotherAccountUnaffected() {
        String first = signup();
        String second = signup();

        post(first, "one").expectStatus().isNoContent();
        post(first, "two").expectStatus().isNoContent();
        post(first, "three")
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().contentType("application/problem+json");

        post(second, "one").expectStatus().isNoContent();
    }

    @Test
    void firstTouch_givenAMalformedBodyPastTheLimit_expects429BeforeItIsRead() {
        String bearer = signup();
        post(bearer, "one").expectStatus().isNoContent();
        post(bearer, "two").expectStatus().isNoContent();

        http.post().uri("/v1/attribution/first-touch")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isEqualTo(429);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users WHERE first_touch IS NOT NULL", Integer.class))
                .isPositive();
    }

    private String signup() {
        String email = "touch-rl-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "First Touch"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(users.findByEmail(email)).isPresent();
        return "Bearer " + resp.accessToken();
    }

    private WebTestClient.ResponseSpec post(String bearer, String source) {
        return http.post().uri("/v1/attribution/first-touch")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("utm_source", source))
                .exchange();
    }
}
