package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
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
import ai.godance.domain.UserRole;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.security.AdminAuthorizationFilter;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Which accounts {@link ai.godance.security.AdminAuthorizationFilter} lets
 * onto {@code /v1/admin/**}: an active account with a verified email and
 * two-factor sign-in on that holds a role, over a JWT session.
 * {@link AdminRolesIntegrationTest} covers which role reaches which route.
 *
 * <p>Each test signs up its own address: the tests share one database and
 * an address can be registered only once.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AdminAuthorizationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    private static final String ALLOWLISTED = "allowlisted@godance.test";

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> ALLOWLISTED);
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired CreditLedgerRepository ledger;

    @Test
    void filter_givenUnverifiedAdmin_returns403() {
        String email = freshEmail();
        String bearer = signUp(email);
        User admin = hold(email, UserRole.ADMIN);
        assertThat(admin.isEmailVerified()).isFalse();

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        http.post().uri("/v1/admin/users/" + admin.getId() + "/credit-adjust")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 999_99, "note", "self-serve credit"))
                .exchange()
                .expectStatus().isForbidden();
        assertThat(ledger.sumDeltaForUser(admin.getId())).isZero();
    }

    @Test
    void filter_givenAdminVerifiesEmail_passesWithoutAFreshLogin() {
        String email = freshEmail();
        String bearer = signUp(email);
        hold(email, UserRole.ADMIN);
        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isForbidden();

        // Verify through the real link flow. The token issued at signup is
        // reused: the filter reads the account, not a claim, so it needs no
        // fresh login to pick up the verification.
        String token = users.findByEmail(email).orElseThrow().getEmailVerificationToken();
        http.get().uri(b -> b.path("/v1/auth/verify").queryParam("token", token).build())
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isNoContent();

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(email)
                .jsonPath("$.role").isEqualTo("ADMIN");
    }

    @Test
    void filter_givenVerifiedStaffWithoutTwoFactor_returns403WithCodeOnEveryRoute() {
        String email = freshEmail();
        String bearer = signUpVerified(email);
        User staff = users.findByEmail(email).orElseThrow();
        staff.setRole(UserRole.ADMIN);
        users.save(staff);

        for (String route : List.of("/v1/admin/me", "/v1/admin/users", "/v1/admin/usage/summary",
                "/v1/admin/pricing/proposals", "/v1/admin/pricing/models")) {
            http.get().uri(route)
                    .header("Authorization", bearer)
                    .exchange()
                    .expectStatus().isForbidden()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    .jsonPath("$.code").isEqualTo(AdminAuthorizationFilter.TWO_FACTOR_REQUIRED_CODE);
        }
    }

    @Test
    void filter_givenPlainUserWithoutTwoFactor_returns403WithoutTheCode() {
        // Nothing here should tell a customer that a console exists to unlock.
        http.get().uri("/v1/admin/me")
                .header("Authorization", signUpVerified(freshEmail()))
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.code").doesNotExist();
    }

    @Test
    void filter_givenVerifiedAccountOnTheAllowlist_returns403() {
        // The bootstrap runs at startup, before this account existed. From
        // then on the list opens nothing.
        String bearer = signUpVerified(ALLOWLISTED);

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void me_givenEmailClaimStale_returnsCurrentEmail() {
        String original = freshEmail();
        String bearer = signUpVerified(original);
        hold(original, UserRole.SUPPORT);

        // The role stays with the account when its address changes; the
        // claim still says the old one. Admin handlers see the account's
        // current address, never the claim.
        String current = freshEmail();
        User u = users.findByEmail(original).orElseThrow();
        u.setEmail(current);
        users.save(u);

        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo(current);
    }

    @Test
    void filter_givenDeactivatedAccount_returns401() {
        String email = freshEmail();
        String bearer = signUpVerified(email);
        hold(email, UserRole.ADMIN);
        http.get().uri("/v1/admin/me")
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk();

        User u = users.findByEmail(email).orElseThrow();
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
        String email = freshEmail();
        String jwtBearer = signUpVerified(email);
        hold(email, UserRole.ADMIN);
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

    private static String freshEmail() {
        return "gate-" + UUID.randomUUID() + "@godance.test";
    }

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

    /** Gives the account a role and the two-factor sign-in staff need. */
    private User hold(String email, UserRole role) {
        User u = users.findByEmail(email).orElseThrow();
        u.setRole(role);
        TwoFactorFixture.turnOn(twoFactors, u.getId());
        return users.save(u);
    }
}
