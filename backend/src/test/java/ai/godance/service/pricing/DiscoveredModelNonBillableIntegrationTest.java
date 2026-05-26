package ai.godance.service.pricing;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;

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
import ai.godance.domain.Modality;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.Provider;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.web.ModelsController;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Enforces the brief's "DISCOVERED is non-billable" rule end-to-end:
 * <ol>
 *   <li>Insert a fresh {@code DISCOVERED} row into {@code model_catalog}
 *       and refresh the in-memory cache.</li>
 *   <li>{@code GET /v1/models} omits it (only ACTIVE rows are listed).</li>
 *   <li>{@code POST /v1/chat/completions} naming it returns 404
 *       "unknown model" — the catalog never hands it to the gateway,
 *       so the user is never billed.</li>
 * </ol>
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class DiscoveredModelNonBillableIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired ModelCatalogEntryRepository entries;
    @Autowired ModelCatalog catalog;

    @Test
    void discoveredModel_isNotInModelList_and_chatRequest_returns404() {
        String discoveredName = "grok-mythos-5-0";
        ModelCatalogEntry e = new ModelCatalogEntry();
        e.setModelName(discoveredName);
        e.setProvider(Provider.XAI);
        e.setModality(Modality.TEXT);
        e.setStatus(CatalogStatus.DISCOVERED);
        e.setCostSource("test-fixture");
        e.setLastVerifiedAt(Instant.now());
        entries.save(e);
        catalog.refresh();

        // (a) /v1/models filters DISCOVERED out — operator-curated public surface.
        ModelsController.ModelListResponse body = http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ModelsController.ModelListResponse.class)
                .returnResult().getResponseBody();
        assertThat(body.data().stream().map(ModelsController.ModelEntry::id))
                .doesNotContain(discoveredName);

        // (b) Chat request to the discovered model is rejected before any billing.
        String bearer = signUp("chat-" + java.util.UUID.randomUUID() + "@godance.test");
        http.post().uri("/v1/chat/completions")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", discoveredName,
                        "messages", List.of(Map.of("role", "user", "content", "hi"))))
                .exchange()
                .expectStatus().isNotFound();
    }

    private String signUp(String email) {
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Discovery Test"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }
}
