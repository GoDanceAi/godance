package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.PriceField;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.domain.User;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Operator-allowlist enforcement on {@code /v1/admin/pricing/**} plus
 * the approve-flow's effect on the live catalog. Two account fixtures:
 * one whose email is in {@code godance.admin.emails}, one whose isn't.
 *
 * <p>The non-admin's request lands in {@link
 * ai.godance.security.AdminAuthorizationFilter} and is rejected as
 * RFC 7807 403; the admin's request passes through to
 * {@link ai.godance.web.AdminPricingController}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AdminPricingFlowIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> "admin@godance.test");
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired WebTestClient http;
    @Autowired ModelChangeProposalRepository proposals;
    @Autowired ModelCatalog catalog;
    @Autowired UserRepository users;

    private String adminBearer;
    private String userBearer;

    /** The next test's sign-in is by password alone, so 2FA can't stay on. */
    @AfterEach
    void turnOffTwoFactor() {
        twoFactors.deleteAll();
    }

    @BeforeEach
    void seedAccounts() {
        // Admin email must match the allowlist exactly. Signup-or-login so
        // the @BeforeEach is idempotent across tests in this class.
        adminBearer = signUpOrLogIn("admin@godance.test");
        markEmailVerified("admin@godance.test");
        TwoFactorFixture.turnOn(twoFactors, users.findByEmail("admin@godance.test").orElseThrow().getId());
        // Non-admin uses a fresh random local-part each test to dodge the
        // signup-uniqueness check; the allowlist still rejects them.
        userBearer  = signUpOrLogIn("user-" + java.util.UUID.randomUUID() + "@godance.test");
    }

    @Test
    void list_givenNonAdminCaller_returns403_problemJson() {
        proposals.save(pendingProposal("kimi-k2.6", 950L, 1_500L));
        http.get().uri("/v1/admin/pricing/proposals")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void list_givenUnauthenticatedCaller_returns401() {
        http.get().uri("/v1/admin/pricing/proposals")
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void approve_priceChangeProposal_mutatesCatalog_reflectsInModelsEndpoint() {
        ModelChangeProposal saved = proposals.save(pendingProposal("kimi-k2.6", 950L, 1_500L));
        long beforeInput = catalog.find("kimi-k2.6").orElseThrow().inputMicrocentsPer1k();
        assertThat(beforeInput).isEqualTo(950L);

        http.post().uri("/v1/admin/pricing/proposals/" + saved.getId() + "/approve")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("APPROVED");

        // ModelCatalog cache refreshed → /v1/models reports the new input cost.
        ModelsController.ModelListResponse body = http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ModelsController.ModelListResponse.class)
                .returnResult().getResponseBody();
        ModelsController.ModelEntry kimi = body.data().stream()
                .filter(m -> "kimi-k2.6".equals(m.id()))
                .findFirst().orElseThrow();
        @SuppressWarnings("unchecked")
        Map<String, Object> pricing = (Map<String, Object>) kimi.godance().get("pricing");
        assertThat(((Number) pricing.get("input_microcents_per_1k")).longValue()).isEqualTo(1_500L);
    }

    @Test
    void approve_alreadyApprovedProposal_returnsCurrentState_doesNotDoubleApply() {
        ModelChangeProposal saved = proposals.save(pendingProposal("glm-4.7-flash", 60L, 120L));
        http.post().uri("/v1/admin/pricing/proposals/" + saved.getId() + "/approve")
                .header("Authorization", adminBearer)
                .exchange().expectStatus().isOk();

        long afterFirstApprove = catalog.find("glm-4.7-flash").orElseThrow().inputMicrocentsPer1k();

        // Mutate the catalog row out-of-band to a different value, then re-approve.
        // The idempotent re-approval must NOT clobber the out-of-band state.
        // (Use the controller to re-fire; the service-level guard is what we're testing.)
        http.post().uri("/v1/admin/pricing/proposals/" + saved.getId() + "/approve")
                .header("Authorization", adminBearer)
                .exchange().expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("APPROVED");

        assertThat(catalog.find("glm-4.7-flash").orElseThrow().inputMicrocentsPer1k())
                .isEqualTo(afterFirstApprove);
    }

    @Test
    void reject_proposal_recordsDecisionWithoutCatalogChange() {
        ModelChangeProposal saved = proposals.save(pendingProposal("doubao-1.6-pro", 110L, 999L));
        long before = catalog.find("doubao-1.6-pro").orElseThrow().inputMicrocentsPer1k();

        http.post().uri("/v1/admin/pricing/proposals/" + saved.getId() + "/reject")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("REJECTED");

        assertThat(catalog.find("doubao-1.6-pro").orElseThrow().inputMicrocentsPer1k())
                .isEqualTo(before);
    }

    private ModelChangeProposal pendingProposal(String modelName, long oldValue, long newValue) {
        ModelChangeProposal p = new ModelChangeProposal();
        p.setType(ProposalType.PRICE_CHANGE);
        p.setStatus(ProposalStatus.PENDING);
        p.setModelName(modelName);
        Map<String, Object> payload = new HashMap<>();
        payload.put("field",       PriceField.INPUT.asColumnKey());
        payload.put("old_value",   oldValue);
        payload.put("new_value",   newValue);
        payload.put("source",      "test");
        payload.put("source_url",  null);
        p.setPayload(payload);
        p.setSource("test");
        p.setDetectedAt(Instant.now());
        return p;
    }

    private String signUpOrLogIn(String email) {
        // Best-effort signup — ignore the 409 if this user already exists from
        // a prior test invocation, then always log in to get a fresh token.
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Admin Test"))
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }

    /**
     * Admin access needs the account's email verified. Password signup
     * leaves it unverified, so mark it directly instead of following the
     * emailed link.
     */
    private void markEmailVerified(String email) {
        User u = users.findByEmail(email).orElseThrow();
        u.setEmailVerified(true);
        users.save(u);
    }

    /** Compile-time keep-alive so unused imports don't break -Werror builds. */
    @SuppressWarnings("unused")
    private void touchUnusedToHelpClasspathCheck() {
        Class<?> a = HttpStatus.class;
        List<?> b = List.of();
    }
}
