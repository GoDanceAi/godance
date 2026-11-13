package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
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
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionService;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobOutputView;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * Reading a job back in full (round 7): every output with a link signed at
 * read time, the prompt and parameters, the purge state and deletion date,
 * outputs of jobs finished before V23 found in storage, and the paged list.
 *
 * <p>The presign TTL is a few seconds here, so a link handed out when the job
 * finished has expired by the time a later read is checked.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class JobReadIntegrationTest {

    private static final Duration PRESIGN_TTL = Duration.ofSeconds(4);

    /** A 1×1 transparent PNG — what the upstream stub returns. */
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
        r.add("godance.providers.openai.api-base",       () -> openAiMock.baseUrl());
        r.add("godance.object-storage.endpoint",         MINIO::getS3URL);
        r.add("godance.object-storage.access-key",       MINIO::getUserName);
        r.add("godance.object-storage.secret-key",       MINIO::getPassword);
        r.add("godance.object-storage.bucket",           () -> "godance-job-read-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
        r.add("godance.object-storage.presign-ttl",      () -> PRESIGN_TTL.toSeconds() + "s");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobOutputRepository outputRows;
    @Autowired DataRetentionService retention;
    @Autowired ObjectStorageClient storage;

    private final HttpClient download = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    // ===== Every output, signed when read =============================

    @Test
    void getJob_givenTwoImagesDelivered_expectsTwoOutputsThatEachDownload() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImages(2);

        UUID jobId = submitImage(u.bearer(), Map.of("model", "gpt-image-2", "prompt", "a lighthouse at dusk",
                "size", "1024x1024", "n", 2));
        JobView done = pollUntilTerminal(jobId, u.bearer());

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.prompt()).isEqualTo("a lighthouse at dusk");
        assertThat(done.params()).isEqualTo(Map.of("size", "1024x1024", "n", 2));
        assertThat(done.purged()).isFalse();
        assertThat(done.mediaExpiresAt()).isEqualTo(done.createdAt().plus(DataRetentionService.JOB_RETENTION));
        assertThat(done.outputs()).extracting(JobOutputView::index).containsExactly(0, 1);
        assertThat(done.outputUrl()).isEqualTo(done.outputs().get(0).url());
        for (JobOutputView o : done.outputs()) {
            assertThat(o.contentType()).isEqualTo("image/png");
            assertThat(o.sizeBytes()).isEqualTo(TINY_PNG.length);
            assertThat(o.revisedPrompt()).isEqualTo("revised " + o.index());
            HttpResponse<byte[]> file = get(o.url());
            assertThat(file.statusCode()).isEqualTo(200);
            assertThat(file.body()).isEqualTo(TINY_PNG);
        }

        // The list returns the same job with the same outputs.
        List<JobView> listed = list(u.bearer(), "");
        assertThat(listed).singleElement().satisfies(j -> {
            assertThat(j.id()).isEqualTo(jobId);
            assertThat(j.outputs()).hasSize(2);
            assertThat(j.prompt()).isEqualTo("a lighthouse at dusk");
        });
    }

    @Test
    void getJob_givenReadAfterTheOriginalLinkExpired_expectsAFreshWorkingLink() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImages(1);
        UUID jobId = submitImage(u.bearer(), Map.of("model", "gpt-image-2", "prompt", "an old link", "n", 1));
        assertThat(pollUntilTerminal(jobId, u.bearer()).status()).isEqualTo(JobStatus.DONE);
        String linkFromCompletion = jobs.findById(jobId).orElseThrow().getOutputUrl();

        Thread.sleep(PRESIGN_TTL.plusMillis(1_500).toMillis());

        assertThat(get(linkFromCompletion).statusCode()).isEqualTo(403);   // expired
        JobView later = getJob(jobId, u.bearer());
        assertThat(later.outputUrl()).isNotEqualTo(linkFromCompletion);
        HttpResponse<byte[]> file = get(later.outputUrl());
        assertThat(file.statusCode()).isEqualTo(200);
        assertThat(file.body()).isEqualTo(TINY_PNG);
        assertThat(get(later.outputs().get(0).url()).statusCode()).isEqualTo(200);
    }

    @Test
    void getJob_givenPurgedJob_expectsPurgedTrueNoPromptNoOutputs() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImages(2);
        UUID jobId = submitImage(u.bearer(), Map.of("model", "gpt-image-2", "prompt", "soon gone", "n", 2));
        assertThat(pollUntilTerminal(jobId, u.bearer()).outputs()).hasSize(2);

        assertThat(retention.purgeJob(jobId, Instant.now())).isTrue();

        JobView purged = getJob(jobId, u.bearer());
        assertThat(purged.purged()).isTrue();
        assertThat(purged.prompt()).isNull();
        assertThat(purged.params()).isEmpty();
        assertThat(purged.outputs()).isEmpty();
        assertThat(purged.outputUrl()).isNull();
        assertThat(purged.mediaExpiresAt()).isNull();
        // Billing fields stay.
        assertThat(purged.status()).isEqualTo(JobStatus.DONE);
        assertThat(purged.actualCostCents()).isPositive();
        assertThat(outputRows.findByJobIdOrderByOutputIndexAsc(jobId)).isEmpty();
        assertThat(storage.list("images/" + jobId + "/")).isEmpty();
    }

    @Test
    void getJob_givenVideoJob_expectsRequestFieldNamesInParams() {
        Signed u = signupWithBalance(0L);
        GenerationJob video = saveJob(u.id(), Modality.VIDEO, JobStatus.QUEUED, Instant.now(),
                Map.of("prompt", "a tiger surfs", "duration_seconds", 10, "size", "1280x720"));

        JobView view = getJob(video.getId(), u.bearer());

        assertThat(view.prompt()).isEqualTo("a tiger surfs");
        assertThat(view.params()).isEqualTo(Map.of("size", "1280x720", "seconds", 10));
        assertThat(view.outputs()).isEmpty();
        assertThat(view.outputUrl()).isNull();
        assertThat(view.purged()).isFalse();
    }

    @Test
    void getJob_otherUsersJob_returns404() {
        Signed owner = signupWithBalance(0L);
        GenerationJob job = saveJob(owner.id(), Modality.IMAGE, JobStatus.DONE, Instant.now(), Map.of("prompt", "mine"));
        Signed other = signupWithBalance(0L);

        http.get().uri("/v1/jobs/{id}", job.getId())
                .header("Authorization", other.bearer())
                .exchange()
                .expectStatus().isNotFound();
    }

    // ===== Jobs finished before V23 ====================================

    @Test
    void getJob_givenJobFinishedBeforeV23_expectsOutputsFoundInStorageAndRecordedOnce() throws Exception {
        Signed u = signupWithBalance(0L);
        GenerationJob legacy = saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, Instant.now().minus(Duration.ofDays(3)),
                Map.of("prompt", "from before outputs were recorded", "n", 2));
        String prefix = "images/" + legacy.getId() + "/";
        storage.upload(prefix + "0.png", TINY_PNG, "image/png");
        storage.upload(prefix + "1.jpg", new byte[] {1, 2, 3, 4}, "image/jpeg");
        assertThat(outputRows.findByJobIdOrderByOutputIndexAsc(legacy.getId())).isEmpty();

        JobView first = getJob(legacy.getId(), u.bearer());

        assertThat(first.outputs()).extracting(JobOutputView::index, JobOutputView::contentType, JobOutputView::sizeBytes)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(0, "image/png", (long) TINY_PNG.length),
                        org.assertj.core.groups.Tuple.tuple(1, "image/jpeg", 4L));
        assertThat(get(first.outputs().get(1).url()).statusCode()).isEqualTo(200);
        assertThat(first.outputUrl()).isEqualTo(first.outputs().get(0).url());
        assertThat(outputRows.findByJobIdOrderByOutputIndexAsc(legacy.getId())).hasSize(2);

        // Later reads use the rows; nothing is recorded twice.
        assertThat(list(u.bearer(), "").get(0).outputs()).hasSize(2);
        assertThat(outputRows.findByJobIdOrderByOutputIndexAsc(legacy.getId())).hasSize(2);
    }

    @Test
    void getJob_givenJobFinishedBeforeV23WithNothingInStorage_expectsNoOutputsAndTheStoredLink() {
        Signed u = signupWithBalance(0L);
        GenerationJob legacy = saveJob(u.id(), Modality.VIDEO, JobStatus.DONE, Instant.now(),
                Map.of("prompt", "files already gone"));

        JobView view = getJob(legacy.getId(), u.bearer());

        assertThat(view.outputs()).isEmpty();
        assertThat(view.outputUrl()).isEqualTo("https://storage.invalid/stored-at-completion");
    }

    // ===== Paged and filtered list ====================================

    @Test
    void list_givenLimitAndBefore_expectsKeysetPagesNewestFirst() {
        Signed u = signupWithBalance(0L);
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(1));
        List<GenerationJob> made = new ArrayList<>();
        made.add(saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, t, Map.of("prompt", "0")));
        made.add(saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, t.plusSeconds(1), Map.of("prompt", "1")));
        // Two jobs created at the same instant: the id breaks the tie.
        made.add(saveJob(u.id(), Modality.VIDEO, JobStatus.FAILED, t.plusSeconds(2), Map.of("prompt", "2a")));
        made.add(saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, t.plusSeconds(2), Map.of("prompt", "2b")));
        made.add(saveJob(u.id(), Modality.VIDEO, JobStatus.DONE, t.plusSeconds(3), Map.of("prompt", "3")));
        Signed other = signupWithBalance(0L);
        saveJob(other.id(), Modality.IMAGE, JobStatus.DONE, t.plusSeconds(4), Map.of("prompt", "not yours"));

        // Postgres orders uuids byte by byte, which is how their strings compare.
        List<UUID> newestFirst = made.stream()
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt)
                        .thenComparing(j -> j.getId().toString()).reversed())
                .map(GenerationJob::getId).toList();

        assertThat(ids(list(u.bearer(), ""))).containsExactlyElementsOf(newestFirst);
        assertThat(ids(list(u.bearer(), "?limit=2"))).containsExactlyElementsOf(newestFirst.subList(0, 2));
        assertThat(ids(list(u.bearer(), "?limit=2&before=" + newestFirst.get(1))))
                .containsExactlyElementsOf(newestFirst.subList(2, 4));
        assertThat(ids(list(u.bearer(), "?limit=2&before=" + newestFirst.get(3))))
                .containsExactlyElementsOf(newestFirst.subList(4, 5));
        assertThat(list(u.bearer(), "?limit=2&before=" + newestFirst.get(4))).isEmpty();
        // before without limit: everything older.
        assertThat(ids(list(u.bearer(), "?before=" + newestFirst.get(0))))
                .containsExactlyElementsOf(newestFirst.subList(1, 5));
    }

    @Test
    void list_givenModalityAndStatusFilters_expectsOnlyMatchingJobs() {
        Signed u = signupWithBalance(0L);
        Instant t = Instant.now().minus(Duration.ofHours(1));
        GenerationJob image = saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, t, Map.of("prompt", "i"));
        GenerationJob failedVideo = saveJob(u.id(), Modality.VIDEO, JobStatus.FAILED, t.plusSeconds(1), Map.of("prompt", "v1"));
        GenerationJob doneVideo = saveJob(u.id(), Modality.VIDEO, JobStatus.DONE, t.plusSeconds(2), Map.of("prompt", "v2"));

        assertThat(ids(list(u.bearer(), "?modality=VIDEO"))).containsExactly(doneVideo.getId(), failedVideo.getId());
        assertThat(ids(list(u.bearer(), "?modality=image"))).containsExactly(image.getId());
        assertThat(ids(list(u.bearer(), "?status=DONE"))).containsExactly(doneVideo.getId(), image.getId());
        assertThat(ids(list(u.bearer(), "?modality=VIDEO&status=FAILED&limit=5"))).containsExactly(failedVideo.getId());
        assertThat(ids(list(u.bearer(), "?modality=VIDEO&limit=1&before=" + doneVideo.getId())))
                .containsExactly(failedVideo.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?limit=0", "?limit=101", "?limit=ten", "?before=not-a-uuid",
            "?modality=TEXT", "?status=FINISHED"})
    void list_givenInvalidParameter_expects400ProblemJson(String query) {
        Signed u = signupWithBalance(0L);
        http.get().uri("/v1/jobs" + query)
                .header("Authorization", u.bearer())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    @Test
    void list_givenBeforeThatIsNotTheCallersJob_expects400() {
        Signed u = signupWithBalance(0L);
        Signed other = signupWithBalance(0L);
        GenerationJob theirs = saveJob(other.id(), Modality.IMAGE, JobStatus.DONE, Instant.now(), Map.of("prompt", "x"));

        for (UUID before : List.of(theirs.getId(), UUID.randomUUID())) {
            http.get().uri("/v1/jobs?limit=10&before=" + before)
                    .header("Authorization", u.bearer())
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "jobread-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Job Reader"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    /** An OpenAI-shaped answer with {@code count} images, each with its own revised prompt. */
    private static void stubImages(int count) {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        StringBuilder data = new StringBuilder();
        for (int i = 0; i < count; i++) {
            if (i > 0) data.append(',');
            data.append("{\"b64_json\":\"").append(b64).append("\",\"revised_prompt\":\"revised ").append(i).append("\"}");
        }
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[" + data + "]}")));
    }

    private UUID submitImage(String bearer, Map<String, Object> body) {
        return http.post().uri("/v1/images/generations")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody().jobId();
    }

    private JobView getJob(UUID jobId, String bearer) {
        return http.get().uri("/v1/jobs/{id}", jobId)
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(JobView.class).returnResult().getResponseBody();
    }

    private List<JobView> list(String bearer, String query) {
        return http.get().uri("/v1/jobs" + query)
                .header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(JobView.class).returnResult().getResponseBody();
    }

    private static List<UUID> ids(List<JobView> views) {
        return views.stream().map(JobView::id).toList();
    }

    private JobView pollUntilTerminal(UUID jobId, String bearer) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 8_000;
        JobView view = null;
        while (System.currentTimeMillis() < deadline) {
            view = getJob(jobId, bearer);
            if (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED) return view;
            Thread.sleep(100);
        }
        return view;
    }

    private HttpResponse<byte[]> get(String url) throws Exception {
        return download.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    /** A job row written directly, as an older release or another test state would have left it. */
    private GenerationJob saveJob(UUID userId, Modality modality, JobStatus status, Instant createdAt,
                                  Map<String, Object> prompt) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(modality);
        job.setProvider(modality == Modality.VIDEO ? Provider.OPENAI : Provider.OPENAI);
        job.setModel(modality == Modality.VIDEO ? "sora-2" : "gpt-image-2");
        job.setPrompt(prompt);
        job.setStatus(status);
        job.setEstimatedCostCents(5L);
        if (status == JobStatus.DONE) {
            job.setActualCostCents(5L);
            job.setOutputUrl("https://storage.invalid/stored-at-completion");
            job.setCompletedAt(createdAt.plusSeconds(1));
        }
        job.setCreatedAt(createdAt);
        return jobs.save(job);
    }
}
