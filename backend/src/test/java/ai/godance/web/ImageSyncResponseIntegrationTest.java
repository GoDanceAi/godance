package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
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
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.ImageSyncService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.JobView;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * Image responses in OpenAI's shape (round 10 brief 04). An API key set to
 * {@code sync} has its image requests wait for the job and answer as
 * OpenAI's API does, so standard SDK code works unchanged. Every other
 * request, and every key left on {@code async}, behaves as before.
 *
 * <p>The provider is WireMock, as in {@link ImageGenerationIntegrationTest}.
 * The wait polls every 50 ms here so the suite stays fast; the default is
 * one second.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.images.sync-poll-interval=50ms"})
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
class ImageSyncResponseIntegrationTest {

    /** A 1×1 transparent PNG: what the upstream returns, and what is stored. */
    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

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
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired UsageRecordRepository usage;
    @Autowired AuditLogEntryRepository audit;
    @Autowired MeterRegistry meters;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        volcMock.resetAll();
    }

    // ===== A sync key: done ===========================================

    @Test
    void generate_givenSyncKey_expects200WithDataUrl() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        // Exactly what OpenAI SDK code sends: no Godance field in the body.
        http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_JSON)
                .expectBody()
                .jsonPath("$.created").isNumber()
                .jsonPath("$.data.length()").isEqualTo(1)
                .jsonPath("$.data[0].url").value(url -> assertThat((String) url).startsWith(MINIO.getS3URL()))
                .jsonPath("$.job_id").exists()
                // seedream-5.0: 35 000 µ¢ × 1.12 = 39 200 µ¢ → 4¢.
                .jsonPath("$.cost_cents").isEqualTo(4);

        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - 4L);
    }

    @Test
    void generate_givenSyncKeyAndTwoImages_expectsBothInOrderAndTheirCost() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(openAiMock, "/v1/images/generations", 2, 0);

        http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "Two boats", "n", 2, "response_format", "url"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data.length()").isEqualTo(2)
                .jsonPath("$.data[0].url").value(url -> assertThat((String) url).contains("/0.png"))
                .jsonPath("$.data[1].url").value(url -> assertThat((String) url).contains("/1.png"))
                .jsonPath("$.data[0].revised_prompt").isEqualTo("revised")
                // gpt-image-2: 53 000 µ¢ × 2 × 1.12 = 118 720 µ¢ → 12¢.
                .jsonPath("$.cost_cents").isEqualTo(12);

        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - 12L);
    }

    @Test
    void generate_givenSyncKey_expectsTheJobReadableAndRecordedAsAnyOther() throws Exception {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        Map<?, ?> body = http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, key)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        // The same job, pre-debit, usage row and outputs an async request makes.
        JobView job = awaitTerminal(UUID.fromString((String) body.get("job_id")), u.bearer());
        assertThat(job.status()).isEqualTo(JobStatus.DONE);
        assertThat(job.actualCostCents()).isEqualTo(4L);
        assertThat(job.outputs()).hasSize(1);
        assertThat(usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(), java.time.Instant.now().minusSeconds(60)))
                .singleElement().satisfies(r -> assertThat(r.getCostCents()).isEqualTo(4L));
        // The seed, and one pre-debit: nothing else on the ledger.
        assertThat(ledgerRowCount(u.id())).isEqualTo(2);
    }

    @Test
    void generate_givenSyncKey_expectsTheWaitCountedAsDone() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);
        double before = waits("done");

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond")).expectStatus().isOk();

        assertThat(waits("done")).isEqualTo(before + 1);
    }

    // ===== A sync key: failed =========================================

    @Test
    void generate_givenSyncKeyAndUpstreamFailure_expects502GenerationFailedAndTheChargeReturned() throws Exception {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"error\":{\"message\":\"upstream boom\"}}")));
        double before = waits("failed");

        Map<?, ?> problem = postImage(key, Map.of("model", "gpt-image-2", "prompt", "A doomed render"))
                .expectStatus().isEqualTo(502)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody(Map.class).returnResult().getResponseBody();

        assertThat(problem.get("code")).isEqualTo("generation_failed");
        assertThat((String) problem.get("detail")).startsWith("upstream:");
        JobView job = awaitTerminal(UUID.fromString((String) problem.get("job_id")), u.bearer());
        assertThat(job.status()).isEqualTo(JobStatus.FAILED);
        assertThat(job.errorMessage()).isEqualTo(problem.get("detail"));
        // The settlement's refund, unchanged: pre-debit and refund, back to the full seed.
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L);
        assertThat(ledgerRowCount(u.id())).isEqualTo(3);
        assertThat(waits("failed")).isEqualTo(before + 1);
    }

    // ===== What does not change =======================================

    @Test
    void generate_givenAKeyNobodyChanged_expectsThe202OfTheParentCommit() throws Exception {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, null);           // names nothing: the server's default, async
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);
        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[0].image_response").isEqualTo("async");
        double waitsBefore = allWaits();

        Map<?, ?> ack = postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .expectStatus().isAccepted()
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody(Map.class).returnResult().getResponseBody();

        // Exactly the three fields it always had.
        assertThat(ack.keySet()).map(Object::toString).containsExactlyInAnyOrder("job_id", "status", "poll_url");
        assertThat(ack.get("status")).isEqualTo("QUEUED");
        assertThat(ack.get("poll_url")).isEqualTo("/v1/jobs/" + ack.get("job_id"));
        assertThat(awaitTerminal(UUID.fromString((String) ack.get("job_id")), u.bearer()).status())
                .isEqualTo(JobStatus.DONE);
        assertThat(allWaits()).isEqualTo(waitsBefore);   // nothing waited
    }

    @Test
    void generate_givenAKeySetToAsync_expects202() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "async");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond", "response_format", "b64_json"))
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("QUEUED").jsonPath("$.data").doesNotExist();
    }

    @Test
    void generate_givenASessionOfAnAccountWithASyncKey_expects202() {
        Signed u = signupWithBalance(100_00L);
        createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        // The Studio's request, and the same without the flag: a session never waits.
        postImage(u.bearer(), Map.of("model", "seedream-5.0", "prompt", "A koi pond", "async", true))
                .expectStatus().isAccepted()
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER);
        postImage(u.bearer(), Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .expectStatus().isAccepted()
                .expectBody().jsonPath("$.status").isEqualTo("QUEUED");
    }

    @Test
    void generate_givenSyncKeyAndAsyncTrue_expects202() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond", "async", true))
                .expectStatus().isAccepted()
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.status").isEqualTo("QUEUED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"respond-async", "RESPOND-ASYNC", "respond-async, wait=10", "wait=10; respond-async"})
    void generate_givenSyncKeyAndPreferRespondAsync_expects202(String prefer) {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, key)
                .header("Prefer", prefer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .exchange()
                .expectStatus().isAccepted()
                .expectHeader().doesNotExist(HttpHeaders.RETRY_AFTER)
                .expectBody().jsonPath("$.status").isEqualTo("QUEUED");
    }

    @Test
    void generate_givenSyncKeyAndAnotherPreference_expectsItToWait() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, key)
                .header("Prefer", "return=minimal")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .exchange()
                .expectStatus().isOk();
    }

    @Test
    void generate_givenSyncKeyAndNoBalance_expects402AsBefore() {
        Signed u = signupWithBalance(0L);
        String key = createKey(u, "sync");

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .expectStatus().isEqualTo(402);

        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
        assertThat(volcMock.getAllServeEvents()).isEmpty();
    }

    // ===== response_format ============================================

    @Test
    void generate_givenB64JsonRequested_expectsTheImageItselfAndNoLink() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond", "response_format", "b64_json"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].b64_json").isEqualTo(Base64.getEncoder().encodeToString(TINY_PNG))
                .jsonPath("$.data[0].url").doesNotExist();
    }

    @Test
    void generate_givenNoFormatOnAGptImageModel_expectsB64JsonAsOpenAiDoesAndALink() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(openAiMock, "/v1/images/generations", 1, 0);

        // OpenAI answers its GPT Image models as base64; the link is there too,
        // so code that reads data[0].url works on the same response.
        postImage(key, Map.of("model", "gpt-image-2", "prompt", "A cat on a chair"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].b64_json").isEqualTo(Base64.getEncoder().encodeToString(TINY_PNG))
                .jsonPath("$.data[0].url").value(url -> assertThat((String) url).startsWith(MINIO.getS3URL()));
    }

    @Test
    void generate_givenNoFormatOnAnotherFamily_expectsALinkOnly() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].url").exists()
                .jsonPath("$.data[0].b64_json").doesNotExist();
    }

    @Test
    void generate_givenUrlRequestedOnAGptImageModel_expectsALinkOnly() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");
        stubImages(openAiMock, "/v1/images/generations", 1, 0);

        postImage(key, Map.of("model", "gpt-image-2", "prompt", "A cat on a chair", "response_format", "url"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.data[0].url").exists()
                .jsonPath("$.data[0].b64_json").doesNotExist();
    }

    @Test
    void generate_givenAnUnknownResponseFormat_expects400AndNoLedgerRow() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "sync");

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond", "response_format", "png"))
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        assertThat(ledgerRowCount(u.id())).isEqualTo(1);    // the seed only
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
        assertThat(volcMock.getAllServeEvents()).isEmpty();
    }

    // ===== The setting on each key ====================================

    @Test
    void createKey_givenImageResponse_expectsItOnTheKeyAndInTheList() {
        Signed u = signupWithBalance(0L);

        http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "sdk", "image_response", "sync"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.image_response").isEqualTo("sync");

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].image_response").isEqualTo("sync");
    }

    @Test
    void createKey_givenAnImageResponseThatIsNeither_expects400AndNoKey() {
        Signed u = signupWithBalance(0L);

        http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "sdk", "image_response", "blocking"))
                .exchange()
                .expectStatus().isBadRequest();

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(0);
    }

    @Test
    void updateKey_givenItsOwner_expectsTheSummaryAnAuditRowAndTheKeyNowWaits() {
        Signed u = signupWithBalance(100_00L);
        String key = createKey(u, "async");
        String keyId = onlyKeyId(u);
        stubImages(volcMock, "/api/v3/images/generations", 1, 0);
        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond")).expectStatus().isAccepted();

        patchKey(u.bearer(), keyId, Map.of("image_response", "sync"))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.id").isEqualTo(keyId)
                .jsonPath("$.name").isEqualTo("sync-test-key")
                .jsonPath("$.image_response").isEqualTo("sync")
                .jsonPath("$.plaintext").doesNotExist();

        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond")).expectStatus().isOk();
        assertThat(audit.findByEventType(AuditLogService.EVT_API_KEY_UPDATED).stream()
                .filter(row -> u.id().equals(row.getUserId())))
                .singleElement().satisfies(row -> assertThat(row.getEventDetail())
                        .containsEntry("apiKeyId", keyId)
                        .containsEntry("field", "image_response")
                        .containsEntry("value", "sync"));

        // And back: the key answers with a job again. Setting what it already is writes no row.
        patchKey(u.bearer(), keyId, Map.of("image_response", "async")).expectStatus().isOk();
        patchKey(u.bearer(), keyId, Map.of("image_response", "async")).expectStatus().isOk();
        postImage(key, Map.of("model", "seedream-5.0", "prompt", "A koi pond")).expectStatus().isAccepted();
        assertThat(audit.findByEventType(AuditLogService.EVT_API_KEY_UPDATED).stream()
                .filter(row -> u.id().equals(row.getUserId()))).hasSize(2);
    }

    @Test
    void updateKey_givenAnotherUsersKey_expects404AndNothingChanged() {
        Signed owner = signupWithBalance(0L);
        createKey(owner, "async");
        String keyId = onlyKeyId(owner);
        Signed other = signupWithBalance(0L);

        patchKey(other.bearer(), keyId, Map.of("image_response", "sync")).expectStatus().isNotFound();
        patchKey(other.bearer(), UUID.randomUUID().toString(), Map.of("image_response", "sync"))
                .expectStatus().isNotFound();

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, owner.bearer()).exchange()
                .expectBody().jsonPath("$[0].image_response").isEqualTo("async");
    }

    @Test
    void updateKey_givenAnApiKey_expects403AndNothingChanged() {
        Signed u = signupWithBalance(0L);
        String key = createKey(u, "async");
        String keyId = onlyKeyId(u);

        // A key cannot change itself: the key routes take a signed-in session only.
        patchKey(key, keyId, Map.of("image_response", "sync"))
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody().jsonPath("$[0].image_response").isEqualTo("async");
    }

    @Test
    void updateKey_givenNoSession_expects401() {
        http.patch().uri("/v1/keys/{id}", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("image_response", "sync"))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @ParameterizedTest
    @ValueSource(strings = {"{}", "{\"image_response\":null}", "{\"image_response\":\"blocking\"}",
            "{\"image_response\":\"\"}", "{\"name\":\"renamed\"}"})
    void updateKey_givenNoValidImageResponse_expects400AndNothingChanged(String body) {
        Signed u = signupWithBalance(0L);
        createKey(u, "sync");
        String keyId = onlyKeyId(u);

        http.patch().uri("/v1/keys/{id}", keyId)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isBadRequest();

        http.get().uri("/v1/keys").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody()
                .jsonPath("$[0].image_response").isEqualTo("sync")
                .jsonPath("$[0].name").isEqualTo("sync-test-key");
    }

    @Test
    void updateKey_givenUpperCase_expectsItAccepted() {
        Signed u = signupWithBalance(0L);
        createKey(u, "async");

        patchKey(u.bearer(), onlyKeyId(u), Map.of("image_response", "SYNC"))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.image_response").isEqualTo("sync");
    }

    // ===== Helpers ====================================================

    record Signed(UUID id, String bearer) { }

    Signed signupWithBalance(long cents) {
        String email = "sync-" + UUID.randomUUID() + "@godance.test";
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
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    /** A new API key with this image response setting (null: the server's default). Returns its bearer. */
    String createKey(Signed u, String imageResponse) {
        Map<String, Object> body = new HashMap<>();
        body.put("name", "sync-test-key");
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

    /** {@code n} images as b64_json from an OpenAI-shaped endpoint, after {@code delayMs}. */
    static void stubImages(WireMockServer mock, String path, int n, int delayMs) {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        String one = "{\"b64_json\":\"" + b64 + "\",\"revised_prompt\":\"revised\"}";
        String data = String.join(",", java.util.Collections.nCopies(n, one));
        mock.stubFor(post(urlEqualTo(path))
                .willReturn(aResponse().withStatus(200)
                        .withFixedDelay(delayMs)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[" + data + "]}")));
    }

    WebTestClient.ResponseSpec postImage(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    WebTestClient.ResponseSpec patchKey(String bearer, String keyId, Map<String, Object> body) {
        return http.patch().uri("/v1/keys/{id}", keyId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    /** The id of the one key the account has. */
    String onlyKeyId(Signed u) {
        List<?> keys = http.get().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody(List.class).returnResult().getResponseBody();
        assertThat(keys).hasSize(1);
        return (String) ((Map<?, ?>) keys.get(0)).get("id");
    }

    /** How many waits have ended this way since the application started. */
    double waits(String outcome) {
        return meters.counter(ImageSyncService.WAITS_METER, "outcome", outcome).count();
    }

    double allWaits() {
        return waits("done") + waits("failed") + waits("timeout") + waits("cancelled");
    }

    JobView awaitTerminal(UUID jobId, String bearer) throws InterruptedException {
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

    long ledgerRowCount(UUID userId) {
        return ledgerRows.findByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, 50)).getTotalElements();
    }
}
