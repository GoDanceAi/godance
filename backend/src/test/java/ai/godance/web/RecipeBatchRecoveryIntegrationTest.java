package ai.godance.web;

import static ai.godance.web.RecipeBatchFixture.IMAGE_CENTS;
import static ai.godance.web.RecipeBatchFixture.batchBody;
import static ai.godance.web.RecipeBatchFixture.rows;
import static ai.godance.web.RecipeBatchFixture.stubImage;
import static ai.godance.web.RecipeBatchFixture.variable;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matching;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.domain.PageRequest;
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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ai.godance.OpenJobsGuard;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobChannel;
import ai.godance.domain.JobStatus;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.WebhookSigner;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.GenerationJobSweepJob;
import ai.godance.service.ImageGenerationService;
import ai.godance.service.JobOrigin;
import ai.godance.service.RecipeBatchRunner;
import ai.godance.web.RecipeBatchFixture.Recipe;
import ai.godance.web.RecipeBatchFixture.Signed;

/**
 * A recipe batch after a crash, at each step where one can happen (round 12
 * brief 01), and its webhook. The rule under test is the round's hard rule:
 * no row is ever submitted twice. Each test puts the database in the state a
 * crash at that step leaves and lets the runner take it from there.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class RecipeBatchRecoveryIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
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
        // The webhook receiver is on localhost.
        r.add("godance.webhooks.allow-private-destinations", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired GenerationJobRepository jobs;
    @Autowired RecipeBatchRunner runner;
    @Autowired ImageGenerationService images;
    @Autowired WebhookSigner webhookSigner;
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired JdbcTemplate jdbc;

    RecipeBatchFixture fx;

    @BeforeEach
    void reset() {
        RecipeBatchFixture.endLeftoverBatches(jdbc);
        openAiMock.resetAll();
        webhookReceiver.resetAll();
        webhookReceiver.stubFor(post(urlEqualTo("/cb")).willReturn(aResponse().withStatus(200)));
        fx = new RecipeBatchFixture(http, users, ledger, runner);
    }

    // ===== A crash between claiming a row and creating its job ==========

    @Test
    void tick_givenARowClaimedButNeverGivenAJob_expectsItSubmittedOnce() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(2, "color", "shade"), 1));
        // What a pass that died right after its claim leaves: the row RUNNING, no job id, no job, nothing charged.
        jdbc.update("UPDATE recipe_batches SET status = 'RUNNING', started_at = now() WHERE id = ?::uuid", batchId);
        jdbc.update("UPDATE recipe_batch_rows SET status = 'RUNNING', started_at = now() "
                + "WHERE batch_id = ?::uuid AND row_index = 0", batchId);

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        // No provider had been called for the row, so starting it now is its first and only submission.
        assertThat(done).containsEntry("rows_done", 2).containsEntry("spent_cents", (int) (2 * IMAGE_CENTS));
        assertThat(jobsOfRow(batchId, 0)).isEqualTo(1);
        assertThat(jobsOfRow(batchId, 1)).isEqualTo(1);
        assertThat(providerCallsFor("shade 0")).isEqualTo(1);
        assertThat(openAiMock.findAll(postRequestedFor(urlEqualTo("/v1/images/generations")))).hasSize(2);
        assertThat(ledgerSum(u)).isEqualTo(-2 * IMAGE_CENTS);
    }

    // ===== A crash after the job was accepted: its worker is gone ========

    @Test
    void tick_givenARowWhoseWorkerWasLost_expectsTheRowFailedAndRefundedAndNeverResubmitted() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        // The provider never answers in time: the row's worker is as good as gone.
        stubImage(openAiMock, 3_000);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(2, "color", "shade"), 1));
        fx.tick();
        UUID lost = UUID.fromString((String) fx.batchRows(u.bearer(), batchId).get(0).get("job_id"));
        assertThat(ledgerSum(u)).isEqualTo(-IMAGE_CENTS);   // its pre-debit is held

        // However often the runner passes, a row with a job waits for that job. It is not started again.
        for (int i = 0; i < 5; i++) fx.tick();
        assertThat(jobsOfRow(batchId, 0)).isEqualTo(1);
        assertThat(providerCallsFor("shade 0")).isEqualTo(1);

        // The job's lease runs out, and the job sweep ends it as it ends any abandoned job: FAILED and refunded.
        jdbc.update("UPDATE generation_jobs SET lease_expires_at = now() - interval '1 minute' WHERE id = ?", lost);
        beans.createBean(GenerationJobSweepJob.class).execute(null);
        assertThat(jobs.findById(lost).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);

        // Later rows get a provider that answers.
        openAiMock.resetAll();
        stubImage(openAiMock);
        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        assertThat(done).containsEntry("rows_failed", 1).containsEntry("rows_done", 1)
                .containsEntry("spent_cents", (int) IMAGE_CENTS);
        List<Map<String, Object>> rowViews = fx.batchRows(u.bearer(), batchId);
        assertThat(rowViews).extracting(r -> r.get("status")).containsExactly("FAILED", "DONE");
        assertThat((String) rowViews.get(0).get("error")).contains("worker lost");
        // Still the one job for the lost row, and the provider was asked for it once.
        assertThat(jobsOfRow(batchId, 0)).isEqualTo(1);
        assertThat(rowViews.get(0).get("job_id")).isEqualTo(lost.toString());

        // The lost worker's answer arrives after all. The job had ended, so it settles nothing.
        Thread.sleep(3_500);
        assertThat(jobs.findById(lost).orElseThrow().getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(ledgerSum(u)).isEqualTo(-IMAGE_CENTS);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - IMAGE_CENTS);
    }

    // ===== A crash after the job ended, before the row took its outcome ==

    @Test
    void tick_givenAJobThatEndedWhileNoPassRan_expectsTheRowToTakeItsOutcomeWithoutASecondJob() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(1, "color", "shade"), 1));
        fx.tick();
        UUID jobId = UUID.fromString((String) fx.batchRows(u.bearer(), batchId).get(0).get("job_id"));
        awaitJob(jobId, JobStatus.DONE);
        // No pass has run since: the row still reads RUNNING.
        assertThat(fx.batchRows(u.bearer(), batchId).get(0)).containsEntry("status", "RUNNING");

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        assertThat(done).containsEntry("rows_done", 1).containsEntry("spent_cents", (int) IMAGE_CENTS);
        assertThat(jobsOfRow(batchId, 0)).isEqualTo(1);
        assertThat(openAiMock.findAll(postRequestedFor(urlEqualTo("/v1/images/generations")))).hasSize(1);
    }

    // ===== The job path itself refuses a second job for a row ============

    @Test
    void executeAsync_givenARowThatAlreadyHasItsJob_expectsRefusedWithNothingCharged() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(2, "color", "shade"), 1));
        fx.tick();   // row 0 has its job; row 1 is still PENDING
        long balance = ledger.authoritativeBalance(u.id());
        ImageRequest request = new ImageRequest("gpt-image-2", "A second try", null, 1, null, null);

        // Straight at the generation service, past everything the runner checks.
        UUID started = rowId(batchId, 0);
        assertThatThrownBy(() -> images.executeAsync(u.id(), null, request, null,
                JobOrigin.ofBatchRow(started, JobChannel.STUDIO)))
                .isInstanceOf(RuntimeException.class);
        // A row nobody has claimed gets no job either.
        UUID unclaimed = rowId(batchId, 1);
        assertThatThrownBy(() -> images.executeAsync(u.id(), null, request, null,
                JobOrigin.ofBatchRow(unclaimed, JobChannel.STUDIO)))
                .isInstanceOf(RuntimeException.class);

        // The pre-debit of each refused attempt rolled back with it: no job, no ledger row, no provider call.
        assertThat(jobsOfRow(batchId, 0)).isEqualTo(1);
        assertThat(jobsOfRow(batchId, 1)).isZero();
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(balance);
        assertThat(providerCallsFor("A second try")).isZero();
        fx.runUntil(u.bearer(), batchId, "DONE");
        assertThat(ledgerSum(u)).isEqualTo(-2 * IMAGE_CENTS);
    }

    @Test
    void tick_givenManyPassesAtOnce_expectsOneJobPerRowAndTheConcurrencyKept() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock, 150);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(8, "color", "shade"), 2));

        // Eight passes at a time, over and over: more overlap than two instances would ever give.
        ExecutorService pool = Executors.newFixedThreadPool(8);
        List<Integer> runningSeen = new ArrayList<>();
        try {
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline && !"DONE".equals(fx.batch(u.bearer(), batchId).get("status"))) {
                CountDownLatch round = new CountDownLatch(8);
                for (int i = 0; i < 8; i++) {
                    pool.submit(() -> {
                        try {
                            runner.tick();
                        } finally {
                            round.countDown();
                        }
                    });
                }
                assertThat(round.await(20, TimeUnit.SECONDS)).isTrue();
                runningSeen.add(jdbc.queryForObject(
                        "SELECT count(*) FROM recipe_batch_rows WHERE batch_id = ?::uuid AND status = 'RUNNING'",
                        Integer.class, batchId));
            }
        } finally {
            pool.shutdownNow();
        }

        Map<String, Object> done = fx.batch(u.bearer(), batchId);
        assertThat(done).containsEntry("status", "DONE").containsEntry("rows_done", 8)
                .containsEntry("spent_cents", (int) (8 * IMAGE_CENTS));
        for (int index = 0; index < 8; index++) assertThat(jobsOfRow(batchId, index)).isEqualTo(1);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(8);
        assertThat(openAiMock.findAll(postRequestedFor(urlEqualTo("/v1/images/generations")))).hasSize(8);
        assertThat(ledgerSum(u)).isEqualTo(-8 * IMAGE_CENTS);
        assertThat(runningSeen).allSatisfy(running -> assertThat(running).isLessThanOrEqualTo(2));
    }

    // ===== The end of a batch, and its webhook ==========================

    @Test
    void tick_givenABatchWithAWebhook_expectsItSignedAndSentOnceWithTheCountsAndTheResults() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        Map<String, Object> body = batchBody(mugRecipe(u).versionId(), rows(2, "color", "shade"), 2);
        body.put("webhook_url", webhookReceiver.baseUrl() + "/cb");
        String batchId = fx.createBatch(u.bearer(), body);

        fx.runUntil(u.bearer(), batchId, "DONE");
        LoggedRequest call = awaitWebhooks(1).get(0);

        // Signed as a job's webhook is: X-Godance-Signature: t=<seconds>,v1=<HMAC-SHA256 of "<t>.<body>">.
        String header = call.getHeader(WebhookSigner.HEADER);
        assertThat(header).matches("t=\\d+,v1=[0-9a-f]{64}");
        String t = header.substring(2, header.indexOf(','));
        assertThat(header.substring(header.indexOf("v1=") + 3))
                .isEqualTo(hmacHex(webhookSigner.secretFor(u.id()), t + "." + call.getBodyAsString()));
        // The event, the batch with its counts, and where the results are.
        com.jayway.jsonpath.DocumentContext sent = com.jayway.jsonpath.JsonPath.parse(call.getBodyAsString());
        assertThat(sent.<String>read("$.event")).isEqualTo("recipe_batch.completed");
        assertThat(sent.<String>read("$.batch.id")).isEqualTo(batchId);
        assertThat(sent.<String>read("$.batch.status")).isEqualTo("DONE");
        assertThat(sent.<Integer>read("$.batch.rows_total")).isEqualTo(2);
        assertThat(sent.<Integer>read("$.batch.rows_done")).isEqualTo(2);
        assertThat(sent.<Integer>read("$.batch.rows_failed")).isZero();
        assertThat(sent.<Integer>read("$.batch.spent_cents")).isEqualTo((int) (2 * IMAGE_CENTS));
        assertThat(sent.<String>read("$.results.csv")).isEqualTo("/v1/recipe-batches/" + batchId + "/results.csv");
        assertThat(sent.<String>read("$.results.zip")).isEqualTo("/v1/recipe-batches/" + batchId + "/results.zip");

        // Once: no later pass sends it again, and the batch's jobs sent no webhook of their own.
        for (int i = 0; i < 5; i++) fx.tick();
        Thread.sleep(300);
        assertThat(webhookReceiver.findAll(postRequestedFor(urlEqualTo("/cb")))).hasSize(1);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).allSatisfy(job -> assertThat(job.getWebhookUrl()).isNull());
    }

    @Test
    void tick_givenEveryRowEndedButTheBatchNot_expectsItEndedAndTheWebhookSentOnce() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        String batchId = batchWithWebhook(u, 2);
        // A crash after the last row took its outcome, before the batch was ended.
        jdbc.update("UPDATE recipe_batches SET status = 'RUNNING', started_at = now() WHERE id = ?::uuid", batchId);
        jdbc.update("UPDATE recipe_batch_rows SET status = 'DONE', cost_cents = 6, completed_at = now() "
                + "WHERE batch_id = ?::uuid", batchId);

        fx.tick();
        fx.tick();

        assertThat(fx.batch(u.bearer(), batchId)).containsEntry("status", "DONE");
        awaitWebhooks(1);
        Thread.sleep(300);
        assertThat(webhookReceiver.findAll(postRequestedFor(urlEqualTo("/cb")))).hasSize(1);
    }

    @Test
    void tick_givenABatchThatEndedBeforeItsWebhookWasSent_expectsItSentOnce() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        String batchId = batchWithWebhook(u, 1);
        // A crash after the batch was ended, before anyone took the right to send its webhook.
        jdbc.update("UPDATE recipe_batch_rows SET status = 'DONE', cost_cents = 6, completed_at = now() "
                + "WHERE batch_id = ?::uuid", batchId);
        jdbc.update("UPDATE recipe_batches SET status = 'DONE', started_at = now(), completed_at = now() "
                + "WHERE id = ?::uuid", batchId);
        assertThat(webhookReceiver.findAll(postRequestedFor(urlEqualTo("/cb")))).isEmpty();

        fx.tick();
        awaitWebhooks(1);
        for (int i = 0; i < 5; i++) fx.tick();
        Thread.sleep(300);

        assertThat(webhookReceiver.findAll(postRequestedFor(urlEqualTo("/cb")))).hasSize(1);
        assertThat(jdbc.queryForObject("SELECT webhook_sent_at IS NOT NULL FROM recipe_batches WHERE id = ?::uuid",
                Boolean.class, batchId)).isTrue();
    }

    @Test
    void cancel_givenABatchWithAWebhook_expectsOneWebhookSayingCancelled() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        String batchId = batchWithWebhook(u, 3);

        http.post().uri("/v1/recipe-batches/" + batchId + "/cancel").header("Authorization", u.bearer())
                .exchange().expectStatus().isOk();
        LoggedRequest call = awaitWebhooks(1).get(0);
        fx.tick();
        Thread.sleep(300);

        com.jayway.jsonpath.DocumentContext sent = com.jayway.jsonpath.JsonPath.parse(call.getBodyAsString());
        assertThat(sent.<String>read("$.batch.status")).isEqualTo("CANCELLED");
        assertThat(sent.<Integer>read("$.batch.rows_skipped")).isEqualTo(3);
        assertThat(webhookReceiver.findAll(postRequestedFor(urlEqualTo("/cb")))).hasSize(1);
    }

    @Test
    void create_givenAWebhookUrlTheBackendMustNotCall_expects400AndNothingWritten() {
        Signed u = fx.signupWithBalance(100_00L);
        Map<String, Object> body = batchBody(mugRecipe(u).versionId(), rows(1, "color", "shade"), null);
        body.put("webhook_url", "not a url");

        http.post().uri("/v1/recipe-batches").header("Authorization", u.bearer())
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isBadRequest();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batches WHERE user_id = ?", Integer.class, u.id()))
                .isZero();
    }

    // ===== Helpers ====================================================

    private Recipe mugRecipe(Signed u) {
        return fx.createRecipe(u, "IMAGE", "A {{color}} mug on a shelf.", List.of(variable("color", "red")),
                "gpt-image-2", Map.of("n", 1));
    }

    private String batchWithWebhook(Signed u, int rowCount) {
        Map<String, Object> body = batchBody(mugRecipe(u).versionId(), rows(rowCount, "color", "shade"), 1);
        body.put("webhook_url", webhookReceiver.baseUrl() + "/cb");
        return fx.createBatch(u.bearer(), body);
    }

    private UUID rowId(String batchId, int index) {
        return jdbc.queryForObject("SELECT id FROM recipe_batch_rows WHERE batch_id = ?::uuid AND row_index = ?",
                UUID.class, batchId, index);
    }

    /** How many jobs were ever made for the row: the database allows one. */
    private int jobsOfRow(String batchId, int index) {
        return jdbc.queryForObject("SELECT count(*) FROM generation_jobs WHERE batch_row_id = ?", Integer.class,
                rowId(batchId, index));
    }

    private int providerCallsFor(String promptPart) {
        return openAiMock.findAll(postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withRequestBody(containing(promptPart))).size();
    }

    /** What the account's ledger rows sum to, the seed aside. */
    private long ledgerSum(Signed u) {
        return ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 200)).getContent().stream()
                .filter(e -> !e.getSourceRef().startsWith("seed-"))
                .mapToLong(CreditLedger::getDeltaCents).sum();
    }

    private void awaitJob(UUID jobId, JobStatus status) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            GenerationJob job = jobs.findById(jobId).orElseThrow();
            if (job.getStatus() == status) return;
            Thread.sleep(50);
        }
        assertThat(jobs.findById(jobId).orElseThrow().getStatus()).isEqualTo(status);
    }

    private List<LoggedRequest> awaitWebhooks(int count) throws InterruptedException {
        List<LoggedRequest> calls = List.of();
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            calls = webhookReceiver.findAll(postRequestedFor(urlEqualTo("/cb"))
                    .withHeader(WebhookSigner.HEADER, matching("t=\\d+,v1=[0-9a-f]{64}")));
            if (calls.size() >= count) break;
            Thread.sleep(50);
        }
        assertThat(calls).hasSize(count);
        return calls;
    }

    private static String hmacHex(String secret, String data) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        StringBuilder hex = new StringBuilder();
        for (byte b : mac.doFinal(data.getBytes(StandardCharsets.UTF_8))) hex.append(String.format("%02x", b));
        return hex.toString();
    }
}
