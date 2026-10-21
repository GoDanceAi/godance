package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
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
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * End-to-end image-gen path: signup → seed balance → POST
 * {@code /v1/images/generations} → upstream WireMock → upload to
 * Testcontainers MinIO → poll {@code GET /v1/jobs/{id}} until terminal.
 *
 * <p>Every image model is async ({@code requiresAsync = true}), so the
 * endpoint always answers 202 + {@code job_id}. Covers two providers
 * (OpenAI {@code gpt-image-2} and Volcengine Ark {@code seedream-5.0}),
 * the refresh of the {@code /v1/models} catalog, and the
 * debit-on-success / refund-on-failure ledger behavior.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ImageGenerationIntegrationTest {

    /** A 1×1 transparent PNG — what every upstream stub returns. */
    private static final byte[] TINY_PNG = {
            (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
            0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
            0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte) 0xC4, (byte) 0x89, 0x00, 0x00, 0x00,
            0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, (byte) 0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
            0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte) 0xB4, 0x00, 0x00, 0x00, 0x00, 0x49,
            0x45, 0x4E, 0x44, (byte) 0xAE, 0x42, 0x60, (byte) 0x82
    };

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;
    static WireMockServer volcMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
        volcMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        volcMock.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
        if (volcMock != null) volcMock.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base",     () -> openAiMock.baseUrl());
        r.add("godance.providers.volcengine.api-base", () -> volcMock.baseUrl());
        r.add("godance.object-storage.endpoint",       MINIO::getS3URL);
        r.add("godance.object-storage.access-key",     MINIO::getUserName);
        r.add("godance.object-storage.secret-key",     MINIO::getPassword);
        r.add("godance.object-storage.bucket",         () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        volcMock.resetAll();
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "img-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Image Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO,
                    "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    /** Stub an OpenAI-shaped {@code b64_json} image endpoint on {@code mock}. */
    private static void stubOneB64Image(WireMockServer mock, String path) {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        mock.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\""
                                + b64 + "\",\"revised_prompt\":\"revised\"}]}")));
    }

    private JobView pollUntilTerminal(UUID jobId, String bearer) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header("Authorization", bearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null
                    && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) {
                return view;
            }
            Thread.sleep(100);
        }
        return view;
    }

    /** Ledger writes on the async worker commit slightly after the job row — poll. */
    private void awaitBalance(UUID userId, long expectedCents) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 3_000;
        while (System.currentTimeMillis() < deadline
                && ledger.authoritativeBalance(userId) != expectedCents) {
            Thread.sleep(50);
        }
        assertThat(ledger.authoritativeBalance(userId)).isEqualTo(expectedCents);
    }

    private AsyncJobAcknowledged submitImage(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/images/generations")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();
    }

    // ===== Job lifecycle — OpenAI gpt-image-2 =========================

    @Test
    void generate_gptImage2_async202_jobReachesDone_storesUrl_andDebits() throws Exception {
        Signed u = signupWithBalance(100_00L);   // $100 seed
        stubOneB64Image(openAiMock, "/v1/images/generations");

        // No async flag passed — every image model auto-promotes to async.
        AsyncJobAcknowledged ack = submitImage(u.bearer(),
                Map.of("model", "gpt-image-2", "prompt", "A cat on a chair", "size", "1024x1024", "n", 1));

        assertThat(ack.jobId()).isNotNull();
        assertThat(ack.status()).isEqualTo("QUEUED");

        JobView done = pollUntilTerminal(ack.jobId(), u.bearer());
        assertThat(done).isNotNull();
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.outputUrl()).startsWith(MINIO.getS3URL());

        // gpt-image-2: 53 000 µ¢ × 1.12 (12% image markup) = 59 360 µ¢ → HALF_UP /10 000 = 6¢.
        assertThat(done.actualCostCents()).isEqualTo(6L);
        awaitBalance(u.id(), 100_00L - 6L);
    }

    // ===== Job lifecycle — Volcengine Ark Seedream ====================

    @Test
    void generate_seedream_async202_jobReachesDone_storesUrl_andDebits() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubOneB64Image(volcMock, "/api/v3/images/generations");

        AsyncJobAcknowledged ack = submitImage(u.bearer(),
                Map.of("model", "seedream-5.0", "prompt", "A koi pond", "size", "1024x1024", "n", 1));

        JobView done = pollUntilTerminal(ack.jobId(), u.bearer());
        assertThat(done).isNotNull();
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.outputUrl()).startsWith(MINIO.getS3URL());

        // seedream-5.0: 35 000 µ¢ × 1.12 = 39 200 µ¢ → HALF_UP /10 000 = 4¢.
        assertThat(done.actualCostCents()).isEqualTo(4L);
        awaitBalance(u.id(), 100_00L - 4L);
    }

    // ===== Refund on upstream failure =================================

    @Test
    void generate_upstreamFails_jobFailed_andBalanceRefunded() throws Exception {
        Signed u = signupWithBalance(100_00L);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"error\":{\"message\":\"upstream boom\"}}")));

        AsyncJobAcknowledged ack = submitImage(u.bearer(),
                Map.of("model", "gpt-image-2", "prompt", "A doomed render", "n", 1));

        JobView failed = pollUntilTerminal(ack.jobId(), u.bearer());
        assertThat(failed).isNotNull();
        assertThat(failed.status()).isEqualTo(JobStatus.FAILED);

        // Pre-debit is reversed on failure — balance back to the full seed.
        awaitBalance(u.id(), 100_00L);
    }

    // ===== Failure modes ==============================================

    @Test
    void generate_zeroBalance_returns402_andDoesNotCallUpstream() {
        Signed u = signupWithBalance(0L);

        http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "x", "n", 1))
                .exchange()
                .expectStatus().isEqualTo(402);

        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void generate_textModelUsed_returns400() {
        Signed u = signupWithBalance(100_00L);

        http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "prompt", "x"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getJob_otherUsersJob_returns404() {
        Signed owner = signupWithBalance(100_00L);
        stubOneB64Image(openAiMock, "/v1/images/generations");

        AsyncJobAcknowledged ack = submitImage(owner.bearer(),
                Map.of("model", "gpt-image-2", "prompt", "x", "n", 1));

        Signed thief = signupWithBalance(0L);
        http.get().uri("/v1/jobs/{id}", ack.jobId())
                .header("Authorization", thief.bearer())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ===== Catalog — GET /v1/models ===================================

    @Test
    void models_listsImageModels_withPerImagePricing_andRequiresAsync() {
        ModelsController.ModelListResponse body = http.get().uri("/v1/models")
                .exchange()
                .expectStatus().isOk()
                .expectBody(ModelsController.ModelListResponse.class)
                .returnResult().getResponseBody();

        assertThat(body).isNotNull();
        List<String> ids = body.data().stream().map(ModelsController.ModelEntry::id).toList();
        assertThat(ids).contains(
                "gpt-image-2", "seedream-5.0", "qwen-image-2.0", "qwen-image-2.0-pro",
                "flux-2-pro", "flux-1.1-pro", "nano-banana", "imagen-4", "sdxl");

        // Every image model carries per-image pricing, is async, and publishes
        // the 12% image-tier markup it is billed at.
        body.data().stream()
                .filter(m -> "IMAGE".equals(m.godance().get("modality")))
                .forEach(m -> {
                    assertThat(m.godance()).containsEntry("requires_async", true);
                    assertThat(m.godance()).containsEntry("markup_percent", 12.0);
                    assertThat(m.godance().get("pricing")).isInstanceOf(Map.class);
                    @SuppressWarnings("unchecked")
                    Map<String, Object> pricing = (Map<String, Object>) m.godance().get("pricing");
                    assertThat(pricing).containsKey("per_image_microcents");
                });

        assertThat(body.data()).anySatisfy(m -> {
            assertThat(m.id()).isEqualTo("gpt-image-2");
            assertThat(m.ownedBy()).isEqualTo("openai");
            assertThat(m.godance()).containsEntry("modality", "IMAGE");
        });
    }
}
