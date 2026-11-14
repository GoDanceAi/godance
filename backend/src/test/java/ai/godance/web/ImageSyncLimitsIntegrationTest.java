package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.ImageSyncService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.JobView;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * The limits of a waiting image request (round 10 brief 04), with each set
 * small: a one-second wait, ten bytes of inline image data, and new keys
 * that wait by default.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.images.new-key-response=sync",
        "godance.images.sync-wait=1s",
        "godance.images.sync-poll-interval=50ms",
        "godance.images.max-inline-bytes=10B"})
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
class ImageSyncLimitsIntegrationTest {

    private static final byte[] TINY_PNG = ImageSyncResponseIntegrationTest.TINY_PNG;   // 67 bytes

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
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
    @Autowired MeterRegistry meters;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    // ===== Still running at the limit =================================

    @Test
    void generate_givenAJobSlowerThanTheWait_expects202WithRetryAfterAndTheJobFinishingNormally() throws Exception {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImage(2_500);                     // the wait is one second
        double before = waits("timeout");

        long started = System.currentTimeMillis();
        Map<?, ?> ack = postImage(key, Map.of("model", "gpt-image-2", "prompt", "A slow render"))
                .expectStatus().isAccepted()
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "5")
                .expectBody(Map.class).returnResult().getResponseBody();
        long waited = System.currentTimeMillis() - started;

        // Today's job body, answered at the limit and not when the job ended.
        assertThat(ack.keySet()).map(Object::toString).containsExactlyInAnyOrder("job_id", "status", "poll_url");
        assertThat(ack.get("status")).isIn("QUEUED", "RUNNING");
        assertThat(ack.get("poll_url")).isEqualTo("/v1/jobs/" + ack.get("job_id"));
        assertThat(waited).isBetween(900L, 2_300L);
        assertThat(waits("timeout")).isEqualTo(before + 1);

        // The job carries on: polling works as for any async job, and it is charged once.
        JobView done = awaitTerminal(UUID.fromString((String) ack.get("job_id")), u.bearer());
        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.outputs()).hasSize(1);
        assertThat(done.actualCostCents()).isEqualTo(6L);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - 6L);
    }

    // ===== Above the inline limit =====================================

    @Test
    void generate_givenB64JsonAboveTheInlineLimit_expectsLinksInstead() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImage(0);

        postImage(key, Map.of("model", "gpt-image-2", "prompt", "A cat", "response_format", "b64_json"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].url").value(url -> assertThat((String) url).startsWith(MINIO.getS3URL()))
                .jsonPath("$.data[0].b64_json").doesNotExist();
    }

    @Test
    void generate_givenTheDefaultB64JsonAboveTheInlineLimit_expectsLinksOnly() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImage(0);

        postImage(key, Map.of("model", "gpt-image-2", "prompt", "A cat"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].url").exists()
                .jsonPath("$.data[0].b64_json").doesNotExist();
    }

    // ===== The default for new keys ===================================

    @Test
    void createKey_givenNoImageResponse_expectsTheConfiguredDefault() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, null);
        stubImage(0);

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].image_response").isEqualTo("sync");
        postImage(key, Map.of("model", "gpt-image-2", "prompt", "A cat")).expectStatus().isOk();
    }

    @Test
    void createKey_givenAsyncNamed_expectsAsyncWhateverTheDefault() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "async");
        stubImage(0);

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody().jsonPath("$[0].image_response").isEqualTo("async");
        postImage(key, Map.of("model", "gpt-image-2", "prompt", "A cat")).expectStatus().isAccepted();
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "sync-limit-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Sync Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private String createKey(Signed u, String imageResponse) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "limits-key");
        if (imageResponse != null) body.put("image_response", imageResponse);
        Map<?, ?> created = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return "Bearer " + created.get("plaintext");
    }

    private WebTestClient.ResponseSpec postImage(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static void stubImage(int delayMs) {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(delayMs)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }

    private double waits(String outcome) {
        return meters.counter(ImageSyncService.WAITS_METER, "outcome", outcome).count();
    }

    private JobView awaitTerminal(UUID jobId, String bearer) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 15_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) {
                return view;
            }
            Thread.sleep(50);
        }
        return view;
    }
}
