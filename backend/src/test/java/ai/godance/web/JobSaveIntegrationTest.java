package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionService;
import ai.godance.service.JobSaveRefusedException;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * Saving a generation (round 9): {@code PUT} and
 * {@code DELETE /v1/jobs/{id}/saved}, the {@code saved} field and deletion
 * date a job reads back with, the limit per account, the saved filter on the
 * list, and the count against the limit.
 *
 * <p>The limit is 3 here, so a test can reach it with a handful of jobs.
 * What the nightly purge does with a saved job is in
 * {@code DataRetentionIntegrationTest}; closing an account is in
 * {@code AccountClosureIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK,
        properties = "godance.retention.saved-limit=" + JobSaveIntegrationTest.LIMIT)
@AutoConfigureWebTestClient
@Testcontainers
class JobSaveIntegrationTest {

    static final int LIMIT = 3;

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.object-storage.endpoint",          MINIO::getS3URL);
        r.add("godance.object-storage.access-key",        MINIO::getUserName);
        r.add("godance.object-storage.secret-key",        MINIO::getPassword);
        r.add("godance.object-storage.bucket",            () -> "godance-job-save-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledgerService;
    @Autowired CreditLedgerRepository ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobOutputRepository outputRows;
    @Autowired DataRetentionService retention;
    @Autowired ObjectStorageClient storage;
    @Autowired AuditLogEntryRepository audit;

    // ===== Saving =====================================================

    @Test
    void save_givenDoneJob_expects204AndTheJobReadsBackSavedWithNoDeletionDate() {
        Signed u = signupWithBalance(50_00L);
        GenerationJob job = doneJob(u.id(), Instant.now().minus(Duration.ofDays(10)));
        long balance = users.findById(u.id()).orElseThrow().getCreditBalanceCents();
        long ledgerSum = ledger.sumDeltaForUser(u.id());

        // Not saved: due for deletion 90 days after it was made.
        JobView before = getJob(job.getId(), u.bearer());
        assertThat(before.saved()).isFalse();
        assertThat(before.mediaExpiresAt()).isEqualTo(before.createdAt().plus(DataRetentionService.JOB_RETENTION));

        save(job.getId(), u.bearer()).expectStatus().isNoContent().expectBody().isEmpty();

        JobView after = getJob(job.getId(), u.bearer());
        assertThat(after.saved()).isTrue();
        assertThat(after.mediaExpiresAt()).isNull();
        // Nothing else about the job moves.
        assertThat(after.purged()).isFalse();
        assertThat(after.prompt()).isEqualTo("a lighthouse at dusk");
        assertThat(after.outputs()).hasSize(1);
        assertThat(after.actualCostCents()).isEqualTo(before.actualCostCents());
        assertThat(storage.list("images/" + job.getId() + "/")).hasSize(1);
        assertThat(list(u.bearer(), "")).singleElement().satisfies(v -> assertThat(v.saved()).isTrue());
        // Saving is free: the balance and the ledger are as they were.
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(balance);
        assertThat(ledger.sumDeltaForUser(u.id())).isEqualTo(ledgerSum);
        assertThat(auditRows(AuditLogService.EVT_JOB_SAVED, u.id())).singleElement().satisfies(row -> {
            assertThat(row.getEventDetail()).containsEntry("jobId", job.getId().toString());
            assertThat(number(row, "saved")).isEqualTo(1);
            assertThat(number(row, "limit")).isEqualTo(LIMIT);
        });
    }

    @Test
    void save_givenJobAlreadySaved_expects204AndChangesNothing() {
        Signed u = signupWithBalance(0L);
        GenerationJob job = doneJob(u.id(), Instant.now());
        save(job.getId(), u.bearer()).expectStatus().isNoContent();
        Instant savedAt = jobs.findById(job.getId()).orElseThrow().getSavedAt();

        save(job.getId(), u.bearer()).expectStatus().isNoContent();
        save(job.getId(), u.bearer()).expectStatus().isNoContent();

        assertThat(jobs.findById(job.getId()).orElseThrow().getSavedAt()).isEqualTo(savedAt);
        assertThat(savedCount(u.bearer())).isEqualTo(1);
        assertThat(auditRows(AuditLogService.EVT_JOB_SAVED, u.id())).hasSize(1);
    }

    @ParameterizedTest
    @EnumSource(value = JobStatus.class, names = "DONE", mode = EnumSource.Mode.EXCLUDE)
    void save_givenJobThatIsNotDone_expects409JobNotSaveable(JobStatus status) {
        Signed u = signupWithBalance(0L);
        GenerationJob job = saveJob(u.id(), Modality.IMAGE, status, Instant.now());

        save(job.getId(), u.bearer())
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(JobSaveRefusedException.CODE_NOT_SAVEABLE);

        assertThat(jobs.findById(job.getId()).orElseThrow().getSavedAt()).isNull();
        assertThat(getJob(job.getId(), u.bearer()).saved()).isFalse();
        assertThat(auditRows(AuditLogService.EVT_JOB_SAVED, u.id())).isEmpty();
    }

    @Test
    void save_givenPurgedJob_expects409JobNotSaveable() {
        Signed u = signupWithBalance(0L);
        GenerationJob job = doneJob(u.id(), Instant.now().minus(Duration.ofDays(100)));
        assertThat(retention.purgeJob(job.getId(), Instant.now())).isTrue();

        save(job.getId(), u.bearer())
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(JobSaveRefusedException.CODE_NOT_SAVEABLE);

        JobView purged = getJob(job.getId(), u.bearer());
        assertThat(purged.purged()).isTrue();
        assertThat(purged.saved()).isFalse();
        assertThat(purged.mediaExpiresAt()).isNull();
    }

    // ===== Un-saving ==================================================

    @Test
    void unsave_givenSavedJob_expects204AndTheDeletionDateBack() {
        Signed u = signupWithBalance(0L);
        GenerationJob job = doneJob(u.id(), Instant.now().minus(Duration.ofDays(10)));
        save(job.getId(), u.bearer()).expectStatus().isNoContent();

        unsave(job.getId(), u.bearer()).expectStatus().isNoContent().expectBody().isEmpty();

        JobView after = getJob(job.getId(), u.bearer());
        assertThat(after.saved()).isFalse();
        assertThat(after.mediaExpiresAt()).isEqualTo(after.createdAt().plus(DataRetentionService.JOB_RETENTION));
        assertThat(after.mediaExpiresAt()).isAfter(Instant.now());
        assertThat(after.outputs()).hasSize(1);
        assertThat(savedCount(u.bearer())).isZero();

        // Again: it succeeds and changes nothing.
        unsave(job.getId(), u.bearer()).expectStatus().isNoContent();
        assertThat(auditRows(AuditLogService.EVT_JOB_UNSAVED, u.id())).singleElement().satisfies(row -> {
            assertThat(row.getEventDetail()).containsEntry("jobId", job.getId().toString());
            assertThat(number(row, "saved")).isZero();
        });
    }

    @ParameterizedTest
    @EnumSource(JobStatus.class)
    void unsave_givenJobThatWasNeverSaved_expects204AndChangesNothing(JobStatus status) {
        Signed u = signupWithBalance(0L);
        GenerationJob job = saveJob(u.id(), Modality.IMAGE, status, Instant.now());

        unsave(job.getId(), u.bearer()).expectStatus().isNoContent();

        assertThat(jobs.findById(job.getId()).orElseThrow().getSavedAt()).isNull();
        assertThat(auditRows(AuditLogService.EVT_JOB_UNSAVED, u.id())).isEmpty();
    }

    @Test
    void getJob_givenJobUnsavedAfterTheRetentionPeriod_expectsADeletionDateInThePast() {
        Signed u = signupWithBalance(0L);
        GenerationJob job = doneJob(u.id(), Instant.now().minus(Duration.ofDays(120)));
        save(job.getId(), u.bearer()).expectStatus().isNoContent();
        assertThat(getJob(job.getId(), u.bearer()).mediaExpiresAt()).isNull();

        unsave(job.getId(), u.bearer()).expectStatus().isNoContent();

        // Until the next nightly run deletes it, the job is still whole and
        // says it is overdue.
        JobView overdue = getJob(job.getId(), u.bearer());
        assertThat(overdue.saved()).isFalse();
        assertThat(overdue.purged()).isFalse();
        assertThat(overdue.outputs()).hasSize(1);
        assertThat(overdue.mediaExpiresAt())
                .isEqualTo(overdue.createdAt().plus(DataRetentionService.JOB_RETENTION))
                .isBefore(Instant.now());

        retention.purgeExpiredJobs(Instant.now());

        JobView purged = getJob(job.getId(), u.bearer());
        assertThat(purged.purged()).isTrue();
        assertThat(purged.outputs()).isEmpty();
        assertThat(purged.mediaExpiresAt()).isNull();
    }

    // ===== Whose job it is ============================================

    @Test
    void saveAndUnsave_givenAnotherUsersJob_expect404AndLeaveItAlone() {
        Signed owner = signupWithBalance(0L);
        Signed other = signupWithBalance(0L);
        GenerationJob theirs = doneJob(owner.id(), Instant.now());

        save(theirs.getId(), other.bearer())
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(jobs.findById(theirs.getId()).orElseThrow().getSavedAt()).isNull();

        // Nor can a stranger un-save what its owner saved.
        save(theirs.getId(), owner.bearer()).expectStatus().isNoContent();
        unsave(theirs.getId(), other.bearer())
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        assertThat(jobs.findById(theirs.getId()).orElseThrow().getSavedAt()).isNotNull();
        assertThat(savedCount(other.bearer())).isZero();
        assertThat(savedCount(owner.bearer())).isEqualTo(1);
    }

    @Test
    void saveAndUnsave_givenUnknownJob_expect404() {
        Signed u = signupWithBalance(0L);
        UUID nowhere = UUID.randomUUID();

        save(nowhere, u.bearer()).expectStatus().isNotFound();
        unsave(nowhere, u.bearer()).expectStatus().isNotFound();
    }

    @ParameterizedTest
    @ValueSource(strings = {"PUT", "DELETE"})
    void saveAndUnsave_givenNoCredentials_expect401(String method) {
        Signed u = signupWithBalance(0L);
        GenerationJob job = doneJob(u.id(), Instant.now());

        http.method(HttpMethod.valueOf(method)).uri("/v1/jobs/{id}/saved", job.getId())
                .exchange()
                .expectStatus().isUnauthorized();
        assertThat(jobs.findById(job.getId()).orElseThrow().getSavedAt()).isNull();
    }

    @Test
    void saveUnsaveAndCount_givenApiKey_work() {
        Signed u = signupWithBalance(0L);
        GenerationJob job = doneJob(u.id(), Instant.now());
        String key = "Bearer " + http.post().uri("/v1/keys")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "saves"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody().plaintext();

        save(job.getId(), key).expectStatus().isNoContent();
        assertThat(getJob(job.getId(), key).saved()).isTrue();
        assertThat(savedCount(key)).isEqualTo(1);
        assertThat(ids(list(key, "?saved=true"))).containsExactly(job.getId());

        unsave(job.getId(), key).expectStatus().isNoContent();
        assertThat(getJob(job.getId(), key).saved()).isFalse();
    }

    // ===== The limit ==================================================

    @Test
    void save_givenLimitReached_expects409SavedLimitReachedUntilOneIsUnsaved() {
        Signed u = signupWithBalance(0L);
        List<GenerationJob> made = new ArrayList<>();
        for (int i = 0; i < LIMIT + 1; i++) {
            made.add(doneJob(u.id(), Instant.now().minusSeconds(60 - i)));
        }
        for (int i = 0; i < LIMIT; i++) {
            save(made.get(i).getId(), u.bearer()).expectStatus().isNoContent();
        }
        UUID oneTooMany = made.get(LIMIT).getId();

        save(oneTooMany, u.bearer())
                .expectStatus().isEqualTo(409)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo(JobSaveRefusedException.CODE_LIMIT_REACHED)
                .jsonPath("$.limit").isEqualTo(LIMIT);
        assertThat(jobs.findById(oneTooMany).orElseThrow().getSavedAt()).isNull();
        assertThat(savedCount(u.bearer())).isEqualTo(LIMIT);

        // At the limit, saving one that is already saved still succeeds.
        save(made.get(0).getId(), u.bearer()).expectStatus().isNoContent();

        // Un-saving one makes room for it. Nothing is un-saved on the user's behalf.
        unsave(made.get(0).getId(), u.bearer()).expectStatus().isNoContent();
        save(oneTooMany, u.bearer()).expectStatus().isNoContent();
        assertThat(savedCount(u.bearer())).isEqualTo(LIMIT);
        assertThat(getJob(oneTooMany, u.bearer()).saved()).isTrue();

        // The limit is each account's own.
        Signed other = signupWithBalance(0L);
        save(doneJob(other.id(), Instant.now()).getId(), other.bearer()).expectStatus().isNoContent();
    }

    @Test
    void save_givenConcurrentRequestsPastTheLimit_expectsExactlyTheLimitSaved() throws Exception {
        Signed u = signupWithBalance(0L);
        int requests = LIMIT * 4;
        List<UUID> made = new ArrayList<>();
        for (int i = 0; i < requests; i++) {
            made.add(doneJob(u.id(), Instant.now().minusSeconds(i)).getId());
        }

        ExecutorService pool = Executors.newFixedThreadPool(requests);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<Integer>> answers = new ArrayList<>();
        try {
            for (UUID id : made) {
                Callable<Integer> call = () -> {
                    go.await();
                    return save(id, u.bearer()).returnResult(Void.class).getStatus().value();
                };
                answers.add(pool.submit(call));
            }
            go.countDown();

            List<Integer> statuses = new ArrayList<>();
            for (Future<Integer> answer : answers) {
                statuses.add(answer.get(60, TimeUnit.SECONDS));
            }

            assertThat(statuses).filteredOn(s -> s == 204).hasSize(LIMIT);
            assertThat(statuses).filteredOn(s -> s == 409).hasSize(requests - LIMIT);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jobs.countSavedByUserId(u.id())).isEqualTo(LIMIT);
        assertThat(savedCount(u.bearer())).isEqualTo(LIMIT);
        assertThat(list(u.bearer(), "?saved=true")).hasSize(LIMIT);
        assertThat(auditRows(AuditLogService.EVT_JOB_SAVED, u.id())).hasSize(LIMIT);
    }

    @Test
    void savedCount_expectsTheCallersCountAndTheLimit() {
        Signed u = signupWithBalance(0L);
        GenerationJob first = doneJob(u.id(), Instant.now().minusSeconds(2));
        GenerationJob second = doneJob(u.id(), Instant.now().minusSeconds(1));

        http.get().uri("/v1/jobs/saved").header("Authorization", u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.count").isEqualTo(0)
                .jsonPath("$.limit").isEqualTo(LIMIT);

        save(first.getId(), u.bearer()).expectStatus().isNoContent();
        save(second.getId(), u.bearer()).expectStatus().isNoContent();
        assertThat(savedCount(u.bearer())).isEqualTo(2);

        unsave(first.getId(), u.bearer()).expectStatus().isNoContent();
        assertThat(savedCount(u.bearer())).isEqualTo(1);

        http.get().uri("/v1/jobs/saved").exchange().expectStatus().isUnauthorized();
    }

    // ===== The list ===================================================

    @Test
    void list_givenSavedFilter_expectsItToCombineWithPagingAndTheOtherFilters() {
        Signed u = signupWithBalance(0L);
        Instant t = Instant.now().truncatedTo(ChronoUnit.MILLIS).minus(Duration.ofHours(1));
        GenerationJob image0 = saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, t);
        GenerationJob image1 = saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, t.plusSeconds(1));
        GenerationJob video2 = saveJob(u.id(), Modality.VIDEO, JobStatus.DONE, t.plusSeconds(2));
        GenerationJob failed3 = saveJob(u.id(), Modality.IMAGE, JobStatus.FAILED, t.plusSeconds(3));
        GenerationJob image4 = saveJob(u.id(), Modality.IMAGE, JobStatus.DONE, t.plusSeconds(4));
        GenerationJob video5 = saveJob(u.id(), Modality.VIDEO, JobStatus.DONE, t.plusSeconds(5));
        for (GenerationJob saved : List.of(image0, video2, image4)) {
            save(saved.getId(), u.bearer()).expectStatus().isNoContent();
        }
        // Someone else's saved job never shows.
        Signed other = signupWithBalance(0L);
        save(saveJob(other.id(), Modality.IMAGE, JobStatus.DONE, t.plusSeconds(6)).getId(), other.bearer())
                .expectStatus().isNoContent();

        // Newest first, by when the job was made.
        assertThat(ids(list(u.bearer(), "?saved=true")))
                .containsExactly(image4.getId(), video2.getId(), image0.getId());
        assertThat(list(u.bearer(), "?saved=true")).allSatisfy(v -> assertThat(v.saved()).isTrue());
        // With paging: a page, then the page before a saved job, then the end.
        assertThat(ids(list(u.bearer(), "?saved=true&limit=2"))).containsExactly(image4.getId(), video2.getId());
        assertThat(ids(list(u.bearer(), "?saved=true&limit=2&before=" + video2.getId())))
                .containsExactly(image0.getId());
        assertThat(list(u.bearer(), "?saved=true&limit=2&before=" + image0.getId())).isEmpty();
        // The cursor can be any of the caller's jobs, saved or not.
        assertThat(ids(list(u.bearer(), "?saved=true&limit=5&before=" + failed3.getId())))
                .containsExactly(video2.getId(), image0.getId());
        // With the other filters.
        assertThat(ids(list(u.bearer(), "?saved=true&modality=IMAGE")))
                .containsExactly(image4.getId(), image0.getId());
        assertThat(ids(list(u.bearer(), "?saved=true&modality=VIDEO&status=DONE&limit=1")))
                .containsExactly(video2.getId());
        assertThat(list(u.bearer(), "?saved=true&status=FAILED")).isEmpty();
        assertThat(ids(list(u.bearer(), "?saved=TRUE&modality=IMAGE&limit=1&before=" + image4.getId())))
                .containsExactly(image0.getId());

        // saved=false is the rest, and without it the list is as before.
        assertThat(ids(list(u.bearer(), "?saved=false")))
                .containsExactly(video5.getId(), failed3.getId(), image1.getId());
        assertThat(ids(list(u.bearer(), "?saved=false&limit=2&before=" + video5.getId())))
                .containsExactly(failed3.getId(), image1.getId());
        List<UUID> everything = List.of(image0, image1, video2, failed3, image4, video5).stream()
                .sorted(Comparator.comparing(GenerationJob::getCreatedAt).reversed())
                .map(GenerationJob::getId).toList();
        assertThat(ids(list(u.bearer(), ""))).containsExactlyElementsOf(everything);
        assertThat(list(u.bearer(), "")).filteredOn(JobView::saved).hasSize(3);

        // Un-saving takes a job out of the saved list.
        unsave(video2.getId(), u.bearer()).expectStatus().isNoContent();
        assertThat(ids(list(u.bearer(), "?saved=true"))).containsExactly(image4.getId(), image0.getId());
    }

