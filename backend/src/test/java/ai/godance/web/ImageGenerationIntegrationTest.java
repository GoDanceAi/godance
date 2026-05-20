package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
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
import ai.godance.web.dto.ImageDtos.ImageGenerationResponse;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * End-to-end image-gen path: signup → seed balance → POST
 * {@code /v1/images/generations} → upstream WireMock OpenAI → upload
 * to Testcontainers MinIO → response carries a presigned URL pointing
 * at MinIO. Also covers the async path: 202 + job_id → poll
 * {@code GET /v1/jobs/{id}} until {@code DONE}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ImageGenerationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-13T22-19-12Z")
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base",  () -> openAiMock.baseUrl());
        r.add("godance.object-storage.endpoint",    MINIO::getS3URL);
        r.add("godance.object-storage.access-key",  MINIO::getUserName);
        r.add("godance.object-storage.secret-key",  MINIO::getPassword);
        r.add("godance.object-storage.bucket",      () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
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

    /** Stub OpenAI's image endpoint to return a single 1x1 PNG. */
    private static void stubOpenAiOneImage() {
        byte[] tinyPng = new byte[] {
                (byte)0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x00, 0x00, 0x0D,
                0x49, 0x48, 0x44, 0x52, 0x00, 0x00, 0x00, 0x01, 0x00, 0x00, 0x00, 0x01,
                0x08, 0x06, 0x00, 0x00, 0x00, 0x1F, 0x15, (byte)0xC4, (byte)0x89, 0x00, 0x00, 0x00,
                0x0A, 0x49, 0x44, 0x41, 0x54, 0x78, (byte)0x9C, 0x63, 0x00, 0x01, 0x00, 0x00,
                0x05, 0x00, 0x01, 0x0D, 0x0A, 0x2D, (byte)0xB4, 0x00, 0x00, 0x00, 0x00, 0x49,
                0x45, 0x4E, 0x44, (byte)0xAE, 0x42, 0x60, (byte)0x82
        };
        String b64 = Base64.getEncoder().encodeToString(tinyPng);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\""
                                + b64 + "\",\"revised_prompt\":\"A cat (revised)\"}]}")));
    }

    // ===== Sync path ==================================================

    @Test
    void generate_sync_uploadsToObjectStorage_andReturnsPresignedUrl() {
        Signed u = signupWithBalance(100_00L);   // $100 seed
        stubOpenAiOneImage();

        ImageGenerationResponse out = http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-image-1",
                        "prompt", "A cat sitting on a chair",
                        "size", "1024x1024",
                        "n", 1))
                .exchange()
                .expectStatus().isOk()
                .expectBody(ImageGenerationResponse.class).returnResult().getResponseBody();

        assertThat(out.data()).hasSize(1);
        assertThat(out.data().get(0).url())
                .as("URL points at the Testcontainers MinIO endpoint")
                .startsWith(MINIO.getS3URL());
        assertThat(out.data().get(0).revisedPrompt()).isEqualTo("A cat (revised)");
        // gpt-image-1: 40 000 µ¢/image × 1.05 (image markup) = 42 000 µ¢ → HALF_UP /10 000 = 4¢
        assertThat(out.costCents()).isEqualTo(4L);
    }

    // ===== Async path =================================================

    @Test
    void generate_async_returns202_jobReachesDoneAfterProcessing() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubOpenAiOneImage();

        AsyncJobAcknowledged ack = http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-image-1",
                        "prompt", "A red ball",
                        "n", 1,
                        "async", true))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        assertThat(ack.jobId()).isNotNull();
        assertThat(ack.status()).isEqualTo("QUEUED");

        // Poll until DONE (or 5 sec).
        JobView finalView = null;
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            finalView = http.get().uri("/v1/jobs/{id}", ack.jobId())
                    .header("Authorization", u.bearer())
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (finalView != null
                    && (finalView.status() == JobStatus.DONE || finalView.status() == JobStatus.FAILED)) {
                break;
            }
            Thread.sleep(100);
        }
        assertThat(finalView).isNotNull();
        assertThat(finalView.status()).isEqualTo(JobStatus.DONE);
        assertThat(finalView.outputUrl()).startsWith(MINIO.getS3URL());
    }

    // ===== Failure modes ==============================================

    @Test
    void generate_zeroBalance_returns402() {
        Signed u = signupWithBalance(0L);

        http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-1", "prompt", "x", "n", 1))
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
    void generate_hqImageModel_autoPromotesToAsync_returns202EvenWithoutAsyncFlag() {
        Signed u = signupWithBalance(100_00L);
        stubOpenAiOneImage();

        // gpt-image-1-hd has requiresAsync=true. Even though client didn't
        // pass `async: true`, the response should be 202 + jobId, NOT 200 + URL.
        AsyncJobAcknowledged ack = http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "gpt-image-1-hd",
                        "prompt", "A high quality image",
                        "n", 1))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        assertThat(ack.jobId()).isNotNull();
        assertThat(ack.status()).isEqualTo("QUEUED");
    }

    @Test
    void getJob_otherUsersJob_returns404() {
        Signed owner = signupWithBalance(100_00L);
        stubOpenAiOneImage();

        AsyncJobAcknowledged ack = http.post().uri("/v1/images/generations")
                .header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-1", "prompt", "x", "async", true))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        Signed thief = signupWithBalance(0L);
        http.get().uri("/v1/jobs/{id}", ack.jobId())
                .header("Authorization", thief.bearer())
                .exchange()
                .expectStatus().isNotFound();
    }
}
