package ai.godance.web;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AdminFeaturesIntegrationTest {
    @Container @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;

    @Test
    void features_givenNoSession_returns401() {
        http.get().uri("/v1/admin/features").exchange().expectStatus().isUnauthorized();
    }

    @Test
    void features_givenVerifiedAdminWithTwoFactor_returnsExactDefaultSchema() {
        http.get().uri("/v1/admin/features").header("Authorization", account(UserRole.ADMIN, true, true))
                .exchange().expectStatus().isOk().expectBody().json("""
                {"inputs":{"enabled":false,"allow_people":false},
                 "community":{"enabled":false},"byok":{"enabled":false},
                 "routing_policies":{"enabled":false,"allow_list_restricted":false},
                 "referrals":{"enabled":false},"mcp":{"enabled":true}}
                """, org.springframework.test.json.JsonCompareMode.STRICT);
    }

    @Test
    void features_givenAdminWithoutTwoFactor_preservesEnrollmentCode() {
        http.get().uri("/v1/admin/features").header("Authorization", account(UserRole.ADMIN, true, false))
                .exchange().expectStatus().isForbidden().expectBody()
                .jsonPath("$.code").isEqualTo("two_factor_required");
    }

    @ParameterizedTest
    @EnumSource(value = UserRole.class, names = {"USER", "SUPPORT", "FINANCE"})
    void features_givenOtherRoleWithoutTwoFactor_returns403WithoutEnrollmentCode(UserRole role) {
        http.get().uri("/v1/admin/features").header("Authorization", account(role, true, false))
                .exchange().expectStatus().isForbidden().expectBody().jsonPath("$.code").doesNotExist();
    }

    @Test
    void features_givenUnverifiedAdmin_returns403() {
        http.get().uri("/v1/admin/features").header("Authorization", account(UserRole.ADMIN, false, true))
                .exchange().expectStatus().isForbidden();
    }

    @Test
    void features_givenInactiveAdmin_returns401() {
        String bearer = account(UserRole.ADMIN, true, true, false);
        http.get().uri("/v1/admin/features").header("Authorization", bearer)
                .exchange().expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @ValueSource(strings = {"POST", "PUT", "PATCH", "DELETE"})
    void features_givenWriteMethod_neverSucceeds(String method) {
        http.method(HttpMethod.valueOf(method)).uri("/v1/admin/features")
                .header("Authorization", account(UserRole.ADMIN, true, true))
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("enabled", true))
                .exchange().expectStatus().isForbidden();
    }

    private String account(UserRole role, boolean verified, boolean twoFactor) {
        return account(role, verified, twoFactor, true);
    }

    private String account(UserRole role, boolean verified, boolean twoFactor, boolean active) {
        String email = "features-" + UUID.randomUUID() + "@example.test";
        AuthResponse response = http.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Synthetic Admin"))
                .exchange().expectStatus().isCreated().expectBody(AuthResponse.class).returnResult().getResponseBody();
        User user = users.findByEmail(email).orElseThrow();
        user.setRole(role);
        user.setEmailVerified(verified);
        user.setActive(active);
        users.save(user);
        if (twoFactor) TwoFactorFixture.turnOn(twoFactors, user.getId());
        return "Bearer " + response.accessToken();
    }
}
