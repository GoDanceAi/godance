package ai.godance.service;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
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

import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;

/**
 * Spec risks R-03 and R-05: an accepted media job always ends, DONE or
 * FAILED-and-refunded, exactly once, whether its worker finishes, fails to
 * store the result, or is lost to a restart.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class GenerationJobRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer openAiMock;

    @BeforeAll
    static void startMock() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
    }

    @AfterAll
    static void stopMock() {
        if (openAiMock != null) openAiMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
    }

    /** Object storage is the failure this class injects; every upload throws. */
    @MockitoBean ObjectStorageClient storage;

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired UsageRecordRepository usage;
    @Autowired GenerationJobSettlement settlement;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        when(storage.upload(anyString(), any(), anyString()))
                .thenThrow(new IllegalStateException("object storage unavailable"));
    }

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "jobs-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Job Recovery Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private long balance(UUID userId) {
        return users.findById(userId).orElseThrow().getCreditBalanceCents();
    }

    private static GenerationJob job(UUID userId, long estimateCents, String requestId, Instant leaseExpiresAt) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(Modality.IMAGE);
        job.setProvider(Provider.OPENAI);
        job.setModel("gpt-image-2");
        job.setPrompt(Map.of("prompt", "a recovered job"));
        job.setEstimatedCostCents(estimateCents);
        job.setRequestId(requestId);
        job.setLeaseExpiresAt(leaseExpiresAt);
        return job;
    }

    // ===== Lost worker: the sweep ends the job and refunds it ==========

    @Test
    void sweep_givenOpenJobPastItsLease_failsItAndRefundsOnce() {
        Signed u = signupWithBalance(100_00L);
        GenerationJob job = job(u.id(), 500L, "img_" + UUID.randomUUID(), Instant.now().minusSeconds(60));
        assertThat(settlement.accept(job)).isTrue();
        assertThat(settlement.start(job)).isTrue();
        assertThat(balance(u.id())).isEqualTo(100_00L - 500L);

        assertThat(GenerationJobSweepJob.sweep(settlement)).isGreaterThanOrEqualTo(1);
        GenerationJobSweepJob.sweep(settlement);   // a second pass refunds nothing more

        GenerationJob swept = jobs.findById(job.getId()).orElseThrow();
        assertThat(swept.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(swept.getErrorMessage()).startsWith("worker lost");
        assertThat(balance(u.id())).isEqualTo(100_00L);
    }

    @Test
    void sweep_givenOpenJobInsideItsLease_leavesItRunning() {
        Signed u = signupWithBalance(100_00L);
        GenerationJob job = job(u.id(), 500L, "img_" + UUID.randomUUID(), Instant.now().plus(Duration.ofMinutes(10)));
        settlement.accept(job);

        GenerationJobSweepJob.sweep(settlement);

        assertThat(jobs.findById(job.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.QUEUED);
        assertThat(balance(u.id())).isEqualTo(100_00L - 500L);
    }

    @Test
    void sweep_givenPreV16JobWithoutLease_refundsItOnlyOnceItIsOld() {
        Signed u = signupWithBalance(100_00L);
        // Rows written before V16 have no lease or request id; their pre-debit used an unrecorded id.
        ledger.preDebit(u.id(), 300L, "img_legacy_" + UUID.randomUUID());
        ledger.preDebit(u.id(), 200L, "img_legacy_" + UUID.randomUUID());
        GenerationJob old = job(u.id(), 300L, null, null);
        old.setStatus(JobStatus.RUNNING);
        old.setCreatedAt(Instant.now().minus(Duration.ofHours(2)));
        jobs.save(old);
        GenerationJob recent = job(u.id(), 200L, null, null);
        recent.setStatus(JobStatus.RUNNING);
        recent.setCreatedAt(Instant.now().minus(Duration.ofMinutes(5)));
        jobs.save(recent);
        assertThat(balance(u.id())).isEqualTo(100_00L - 500L);

        GenerationJobSweepJob.sweep(settlement);
        GenerationJobSweepJob.sweep(settlement);

        assertThat(jobs.findById(old.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(jobs.findById(recent.getId()).orElseThrow().getStatus()).isEqualTo(JobStatus.RUNNING);
        assertThat(balance(u.id())).isEqualTo(100_00L - 200L);
    }

    // ===== A worker that finishes after the sweep settles nothing ======

    @Test
    void complete_afterTheSweepEndedTheJob_isANoOp() {
        Signed u = signupWithBalance(100_00L);
        GenerationJob job = job(u.id(), 500L, "img_" + UUID.randomUUID(), Instant.now().minusSeconds(60));
        settlement.accept(job);
        settlement.start(job);
        GenerationJobSweepJob.sweep(settlement);

        boolean completed = settlement.complete(job, "https://storage.invalid/late.png", 500L, new UsageRecord());

        assertThat(completed).isFalse();
        GenerationJob after = jobs.findById(job.getId()).orElseThrow();
        assertThat(after.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(after.getOutputUrl()).isNull();
        assertThat(usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(), Instant.now().minusSeconds(60))).isEmpty();
        assertThat(balance(u.id())).isEqualTo(100_00L);
    }

    // ===== Storage fails after the provider delivered (R-05) ==========

    @Test
    void generate_storageFailsAfterUpstreamSuccess_jobEndsFailedAndRefunded() throws Exception {
        Signed u = signupWithBalance(100_00L);
        String b64 = Base64.getEncoder().encodeToString(new byte[] {(byte) 0x89, 0x50, 0x4E, 0x47});
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));

        AsyncJobAcknowledged ack = http.post().uri("/v1/images/generations")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "a cat", "n", 1))
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody();

        GenerationJob ended = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            ended = jobs.findById(ack.jobId()).orElseThrow();
            if (ended.getStatus() == JobStatus.FAILED || ended.getStatus() == JobStatus.DONE) break;
            Thread.sleep(100);
        }

        assertThat(ended.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(ended.getErrorMessage()).startsWith("finalize:");
        assertThat(ended.getRequestId()).startsWith("img_");
        assertThat(balance(u.id())).isEqualTo(100_00L);
    }
}