    @ParameterizedTest
    @ValueSource(strings = {"?saved=yes", "?saved=1", "?saved="})
    void list_givenSavedThatIsNotTrueOrFalse_expects400ProblemJson(String query) {
        Signed u = signupWithBalance(0L);

        http.get().uri("/v1/jobs" + query)
                .header("Authorization", u.bearer())
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "jobsave-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Job Saver"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledgerService.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private WebTestClient.ResponseSpec save(UUID jobId, String bearer) {
        return http.put().uri("/v1/jobs/{id}/saved", jobId).header("Authorization", bearer).exchange();
    }

    private WebTestClient.ResponseSpec unsave(UUID jobId, String bearer) {
        return http.delete().uri("/v1/jobs/{id}/saved", jobId).header("Authorization", bearer).exchange();
    }

    private long savedCount(String bearer) {
        Map<?, ?> body = http.get().uri("/v1/jobs/saved").header("Authorization", bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) body.get("count")).longValue();
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

    private List<AuditLogEntry> auditRows(String eventType, UUID userId) {
        return audit.findByEventType(eventType).stream()
                .filter(row -> userId.equals(row.getUserId()))
                .toList();
    }

    private static long number(AuditLogEntry row, String key) {
        return ((Number) row.getEventDetail().get(key)).longValue();
    }

    /** A finished image job with one stored file and its output row, as a worker leaves it. */
    private GenerationJob doneJob(UUID userId, Instant createdAt) {
        GenerationJob job = saveJob(userId, Modality.IMAGE, JobStatus.DONE, createdAt);
        String key = "images/" + job.getId() + "/0.png";
        storage.upload(key, new byte[] {1, 2, 3}, "image/png");
        outputRows.save(new GenerationJobOutput(job.getId(), 0, key, "image/png", 3L, null));
        return job;
    }

    /** A job row in the given state, written directly, with nothing in storage. */
    private GenerationJob saveJob(UUID userId, Modality modality, JobStatus status, Instant createdAt) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(modality);
        job.setProvider(Provider.OPENAI);
        job.setModel(modality == Modality.VIDEO ? "sora-2" : "gpt-image-2");
        job.setPrompt(Map.of("prompt", "a lighthouse at dusk", "n", 1));
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
