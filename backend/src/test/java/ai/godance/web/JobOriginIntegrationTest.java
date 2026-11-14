package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionService;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * Where a job came from (round 10 brief 01): the server records the
 * {@code channel} it was made through and the gallery recipe the request
 * named, and {@code GET /v1/jobs} shows both. Checked on the wire, by the
 * names the contract gives them.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class JobOriginIntegrationTest {

    /** A 1×1 transparent PNG: what the image upstream returns. */
    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    private static final String SLUG = "paper-lantern-street-at-dusk";

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
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired DataRetentionService retention;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    // ===== channel ====================================================

    @Test
    void generateImage_givenApiKey_expectsChannelApi() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();

        UUID jobId = submit("/v1/images/generations", apiKeyBearer(u), imageBody());

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer()).jsonPath("$.channel").isEqualTo("API");
    }

    @Test
    void generateImage_givenSession_expectsChannelStudio() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();

        UUID jobId = submit("/v1/images/generations", u.bearer(), imageBody());

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer()).jsonPath("$.channel").isEqualTo("STUDIO");
    }

    @Test
    void generateVideo_givenApiKey_expectsChannelApi() throws Exception {
        Signed u = signupWithBalance(1000_00L);
        stubVideo();

        UUID jobId = submit("/v1/videos/generations", apiKeyBearer(u), videoBody());

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer()).jsonPath("$.channel").isEqualTo("API");
    }

    @Test
    void generateVideo_givenSession_expectsChannelStudio() throws Exception {
        Signed u = signupWithBalance(1000_00L);
        stubVideo();

        UUID jobId = submit("/v1/videos/generations", u.bearer(), videoBody());

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer()).jsonPath("$.channel").isEqualTo("STUDIO");
    }

    @Test
    void generateImage_givenChannelInTheBody_expectsItIgnored() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Map<String, Object> body = imageBody();
        body.put("channel", "API");

        UUID jobId = submit("/v1/images/generations", u.bearer(), body);

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer()).jsonPath("$.channel").isEqualTo("STUDIO");
    }

    @Test
    void listJobs_givenJobsOfBothChannels_expectsEachWithItsOwn() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        UUID viaKey = submit("/v1/images/generations", apiKeyBearer(u), imageBody());
        awaitDone(viaKey, u.bearer());
        UUID viaSession = submit("/v1/images/generations", u.bearer(), imageBody());
        awaitDone(viaSession, u.bearer());

        http.get().uri("/v1/jobs")
                .header("Authorization", u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.id == '" + viaKey + "')].channel").isEqualTo("API")
                .jsonPath("$[?(@.id == '" + viaSession + "')].channel").isEqualTo("STUDIO");
    }

    @Test
    void readJob_givenARowFromBeforeV28_expectsChannelNull() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        UUID jobId = submit("/v1/images/generations", u.bearer(), imageBody());
        awaitDone(jobId, u.bearer());
        clearOrigin(jobId);

        readJob(jobId, u.bearer())
                .jsonPath("$.channel").value(v -> assertThat(v).isNull())
                .jsonPath("$.galleryRecipe").value(v -> assertThat(v).isNull());
    }

    // ===== gallery_recipe =============================================

    @Test
    void generateImage_givenGalleryRecipe_expectsItOnTheJob() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Map<String, Object> body = imageBody();
        body.put("gallery_recipe", SLUG);

        UUID jobId = submit("/v1/images/generations", u.bearer(), body);

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer())
                .jsonPath("$.galleryRecipe").isEqualTo(SLUG)
                .jsonPath("$.channel").isEqualTo("STUDIO");
    }

    @Test
    void generateVideo_givenGalleryRecipe_expectsItOnTheJob() throws Exception {
        Signed u = signupWithBalance(1000_00L);
        stubVideo();
        Map<String, Object> body = videoBody();
        body.put("gallery_recipe", SLUG);

        UUID jobId = submit("/v1/videos/generations", apiKeyBearer(u), body);

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer())
                .jsonPath("$.galleryRecipe").isEqualTo(SLUG)
                .jsonPath("$.channel").isEqualTo("API");
    }

    @Test
    void generateImage_givenNoGalleryRecipe_expectsNull() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();

        UUID jobId = submit("/v1/images/generations", u.bearer(), imageBody());

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer()).jsonPath("$.galleryRecipe").value(v -> assertThat(v).isNull());
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "Paper-Lantern",                       // capitals
            "paper lantern",                       // a space
            "paper_lantern",                       // an underscore
            "../admin",
            "",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"   // 81 characters
    })
    void generateImage_givenMalformedGalleryRecipe_expects400AndNoLedgerRow(String slug) {
        Signed u = signupWithBalance(100_00L);
        Map<String, Object> body = imageBody();
        body.put("gallery_recipe", slug);

        http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("invalid_gallery_recipe");

        expectNothingCharged(u, 100_00L);
    }

    @Test
    void generateVideo_givenMalformedGalleryRecipe_expects400AndNoLedgerRow() {
        Signed u = signupWithBalance(1000_00L);
        Map<String, Object> body = videoBody();
        body.put("gallery_recipe", "Not A Slug");

        http.post().uri("/v1/videos/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo("invalid_gallery_recipe");

        expectNothingCharged(u, 1000_00L);
    }

    @Test
    void generateImage_givenGalleryRecipeOfTheWrongType_expects400AndNoLedgerRow() {
        Signed u = signupWithBalance(100_00L);
        Map<String, Object> body = imageBody();
        body.put("gallery_recipe", Map.of("slug", SLUG));

        http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();

        expectNothingCharged(u, 100_00L);
    }

    // ===== Purge ======================================================

    @Test
    void purge_givenAJobWithAnOrigin_expectsBothFieldsKept() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Map<String, Object> body = imageBody();
        body.put("gallery_recipe", SLUG);
        UUID jobId = submit("/v1/images/generations", apiKeyBearer(u), body);
        awaitDone(jobId, u.bearer());

        // What the nightly run does once the job is past the retention period.
        assertThat(retention.purgeJob(jobId, Instant.now())).isTrue();

        readJob(jobId, u.bearer())
                .jsonPath("$.purged").isEqualTo(true)
                .jsonPath("$.prompt").value(v -> assertThat(v).isNull())
                .jsonPath("$.channel").isEqualTo("API")
                .jsonPath("$.galleryRecipe").isEqualTo(SLUG);
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "origin-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Origin Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private String apiKeyBearer(Signed u) {
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "origin-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
        return "Bearer " + key.plaintext();
    }

    private static Map<String, Object> imageBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "gpt-image-2");
        body.put("prompt", "A paper lantern street at dusk");
        body.put("n", 1);
        return body;
    }

    private static Map<String, Object> videoBody() {
        Map<String, Object> body = new HashMap<>();
        body.put("model", "sora-2");
        body.put("prompt", "Koi circling under falling rain");
        body.put("seconds", 4);
        return body;
    }

    private UUID submit(String path, String bearer, Map<String, Object> body) {
        return http.post().uri(path)
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody().jobId();
    }

    private void awaitDone(UUID jobId, String bearer) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header("Authorization", bearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) break;
            Thread.sleep(100);
        }
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo(JobStatus.DONE);
    }

    private WebTestClient.BodyContentSpec readJob(UUID jobId, String bearer) {
        return http.get().uri("/v1/jobs/{id}", jobId)
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    /** Only the seed top-up is on the ledger, and no job exists: the request was refused before any charge. */
    private void expectNothingCharged(Signed u, long balanceCents) {
        assertThat(ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 10)).getTotalElements())
                .isEqualTo(1);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(balanceCents);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    /** Makes the job look like one from before V28, which recorded no origin. */
    private void clearOrigin(UUID jobId) {
        assertThat(jdbc.update("UPDATE generation_jobs SET channel = NULL, gallery_recipe = NULL WHERE id = ?",
                jobId)).isEqualTo(1);
    }

    private static void stubImage() {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }

    /** Submit, then the first poll already reports {@code completed}. */
    private static void stubVideo() {
        openAiMock.stubFor(post(urlEqualTo("/v1/videos"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_origin\",\"status\":\"queued\"}")));
        openAiMock.stubFor(get(urlPathMatching("/v1/videos/vid_origin"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_origin\",\"status\":\"completed\"}")));
        openAiMock.stubFor(get(urlEqualTo("/v1/videos/vid_origin/content"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody("FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8))));
    }
}
