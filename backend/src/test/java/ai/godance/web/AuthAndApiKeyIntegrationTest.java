package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.UserRepository;
import ai.godance.web.dto.ApiKeyDtos.ApiKeySummary;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.MeResponse;

/**
 * End-to-end auth + API-key surface. One Testcontainers Postgres shared
 * across all test methods. Each test mints a unique email so the methods
 * are independent and order-insensitive.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AuthAndApiKeyIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;

    private static String uniqueEmail(String tag) {
        return tag + "-" + UUID.randomUUID() + "@godance.test";
    }

    private static Map<String, Object> signupBody(String email) {
        return Map.of(
                "email", email,
                "password", "correct-horse-battery-staple",
                "fullName", "Test User");
    }

    private AuthResponse signup(String email) {
        return http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(signupBody(email))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
    }

    // ---------- /v1/auth/signup ---------------------------------------

    @Test
    void signup_givenValidPayload_returns201WithBearerToken() {
        AuthResponse out = signup(uniqueEmail("signup-ok"));
        assertThat(out.tokenType()).isEqualTo("Bearer");
        assertThat(out.accessToken()).isNotBlank();
        assertThat(out.expiresAt()).isNotNull();
    }

    @Test
    void signup_givenDuplicateEmail_returns409() {
        String email = uniqueEmail("signup-dup");
        signup(email);
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(signupBody(email))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void signup_givenInvalidEmail_returns400() {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", "not-an-email",
                        "password", "long-enough-password-here",
                        "fullName", "Test"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void signup_givenShortPassword_returns400() {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", uniqueEmail("short-pw"),
                        "password", "short",
                        "fullName", "Test"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ---------- /v1/auth/login ----------------------------------------

    @Test
    void login_givenCorrectCredentials_returns200WithToken() {
        String email = uniqueEmail("login-ok");
        signup(email);

        AuthResponse out = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        assertThat(out.accessToken()).isNotBlank();
    }

    @Test
    void login_givenWrongPassword_returns401() {
        String email = uniqueEmail("login-bad-pw");
        signup(email);

        http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "wrong-password-here"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void login_givenUnknownUser_returns401() {
        http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", uniqueEmail("nobody"), "password", "anything-long-enough"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------- /v1/auth/me -------------------------------------------

    @Test
    void me_givenValidBearerToken_returnsUserProfile() {
        String email = uniqueEmail("me-ok");
        AuthResponse signed = signup(email);

        MeResponse me = http.get().uri("/v1/auth/me")
                .header("Authorization", "Bearer " + signed.accessToken())
                .exchange()
                .expectStatus().isOk()
                .expectBody(MeResponse.class).returnResult().getResponseBody();

        assertThat(me.email()).isEqualTo(email);
        assertThat(me.emailVerified()).isFalse();
        assertThat(me.creditBalanceCents()).isZero();
    }

    @Test
    void me_givenNoToken_returns401() {
        http.get().uri("/v1/auth/me")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void me_givenGarbageToken_returns401() {
        http.get().uri("/v1/auth/me")
                .header("Authorization", "Bearer not.a.valid.jwt")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ---------- /v1/auth/verify ---------------------------------------

    @Test
    void verify_givenValidTokenInOwnSession_flipsEmailVerifiedFlag() {
        String email = uniqueEmail("verify-ok");
        String bearer = "Bearer " + signup(email).accessToken();
        String token = users.findByEmail(email).orElseThrow().getEmailVerificationToken();
        assertThat(token).isNotNull();

        http.get().uri("/v1/auth/verify?token={t}", token)
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isNoContent();

        var refreshed = users.findByEmail(email).orElseThrow();
        assertThat(refreshed.isEmailVerified()).isTrue();
        assertThat(refreshed.getEmailVerificationToken()).isNull();
    }

    @Test
    void verify_givenValidTokenWithoutSession_returns401AndStaysUnverified() {
        String email = uniqueEmail("verify-anon");
        signup(email);
        String token = users.findByEmail(email).orElseThrow().getEmailVerificationToken();

        http.get().uri("/v1/auth/verify?token={t}", token)
                .exchange()
                .expectStatus().isUnauthorized();

        var refreshed = users.findByEmail(email).orElseThrow();
        assertThat(refreshed.isEmailVerified()).isFalse();
        assertThat(refreshed.getEmailVerificationToken()).isEqualTo(token);
    }

    @Test
    void verify_givenAnotherAccountsToken_returns403AndStaysUnverified() {
        // Someone registered the victim's address; the victim, signed in to
        // their own account, opens the mail meant for that registration.
        String squattedEmail = uniqueEmail("verify-squatted");
        signup(squattedEmail);
        String token = users.findByEmail(squattedEmail).orElseThrow().getEmailVerificationToken();
        String otherBearer = "Bearer " + signup(uniqueEmail("verify-other")).accessToken();

        http.get().uri("/v1/auth/verify?token={t}", token)
                .header("Authorization", otherBearer)
                .exchange()
                .expectStatus().isForbidden();

        var squatted = users.findByEmail(squattedEmail).orElseThrow();
        assertThat(squatted.isEmailVerified()).isFalse();
        assertThat(squatted.getEmailVerificationToken()).isEqualTo(token);
    }

    @Test
    void resendVerification_givenNoSession_returns401() {
        http.post().uri("/v1/auth/verify/resend")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void resendVerification_givenSignupEmailJustSent_returns429() {
        String bearer = "Bearer " + signup(uniqueEmail("resend-soon")).accessToken();

        http.post().uri("/v1/auth/verify/resend")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS);
    }

    @Test
    void resendVerification_givenCooldownPassed_returns204AndKeepsToken() {
        String email = uniqueEmail("resend-ok");
        String bearer = "Bearer " + signup(email).accessToken();
        var user = users.findByEmail(email).orElseThrow();
        String token = user.getEmailVerificationToken();
        Instant longAgo = Instant.now().minus(Duration.ofMinutes(5));
        user.setEmailVerificationSentAt(longAgo);
        users.save(user);

        http.post().uri("/v1/auth/verify/resend")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isNoContent();

        var refreshed = users.findByEmail(email).orElseThrow();
        assertThat(refreshed.getEmailVerificationToken()).isEqualTo(token);
        assertThat(refreshed.getEmailVerificationSentAt()).isAfter(longAgo);
    }

    @Test
    void verify_givenBadToken_returns400() {
        String bearer = "Bearer " + signup(uniqueEmail("verify-bad")).accessToken();

        http.get().uri("/v1/auth/verify?token=this-token-does-not-exist")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isBadRequest();
    }

    // ---------- /v1/keys ----------------------------------------------

    @Test
    void apiKeys_fullLifecycle_create_list_delete() {
        String email = uniqueEmail("keys-lifecycle");
        String bearer = "Bearer " + signup(email).accessToken();

        // Create
        CreateApiKeyResponse created = http.post().uri("/v1/keys")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "name", "default",
                        "scopes", List.of("chat.completions", "images.generations")))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();

        assertThat(created.plaintext()).startsWith("gd_live_");
        assertThat(created.prefix()).hasSize(8);
        assertThat(created.id()).isNotNull();

        // List
        List<ApiKeySummary> listed = http.get().uri("/v1/keys")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ApiKeySummary.class).returnResult().getResponseBody();

        assertThat(listed).singleElement()
                .satisfies(k -> {
                    assertThat(k.id()).isEqualTo(created.id());
                    assertThat(k.name()).isEqualTo("default");
                    assertThat(k.prefix()).isEqualTo(created.prefix());
                    assertThat(k.scopes()).containsExactly("chat.completions", "images.generations");
                });

        // Delete
        http.delete().uri("/v1/keys/{id}", created.id())
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isNoContent();

        // List again — empty
        http.get().uri("/v1/keys")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ApiKeySummary.class).hasSize(0);
    }

    @Test
    void apiKeys_create_withoutAuth_returns401() {
        http.post().uri("/v1/keys")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "x"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void apiKeys_deleteAnotherUsersKey_returns404_doesNotLeakExistence() {
        // user A creates a key
        String emailA = uniqueEmail("keys-owner");
        String bearerA = "Bearer " + signup(emailA).accessToken();
        CreateApiKeyResponse aKey = http.post().uri("/v1/keys")
                .header("Authorization", bearerA)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "a-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();

        // user B tries to delete it
        String bearerB = "Bearer " + signup(uniqueEmail("keys-thief")).accessToken();
        http.delete().uri("/v1/keys/{id}", aKey.id())
                .header("Authorization", bearerB)
                .exchange()
                .expectStatus().isNotFound();

        // A's key still exists
        http.get().uri("/v1/keys")
                .header("Authorization", bearerA)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(ApiKeySummary.class).hasSize(1);
    }
}
