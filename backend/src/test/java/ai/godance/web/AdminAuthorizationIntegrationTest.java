package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Who {@link ai.godance.security.AdminAuthorizationFilter} lets onto
 * {@code /v1/admin/**}: an active account whose current email is verified
 * and allowlisted, over a JWT session. The JWT {@code email} claim alone
 * is never enough — it is unverified at signup and a snapshot afterwards.
 *
 * <p>Each test signs up its own allowlisted address: the tests share one
 * database and an address can be registered only once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AdminAuthorizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String UNVERIFIED_ADMIN = "unverified-admin@godance.test";
    private static final String VERIFIED_ADMIN = "verified-admin@godance.test";
    private static final String RENAMED_ADMIN = "renamed-admin@godance.test";
    private static final String PROMOTED_ADMIN = "promoted-admin@godance.test";
    private static final String DEACTIVATED_ADMIN = "deactivated-admin@godance.test";
    private static final String KEYED_ADMIN = "keyed-admin@godance.test";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> String.join(",", UNVERIFIED_ADMIN, VERIFIED_ADMIN,
                RENAMED_ADMIN, PROMOTED_ADMIN, DEACTIVATED_ADMIN, KEYED_ADMIN));
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerRepository ledger;

    @Test
    void filter_givenUnverifiedAccountWithAllowlistedEmail_returns403() {
        // Anyone can sign up with an allowlisted address its owner hasn't registered yet.
        String bearer = signUp(UNVERIFIED_ADMIN);
        User squatter = users.findByEmail(UNVERIFIED_ADMIN).orElseThrow();
        assertThat(squatter.isEmailVerified()).isFalse();

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        http.post().uri("/v1/admin/users/" + squatter.getId() + "/credit-adjust")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 999_99, "note", "self-serve credit"))
                .exchange()
                .expectStatus().isForbidden();
        assertThat(ledger.sumDeltaForUser(squatter.getId())).isZero();
    }

    @Test
    void filter_givenVerifiedAccountWithAllowlistedEmail_passes() {
        String bearer = signUp(VERIFIED_ADMIN);
        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isForbidden();

        // Verify through the real link flow. The token issued at signup is
        // reused: the filter reads the account, not a claim, so it needs no
        // fresh login to pick up the verification.
        String token = users.findByEmail(VERIFIED_ADMIN).orElseThrow().getEmailVerificationToken();
        http.get().uri(b -> b.path("/v1/auth/verify").queryParam("token", token).build())
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isNoContent();

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(VERIFIED_ADMIN);
    }

    @Test
    void filter_givenEmailClaimAllowlistedButCurrentEmailNot_returns403() {
        String bearer = signUpVerified(RENAMED_ADMIN);
        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk();

        // The account moves off the allowlisted address; the outstanding
        // JWT still carries it in its email claim until it expires.
        User u = users.findByEmail(RENAMED_ADMIN).orElseThrow();
        u.setEmail("renamed-" + UUID.randomUUID() + "@godance.test");
        users.save(u);

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void me_givenEmailClaimStaleAndCurrentEmailAllowlisted_returnsCurrentEmail() {
        String original = "promoted-" + UUID.randomUUID() + "@godance.test";
        String bearer = signUpVerified(original);

        // The account now holds a verified, allowlisted address; the claim
        // still says the old one. Admin handlers see the address the filter
        // checked, never the claim.
        User u = users.findByEmail(original).orElseThrow();
        u.setEmail(PROMOTED_ADMIN);
        users.save(u);

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(PROMOTED_ADMIN);
    }

    @Test
    void filter_givenDeactivatedAccount_returns401() {
        String bearer = signUpVerified(DEACTIVATED_ADMIN);
        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk();

        User u = users.findByEmail(DEACTIVATED_ADMIN).orElseThrow();
        u.setActive(false);
        users.save(u);

        // A disabled account's session ends at the JWT check, before the admin
        // guard runs, so the answer is 401 rather than the guard's 403.
        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void filter_givenApiKeyOfVerifiedAdmin_returns403() {
        String jwtBearer = signUpVerified(KEYED_ADMIN);
        http.get().uri("/v1/admin/me")
                .header("Authorization", jwtBearer)
                .exchange()
                .expectStatus().isOk();

        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", jwtBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "admin-probe"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();

        http.get().uri("/v1/admin/me")
                .header("Authorization", "Bearer " + key.plaintext())
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ===== Helpers ======================================================

    private String signUp(String email) {
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Admin Gate Test"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    /** Signs up, then marks the email verified directly instead of following the emailed link. */
    private String signUpVerified(String email) {
        String bearer = signUp(email);
        User u = users.findByEmail(email).orElseThrow();
        u.setEmailVerified(true);
        users.save(u);
        return bearer;
    }
}
