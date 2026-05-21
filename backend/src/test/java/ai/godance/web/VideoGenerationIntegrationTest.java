package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.WebhookSigner;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * End-to-end async video gen via {@link ai.godance.gateway.providers.SoraVideoProvider}:
 * submit → poll → MinIO upload → signed webhook delivery to a second
 * WireMock acting as the client-side receiver.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class VideoGenerationIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer("minio/minio:RELEASE.2024-12-13T22-19-12Z")
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;
    static WireMockServer webhookReceiver;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
        webhookReceiver = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        webhookReceiver.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
        if (webhookReceiver != null) webhookReceiver.stop();
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
    @Autowired WebhookSigner webhookSigner;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        webhookReceiver.resetAll();
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "vid-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Video Tester"))
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

    private static void stubSoraSuccessfulRun() {
        byte[] tinyMp4 = "FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8);

        openAiMock.stubFor(post(urlEqualTo("/v1/videos"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_int\",\"status\":\"queued\"}")));

        openAiMock.stubFor(get(urlPathMatching("/v1/videos/vid_int"))
                .inScenario("sora-int").whenScenarioStateIs(STARTED)
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_int\",\"status\":\"in_progress\"}"))
                .willSetStateTo("in-progress"));
        openAiMock.stubFor(get(urlPathMatching("/v1/videos/vid_int"))
                .inScenario("sora-int").whenScenarioStateIs("in-progress")
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_int\",\"status\":\"completed\"}")));

        openAiMock.stubFor(get(urlEqualTo("/v1/videos/vid_int/content"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody(tinyMp4)));
    }

    /** Submit succeeds; the upstream poll then reports {@code failed}. */
    private static void stubSoraFailingRun() {
        openAiMock.stubFor(post(urlEqualTo("/v1/videos"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_fail\",\"status\":\"queued\"}")));
        openAiMock.stubFor(get(urlPathMatching("/v1/videos/vid_fail"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_fail\",\"status\":\"failed\"}")));
    }

    // ===== Tests ======================================================

    @Test
    void videos_alwaysAsync_returns202_jobReachesDoneAfterProcessing() throws Exception {
        Signed u = signupWithBalance(1000_00L);   // $1000 — sora-2 at $0.15/s gets expensive fast
        stubSoraSuccessfulRun();

        AsyncJobAcknowledged ack = http.post().uri("/v1/videos/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "sora-2",
                        "prompt", "A tiger surfs a wave",
                        "seconds", 5))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        assertThat(ack.jobId()).isNotNull();
        assertThat(ack.status()).isEqualTo("QUEUED");

        JobView finalView = pollJob(u.bearer(), ack.jobId());
        assertThat(finalView).isNotNull();
        assertThat(finalView.status()).isEqualTo(JobStatus.DONE);
        assertThat(finalView.outputUrl()).startsWith(MINIO.getS3URL());
        // sora-2: 100 000 µ¢/s × 5s = 500 000 µ¢ = 50¢ × 1.12 (markupOverride) = 56¢ HALF_UP
        assertThat(finalView.actualCostCents()).isBetween(50L, 65L);
    }

    @Test
    void videos_webhookDelivered_withValidHmacSignature() throws Exception {
        Signed u = signupWithBalance(1000_00L);
        stubSoraSuccessfulRun();
        webhookReceiver.stubFor(post(urlEqualTo("/cb"))
                .withHeader(WebhookSigner.HEADER, matching("t=\\d+,v1=[0-9a-f]{64}"))
                .willReturn(aResponse().withStatus(200)));

        AsyncJobAcknowledged ack = http.post().uri("/v1/videos/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "sora-2",
                        "prompt", "Webhook delivery test",
                        "seconds", 5,
                        "webhook_url", webhookReceiver.baseUrl() + "/cb"))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        pollJob(u.bearer(), ack.jobId());

        // Webhook may arrive slightly after the job is DONE.
        long deadline = System.currentTimeMillis() + 5_000;
        List<LoggedRequest> webhookCalls = List.of();
        while (System.currentTimeMillis() < deadline) {
            webhookCalls = webhookReceiver.findAll(postRequestedFor(urlEqualTo("/cb")));
            if (!webhookCalls.isEmpty()) break;
            Thread.sleep(100);
        }
        assertThat(webhookCalls).hasSize(1);

        // Verify the signature by recomputing it client-side.
        LoggedRequest call = webhookCalls.get(0);
        String header = call.getHeader(WebhookSigner.HEADER);
        String body = call.getBodyAsString();
        String secret = webhookSigner.secretFor(u.id());

        String[] parts = header.split(",");
        long t = Long.parseLong(parts[0].substring(2));
        String v1 = parts[1].substring(3);
        assertThat(v1).isEqualTo(hmacHex(secret, t + "." + body));
    }

    @Test
    void videos_zeroBalance_returns402_doesNotEnqueueJob() {
        Signed u = signupWithBalance(0L);

        http.post().uri("/v1/videos/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "sora-2", "prompt", "x", "seconds", 5))
                .exchange()
                .expectStatus().isEqualTo(402);

        // No GenerationJob row should have been inserted for this user.
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
    }

    @Test
    void videos_textModelUsed_returns400() {
        Signed u = signupWithBalance(100_00L);

        http.post().uri("/v1/videos/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "prompt", "x", "seconds", 5))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void videos_upstreamFails_jobReachesFailed_andPreDebitRefunded() throws Exception {
        Signed u = signupWithBalance(1000_00L);
        stubSoraFailingRun();

        AsyncJobAcknowledged ack = http.post().uri("/v1/videos/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "model", "sora-2",
                        "prompt", "A clip that never finishes",
                        "seconds", 5))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        JobView finalView = pollJob(u.bearer(), ack.jobId());
        assertThat(finalView).isNotNull();
        assertThat(finalView.status()).isEqualTo(JobStatus.FAILED);

        // Upstream failure refunds the pre-debit — balance returns to the full seed.
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(1000_00L);
    }

    // ===== Helpers ====================================================

    private JobView pollJob(String bearer, UUID jobId) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 8_000;
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
            Thread.sleep(150);
        }
        return view;
    }

    private static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        StringBuilder sb = new StringBuilder(h.length * 2);
        for (byte b : h) sb.append(String.format("%02x", b));
        return sb.toString();
    }
}
