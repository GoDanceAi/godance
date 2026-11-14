package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.UserRepository;
import ai.godance.service.AccountClosureService;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * {@code POST /v1/attribution/first-touch} (round 10 brief 01): what brought
 * a sign-up here is written once, inside the window, by a signed-in session
 * only, and always answers 204 for a well-formed body.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class FirstTouchIntegrationTest {

    private static final String SLUG = "paper-lantern-street-at-dusk";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired AccountClosureService closure;

    // ===== Written once ===============================================

    @Test
    void firstTouch_givenANewAccount_expects204AndEveryFieldKept() {
        Signed u = signup();

        post(u.bearer(), full()).expectStatus().isNoContent().expectBody().isEmpty();

        assertThat(storedTouch(u.id())).containsExactlyInAnyOrderEntriesOf(Map.of(
                "utm_source", "x",
                "utm_medium", "social",
                "utm_campaign", "launch",
                "gallery_recipe", SLUG,
                "landing_path", "/create/image",
                "referrer_host", "x.com"));
        assertThat(storedAt(u.id())).isCloseTo(Instant.now(), within5s());
    }

    @Test
    void firstTouch_givenASecondCall_expects204AndNothingChanged() {
        Signed u = signup();
        post(u.bearer(), Map.of("utm_source", "first")).expectStatus().isNoContent();
        Instant writtenAt = storedAt(u.id());

        post(u.bearer(), Map.of("utm_source", "second", "gallery_recipe", SLUG)).expectStatus().isNoContent();

        assertThat(storedTouch(u.id())).containsExactlyEntriesOf(Map.of("utm_source", "first"));
        assertThat(storedAt(u.id())).isEqualTo(writtenAt);
    }

    @Test
    void firstTouch_givenAnAccountOlderThanTheWindow_expects204AndNothingWritten() {
        Signed u = signup();
        ageAccount(u.id(), 169);   // the window is 168 hours

        post(u.bearer(), full()).expectStatus().isNoContent();

        assertThat(storedTouch(u.id())).isNull();
        assertThat(storedAt(u.id())).isNull();
    }

    @Test
    void firstTouch_givenAnAccountStillInsideTheWindow_expectsItWritten() {
        Signed u = signup();
        ageAccount(u.id(), 167);

        post(u.bearer(), Map.of("utm_source", "newsletter")).expectStatus().isNoContent();

        assertThat(storedTouch(u.id())).containsExactlyEntriesOf(Map.of("utm_source", "newsletter"));
    }

    // ===== Who may call ===============================================

    @Test
    void firstTouch_givenAnApiKey_expects403AndNothingWritten() {
        Signed u = signup();
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "first-touch-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();

        post("Bearer " + key.plaintext(), full()).expectStatus().isForbidden();

        assertThat(storedTouch(u.id())).isNull();
    }

    @Test
    void firstTouch_givenNoSession_expects401() {
        http.post().uri("/v1/attribution/first-touch")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(full())
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ===== What it takes ==============================================

    @Test
    void firstTouch_givenOneField_expectsItKeptAlone() {
        Signed u = signup();

        post(u.bearer(), Map.of("landing_path", "/signup")).expectStatus().isNoContent();

        assertThat(storedTouch(u.id())).containsExactlyEntriesOf(Map.of("landing_path", "/signup"));
    }

    @Test
    void firstTouch_givenSurroundingSpacesBlankAndUnknownFields_expectsOnlyTheRealOnesTrimmed() {
        Signed u = signup();
        Map<String, Object> body = new HashMap<>();
        body.put("utm_source", "  newsletter  ");
        body.put("utm_medium", "   ");
        body.put("utm_campaign", null);
        body.put("utm_term", "running shoes");          // not a field the contract has
        body.put("user_id", UUID.randomUUID().toString());

        post(u.bearer(), body).expectStatus().isNoContent();

        assertThat(storedTouch(u.id())).containsExactlyEntriesOf(Map.of("utm_source", "newsletter"));
    }

    @Test
    void firstTouch_givenAFieldOfExactly100Characters_expectsItKept() {
        Signed u = signup();
        String hundred = "é".repeat(100);

        post(u.bearer(), Map.of("utm_campaign", hundred)).expectStatus().isNoContent();

        assertThat(storedTouch(u.id())).containsExactlyEntriesOf(Map.of("utm_campaign", hundred));
    }

    @Test
    void firstTouch_givenNoFieldAtAll_expects400AndNothingWritten() {
        Signed u = signup();

        post(u.bearer(), Map.of())
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("invalid_first_touch");
        post(u.bearer(), Map.of("utm_source", "", "landing_path", "  "))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_first_touch");

        assertThat(storedTouch(u.id())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"utm_source", "utm_medium", "utm_campaign", "landing_path", "referrer_host"})
    void firstTouch_givenAFieldOf101Characters_expects400AndNothingWritten(String field) {
        Signed u = signup();

        post(u.bearer(), Map.of(field, "a".repeat(101), "utm_source".equals(field) ? "utm_medium" : "utm_source", "ok"))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_first_touch");

        assertThat(storedTouch(u.id())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "line\nbreak",          // a control character
            "tab\there",
            "nul\u0000byte",
            "over‮ride",       // a bidirectional override
            "zero​width",      // a format character
            "sep arator"       // a line separator
    })
    void firstTouch_givenAnUnprintableCharacter_expects400AndNothingWritten(String value) {
        Signed u = signup();

        post(u.bearer(), Map.of("utm_source", value))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_first_touch");

        assertThat(storedTouch(u.id())).isNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"Paper-Lantern", "paper lantern", "../admin",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"})
    void firstTouch_givenAGalleryRecipeThatIsNotASlug_expects400AndNothingWritten(String slug) {
        Signed u = signup();

        post(u.bearer(), Map.of("utm_source", "x", "gallery_recipe", slug))
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.code").isEqualTo("invalid_gallery_recipe");

        assertThat(storedTouch(u.id())).isNull();
    }

    // ===== Closing the account ========================================

    @Test
    void closeAccount_givenAFirstTouch_expectsItCleared() {
        Signed u = signup();
        post(u.bearer(), full()).expectStatus().isNoContent();
        assertThat(storedTouch(u.id())).isNotNull();

        closure.close(u.id(), null, "203.0.113.9");

        assertThat(storedTouch(u.id())).isNull();
        assertThat(storedAt(u.id())).isNull();
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signup() {
        String email = "touch-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "First Touch"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return new Signed(users.findByEmail(email).orElseThrow().getId(), "Bearer " + resp.accessToken());
    }

    private static Map<String, Object> full() {
        return Map.of(
                "utm_source", "x",
                "utm_medium", "social",
                "utm_campaign", "launch",
                "gallery_recipe", SLUG,
                "landing_path", "/create/image",
                "referrer_host", "x.com");
    }

    private WebTestClient.ResponseSpec post(String bearer, Map<String, ?> body) {
        return http.post().uri("/v1/attribution/first-touch")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** Moves the account's creation back, as if it had signed up {@code hours} ago. */
    private void ageAccount(UUID userId, int hours) {
        assertThat(jdbc.update("UPDATE users SET created_at = ? WHERE id = ?",
                java.sql.Timestamp.from(Instant.now().minus(hours, ChronoUnit.HOURS)), userId)).isEqualTo(1);
    }

    private Map<String, String> storedTouch(UUID userId) {
        String raw = jdbc.queryForObject("SELECT first_touch::text FROM users WHERE id = ?", String.class, userId);
        return raw == null ? null : json.readValue(raw, new TypeReference<Map<String, String>>() { });
    }

    private Instant storedAt(UUID userId) {
        java.sql.Timestamp at = jdbc.queryForObject("SELECT first_touch_at FROM users WHERE id = ?",
                java.sql.Timestamp.class, userId);
        return at == null ? null : at.toInstant();
    }

    private static org.assertj.core.data.TemporalUnitOffset within5s() {
        return org.assertj.core.api.Assertions.within(5, ChronoUnit.SECONDS);
    }
}
