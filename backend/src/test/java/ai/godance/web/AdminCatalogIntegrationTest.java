package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ai.godance.domain.CatalogStatus;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.ModelPriceHistory;
import ai.godance.domain.PriceField;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.repository.ModelPriceHistoryRepository;
import ai.godance.service.AuditLogService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Admin catalog management: full listing (non-ACTIVE rows included),
 * enable/disable + markup PATCH (audited, refreshes the in-memory
 * {@link ModelCatalog}), and per-model price history. Allowlist 403
 * coverage on every route.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AdminCatalogIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> "admin@godance.test");
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired ModelCatalogEntryRepository catalogRepo;
    @Autowired ModelPriceHistoryRepository historyRepo;
    @Autowired ModelCatalog modelCatalog;
    @Autowired AuditLogEntryRepository audit;

    private String adminBearer;
    private String userBearer;

    @BeforeEach
    void seedAccounts() {
        adminBearer = signUpOrLogIn("admin@godance.test");
        userBearer = signUpOrLogIn("user-" + java.util.UUID.randomUUID() + "@godance.test");
    }

    @Test
    void listModels_includesDisabledRows_withEffectiveMarkup() {
        ModelCatalogEntry kimi = catalogRepo.findByModelName("kimi-k2.6").orElseThrow();
        kimi.setStatus(CatalogStatus.DEPRECATED);
        catalogRepo.save(kimi);
        try {
            http.get().uri("/v1/admin/pricing/models")
                    .header("Authorization", adminBearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.models[?(@.modelName=='kimi-k2.6')].status").isEqualTo("DEPRECATED")
                    .jsonPath("$.models[?(@.modelName=='kimi-k2.6')].enabled").isEqualTo(false)
                    .jsonPath("$.models[?(@.modelName=='kimi-k2.6')].effectiveMarkupPercent").exists();
        } finally {
            kimi.setStatus(CatalogStatus.ACTIVE);
            catalogRepo.save(kimi);
            modelCatalog.refresh();
        }
    }

    @Test
    void updateModel_disable_removesFromLiveCatalog_writesAudit() {
        ModelCatalogEntry glm = catalogRepo.findByModelName("glm-4.7-flash").orElseThrow();
        assertThat(modelCatalog.find("glm-4.7-flash")).isPresent();

        try {
            http.patch().uri("/v1/admin/pricing/models/" + glm.getId())
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("enabled", false))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.status").isEqualTo("DEPRECATED")
                    .jsonPath("$.enabled").isEqualTo(false);

            // The in-memory snapshot refreshed — model no longer dispatchable.
            assertThat(modelCatalog.find("glm-4.7-flash")).isEmpty();

            var events = audit.findByEventType(AuditLogService.EVT_ADMIN_CATALOG_UPDATED).stream()
                    .filter(e -> "glm-4.7-flash".equals(e.getEventDetail().get("modelName")))
                    .toList();
            assertThat(events).isNotEmpty();
            assertThat(events.get(events.size() - 1).getEventDetail().get("adminEmail"))
                    .isEqualTo("admin@godance.test");

            // Re-enable via the endpoint — round-trips back to ACTIVE.
            http.patch().uri("/v1/admin/pricing/models/" + glm.getId())
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("enabled", true))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.enabled").isEqualTo(true);
            assertThat(modelCatalog.find("glm-4.7-flash")).isPresent();
        } finally {
            ModelCatalogEntry fresh = catalogRepo.findByModelName("glm-4.7-flash").orElseThrow();
            fresh.setStatus(CatalogStatus.ACTIVE);
            catalogRepo.save(fresh);
            modelCatalog.refresh();
        }
    }

    @Test
    void updateModel_markupOverride_appliesToLiveCatalog() {
        ModelCatalogEntry doubao = catalogRepo.findByModelName("doubao-1.6-pro").orElseThrow();
        Double before = doubao.getMarkupOverride();
        try {
            http.patch().uri("/v1/admin/pricing/models/" + doubao.getId())
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("markupPercent", 12.5))
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody()
                    .jsonPath("$.markupOverride").isEqualTo(12.5)
                    .jsonPath("$.effectiveMarkupPercent").isEqualTo(12.5);

            assertThat(modelCatalog.find("doubao-1.6-pro").orElseThrow().markupOverride())
                    .isEqualTo(12.5);
        } finally {
            ModelCatalogEntry fresh = catalogRepo.findByModelName("doubao-1.6-pro").orElseThrow();
            fresh.setMarkupOverride(before);
            catalogRepo.save(fresh);
            modelCatalog.refresh();
        }
    }

    @Test
    void updateModel_noFields_returns400() {
        ModelCatalogEntry any = catalogRepo.findAll().get(0);
        http.patch().uri("/v1/admin/pricing/models/" + any.getId())
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of())
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateModel_negativeMarkup_returns400() {
        ModelCatalogEntry any = catalogRepo.findAll().get(0);
        http.patch().uri("/v1/admin/pricing/models/" + any.getId())
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("markupPercent", -5))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void updateModel_unknownId_returns404() {
        http.patch().uri("/v1/admin/pricing/models/999999")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void updateModel_givenNonAdminCaller_returns403_noCatalogChange() {
        ModelCatalogEntry kimi = catalogRepo.findByModelName("kimi-k2.6").orElseThrow();
        CatalogStatus before = kimi.getStatus();
        http.patch().uri("/v1/admin/pricing/models/" + kimi.getId())
                .header("Authorization", userBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isForbidden();
        assertThat(catalogRepo.findByModelName("kimi-k2.6").orElseThrow().getStatus())
                .isEqualTo(before);
    }

    @Test
    void listModels_givenNonAdminCaller_returns403() {
        http.get().uri("/v1/admin/pricing/models")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    @Test
    void priceHistory_returnsObservationsNewestFirst() {
        ModelCatalogEntry kimi = catalogRepo.findByModelName("kimi-k2.6").orElseThrow();
        ModelPriceHistory h = new ModelPriceHistory();
        h.setModelName("kimi-k2.6");
        h.setField(PriceField.INPUT);
        h.setOldValue(900L);
        h.setNewValue(950L);
        h.setSource("test-feed");
        h.setObservedAt(java.time.Instant.now());
        historyRepo.save(h);

        http.get().uri("/v1/admin/pricing/models/" + kimi.getId() + "/history")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.history[0].field").isEqualTo("INPUT")
                .jsonPath("$.history[0].oldValue").isEqualTo(900)
                .jsonPath("$.history[0].newValue").isEqualTo(950)
                .jsonPath("$.history[0].source").isEqualTo("test-feed");
    }

    @Test
    void priceHistory_givenNonAdminCaller_returns403() {
        ModelCatalogEntry any = catalogRepo.findAll().get(0);
        http.get().uri("/v1/admin/pricing/models/" + any.getId() + "/history")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    private String signUpOrLogIn(String email) {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Catalog Test"))
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
}
