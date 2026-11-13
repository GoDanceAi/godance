package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CatalogStatus;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.Provider;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.VideoProviderGateway;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.EstimateDtos.EstimateResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;

/**
 * Model metadata (V24) on {@code GET /v1/models}, the published default clip
 * length against each adapter, and {@code POST /v1/estimates} against the
 * pre-debit the matching generation request really takes.
 *
 * <p>Every provider points at one WireMock with no stubs, so each generation
 * submitted here is accepted, pre-debited, fails upstream and is refunded;
 * nothing leaves the machine.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ModelMetadataAndEstimateIntegrationTest {

    /** Every image and video model V24 seeds; a test below keeps this equal to the ACTIVE catalog. */
    static final List<String> SEEDED_IMAGE_MODELS = List.of(
            "gpt-image-2", "gpt-image-1.5", "seedream-5.0", "qwen-image-2.0", "qwen-image-2.0-pro",
            "flux-2-pro", "flux-1.1-pro", "nano-banana", "imagen-4", "sdxl");
    static final List<String> SEEDED_VIDEO_MODELS = List.of(
            "seedance-2.0", "sora-2", "sora-2-pro", "sora-2-pro-hd", "veo-3.1", "veo-3.1-fast",
            "kling-3.0", "runway-gen4", "minimax-hailuo-2.3");
    /** Seeded with max_n above 1. */
    static final Set<String> MULTI_IMAGE_MODELS = Set.of("gpt-image-2", "gpt-image-1.5");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer providers;

    @BeforeAll
    static void startMock() {
        providers = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        providers.start();
    }

    @AfterAll
    static void stopMock() {
        if (providers != null) providers.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        for (String p : List.of("openai", "google", "bfl", "volcengine", "alibaba", "minimax",
                "stability", "kling", "runway")) {
            r.add("godance.providers." + p + ".api-base", () -> providers.baseUrl());
        }
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    /** No upload happens (every upstream fails), and no MinIO is needed. */
    @MockitoBean ObjectStorageClient storage;

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired ModelCatalog catalog;
    @Autowired ModelCatalogEntryRepository catalogRows;
    @Autowired VideoProviderGateway videoGateway;

    // ===== GET /v1/models =============================================

    @Test
    void models_givenSeededMetadata_expectsDisplayNameDescriptionAndCapabilities() {
        Map<String, Map<String, Object>> godance = modelsById();

        assertThat(godance.get("flux-1.1-pro"))
                .containsEntry("display_name", "FLUX1.1 [pro]")
                .containsKey("description")
                .containsEntry("capabilities", Map.of("default_size", "1024x1024", "max_n", 1));
        assertThat(godance.get("gpt-image-1.5").get("capabilities")).isEqualTo(Map.of(
                "sizes", List.of("1024x1024", "1536x1024", "1024x1536", "auto"),
                "default_size", "auto", "max_n", 10));
        assertThat(godance.get("minimax-hailuo-2.3").get("capabilities"))
                .isEqualTo(Map.of("default_seconds", 6, "max_n", 1));
        // Unknown capabilities are left out, not published as empty.
        assertThat(godance.get("imagen-4")).containsEntry("display_name", "Imagen 4")
                .doesNotContainKey("capabilities");
        // A text model has none of the three; its existing fields are unchanged.
        assertThat(godance.get("gpt-4o")).doesNotContainKeys("display_name", "description", "capabilities")
                .containsKeys("modality", "requires_async", "pricing", "markup_percent");
    }

    @Test
    void seededModels_givenActiveCatalog_expectsEveryImageAndVideoModelSeededWithAName() {
        Set<String> active = catalog.all().stream()
                .filter(m -> m.modality() == Modality.IMAGE || m.modality() == Modality.VIDEO)
                .map(ModelInfo::modelName).collect(Collectors.toSet());
        assertThat(active).containsExactlyInAnyOrderElementsOf(
                Stream.concat(SEEDED_IMAGE_MODELS.stream(), SEEDED_VIDEO_MODELS.stream()).toList());
        for (String model : active) {
            assertThat(catalog.metadata(model).displayName()).as(model).isNotBlank();
            assertThat(catalog.metadata(model).description()).as(model).isNotBlank();
        }
    }

    @Test
    void defaultSeconds_givenEveryActiveVideoModel_expectsTheAdaptersDefaultDuration() {
        List<ModelInfo> videoModels = catalog.all().stream().filter(m -> m.modality() == Modality.VIDEO).toList();
        assertThat(videoModels).isNotEmpty();
        for (ModelInfo m : videoModels) {
            assertThat(catalog.metadata(m.modelName()).capabilities().defaultSeconds())
                    .as("published default_seconds of %s", m.modelName())
                    .isEqualTo(videoGateway.defaultDurationSeconds(m));
            assertThat(catalog.metadata(m.modelName()).capabilities().maxN()).as(m.modelName()).isEqualTo(1);
        }
    }

    @Test
    void approvedModel_givenNoCapabilities_expectsListedWithoutThemAndLimitedToOneOutput() {
        ModelCatalogEntry row = new ModelCatalogEntry();
        row.setModelName("approved-from-proposal-" + UUID.randomUUID().toString().substring(0, 8));
        row.setProvider(Provider.OPENAI);
        row.setModality(Modality.IMAGE);
        row.setPerImageMicrocents(25_000L);
        row.setRequiresAsync(true);
        row.setStatus(CatalogStatus.ACTIVE);
        row.setCostSource("proposal:test");
        row = catalogRows.save(row);
        catalog.refresh();
        try {
            assertThat(modelsById().get(row.getModelName()))
                    .containsKeys("modality", "pricing")
                    .doesNotContainKeys("display_name", "description", "capabilities");

            Signed u = signupWithBalance(10_00L);
            EstimateResponse one = estimate(u.bearer(), Map.of("model", row.getModelName(), "n", 1));
            assertThat(one.n()).isEqualTo(1);
            assertThat(one.estimatedCostCents()).isPositive();
            postEstimate(u.bearer(), Map.of("model", row.getModelName(), "n", 2))
                    .expectStatus().isBadRequest();
        } finally {
            catalogRows.delete(row);
            catalog.refresh();
        }
    }

    // ===== POST /v1/estimates =========================================

    static Stream<Arguments> seededRequests() {
        List<Arguments> cases = new ArrayList<>();
        for (String m : SEEDED_IMAGE_MODELS) {
            cases.add(Arguments.of(m, Map.of("n", 1)));
            if (MULTI_IMAGE_MODELS.contains(m)) cases.add(Arguments.of(m, Map.of("n", 2)));
        }
        for (String m : SEEDED_VIDEO_MODELS) {
            cases.add(Arguments.of(m, Map.of()));               // default clip length
            cases.add(Arguments.of(m, Map.of("seconds", 8)));
        }
        return cases.stream();
    }

    @ParameterizedTest(name = "{0} {1}")
    @MethodSource("seededRequests")
    void estimate_givenSeededModel_expectsTheLedgerPreDebitOfTheSameRequest(String model, Map<String, Object> extra)
            throws Exception {
        Signed u = signupWithBalance(1000_00L);
        Map<String, Object> request = new java.util.HashMap<>(extra);
        request.put("model", model);

        EstimateResponse est = estimate(u.bearer(), request);

        ModelInfo info = catalog.find(model).orElseThrow();
        assertThat(est.model()).isEqualTo(model);
        assertThat(est.modality()).isEqualTo(info.modality().name());
        assertThat(est.currency()).isEqualTo("USD");
        assertThat(est.estimatedCostCents()).isPositive();
        if (info.modality() == Modality.VIDEO) {
            Integer asked = (Integer) extra.get("seconds");
            assertThat(est.seconds()).isEqualTo(asked != null ? asked : videoGateway.defaultDurationSeconds(info));
        } else {
            assertThat(est.seconds()).isNull();
        }
        // Estimating charges nothing.
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(1000_00L);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();

        // The same request, submitted: its pre-debit is the estimate.
        Map<String, Object> generation = new java.util.HashMap<>(request);
        generation.put("prompt", "estimate check");
        String path = info.modality() == Modality.VIDEO ? "/v1/videos/generations" : "/v1/images/generations";
        UUID jobId = http.post().uri(path)
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(generation)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody().jobId();
        GenerationJob job = jobs.findById(jobId).orElseThrow();
        CreditLedger preDebit = ledgerRows.findByIdempotencyKey("estimate-" + job.getRequestId()).orElseThrow();

        assertThat(-preDebit.getDeltaCents()).isEqualTo(est.estimatedCostCents());
        assertThat(job.getEstimatedCostCents()).isEqualTo(est.estimatedCostCents());

        awaitEnded(jobId);   // the upstream refuses; the job ends FAILED and refunded
        awaitBalance(u.id(), 1000_00L);
    }

    @Test
    void estimate_givenNAboveMaxN_expects400ProblemJsonAndNoCharge() {
        Signed u = signupWithBalance(10_00L);

        postEstimate(u.bearer(), Map.of("model", "flux-1.1-pro", "n", 2))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        postEstimate(u.bearer(), Map.of("model", "gpt-image-2", "n", 11))
                .expectStatus().isBadRequest();
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(10_00L);
    }

    @Test
    void estimate_givenUnknownModel_expects404ProblemJson() {
        Signed u = signupWithBalance(0L);
        postEstimate(u.bearer(), Map.of("model", "no-such-model"))
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void estimate_givenTextModel_expects400ProblemJson() {
        Signed u = signupWithBalance(0L);
        postEstimate(u.bearer(), Map.of("model", "gpt-4o"))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void estimate_givenMissingModelOrZeroCount_expects400() {
        Signed u = signupWithBalance(0L);
        postEstimate(u.bearer(), Map.of("n", 1)).expectStatus().isBadRequest();
        postEstimate(u.bearer(), Map.of("model", "gpt-image-2", "n", 0)).expectStatus().isBadRequest();
        postEstimate(u.bearer(), Map.of("model", "sora-2", "seconds", 0)).expectStatus().isBadRequest();
    }

    @Test
    void estimate_givenFluxDefaults_expectsTheContractExample() {
        Signed u = signupWithBalance(0L);   // no balance needed: nothing is charged
        EstimateResponse est = estimate(u.bearer(), Map.of("model", "flux-1.1-pro", "n", 1, "size", "1024x1024"));

        // flux-1.1-pro: 40 000 µ¢ × 1.12 (12% image markup) = 44 800 µ¢ → HALF_UP /10 000 = 4¢.
        assertThat(est).isEqualTo(new EstimateResponse("flux-1.1-pro", "IMAGE", 1, null, 4L, "USD"));
    }

    @Test
    void estimate_givenApiKey_expects200_andGivenNoCredentials_expects401() {
        Signed u = signupWithBalance(0L);
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "estimate-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();

        assertThat(estimate("Bearer " + key.plaintext(), Map.of("model", "veo-3.1")).seconds()).isEqualTo(5);
        http.post().uri("/v1/estimates")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "veo-3.1"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "estimate-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Estimate Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private WebTestClient.ResponseSpec postEstimate(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/estimates")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private EstimateResponse estimate(String bearer, Map<String, Object> body) {
        return postEstimate(bearer, body)
                .expectStatus().isOk()
                .expectBody(EstimateResponse.class).returnResult().getResponseBody();
    }

    @SuppressWarnings("unchecked")
    private Map<String, Map<String, Object>> modelsById() {
        ModelsController.ModelListResponse body = http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ModelsController.ModelListResponse.class).returnResult().getResponseBody();
        return body.data().stream().collect(Collectors.toMap(ModelsController.ModelEntry::id,
                e -> (Map<String, Object>) (Map<?, ?>) e.godance()));
    }

    private void awaitEnded(UUID jobId) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            JobStatus s = jobs.findById(jobId).orElseThrow().getStatus();
            if (s == JobStatus.FAILED || s == JobStatus.DONE) return;
            Thread.sleep(50);
        }
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
    }

    private void awaitBalance(UUID userId, long expectedCents) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline && ledger.authoritativeBalance(userId) != expectedCents) {
            Thread.sleep(50);
        }
        assertThat(ledger.authoritativeBalance(userId)).isEqualTo(expectedCents);
    }
}
