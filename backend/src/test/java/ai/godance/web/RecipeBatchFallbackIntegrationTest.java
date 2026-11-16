package ai.godance.web;

import static ai.godance.web.RecipeBatchFixture.batchBody;
import static ai.godance.web.RecipeBatchFixture.row;
import static ai.godance.web.RecipeBatchFixture.stubImage;
import static ai.godance.web.RecipeBatchFixture.variable;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.List;
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
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpHeaders;
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

import ai.godance.domain.CreditLedger;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.RecipeBatchRunner;
import ai.godance.web.RecipeBatchFixture.Recipe;
import ai.godance.web.RecipeBatchFixture.Signed;

/**
 * Batch fallbacks (round 14 brief 04): a batch created with
 * {@code "fallbacks": true} retries a row once, on the alternative its
 * owner's routing policy names, when the row's job fails on a provider error;
 * through the normal job path, so the new job takes its own pre-debit and the
 * failed one is refunded as always. A content refusal is never retried, and a
 * batch that did not ask is unchanged. WireMock stands in for OpenAI's two
 * image models, told apart by {@code $.model}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RecipeBatchFallbackIntegrationTest {

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
        r.add("godance.routing.user-policies.enabled", () -> "true");
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
    @Autowired RecipeBatchRunner runner;
    @Autowired JdbcTemplate jdbc;
    @Autowired ai.godance.routing.MediaFallbacks mediaFallbacks;

    RecipeBatchFixture fx;

    @BeforeEach
    void reset() {
        RecipeBatchFixture.endLeftoverBatches(jdbc);
        openAiMock.resetAll();
        stubImage(openAiMock);
        fx = new RecipeBatchFixture(http, users, ledger, runner);
    }

    @Test
    void tick_givenAFallbackBatchWhoseRowFailsOnAProviderError_expectsItRetriedOnceOnTheAlternativeWithItsOwnCharge()
            throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        withImageFallbacks(u, List.of("provider_error"));
        failForPrimary("doomed", 500, "{\"error\":{\"message\":\"upstream boom\",\"type\":\"server_error\"}}");
        String batchId = fx.createBatch(u.bearer(), withFallbacks(batchBody(mugRecipe(u).versionId(),
                List.of(row("color", "blue"), row("color", "doomed"), row("color", "green")), 1)));

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        assertThat(done).containsEntry("fallbacks", true).containsEntry("rows_done", 3).containsEntry("rows_failed", 0);
        List<Map<String, Object>> rows = fx.batchRows(u.bearer(), batchId);
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("DONE");
        // Only the retried row says which model served and which job it replaced.
        assertThat(rows.get(0)).doesNotContainKeys("model", "retried_job_id");
        assertThat(rows.get(2)).doesNotContainKeys("model", "retried_job_id");
        Map<String, Object> retried = rows.get(1);
        assertThat(retried).containsEntry("model", "gpt-image-1.5");

        // The row has both jobs: the failed one on the primary, refunded by its own path ...
        GenerationJob failed = jobs.findById(UUID.fromString((String) retried.get("retried_job_id"))).orElseThrow();
        assertThat(failed.getModel()).isEqualTo("gpt-image-2");
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        assertThat(ledgerOf(u, failed).stream().mapToLong(CreditLedger::getDeltaCents).sum()).isZero();
        // ... and the new one on the alternative, which took its own pre-debit and served.
        GenerationJob served = jobs.findById(UUID.fromString((String) retried.get("job_id"))).orElseThrow();
        assertThat(served.getModel()).isEqualTo("gpt-image-1.5");
        assertThat(served.getStatus()).isEqualTo(JobStatus.DONE);
        assertThat(served.getBatchRowId()).isEqualTo(failed.getBatchRowId());
        assertThat(ledgerOf(u, served)).isNotEmpty();
        assertThat(((Number) retried.get("cost_cents")).longValue()).isEqualTo(served.getActualCostCents());

        // The ledger still sums to what the batch spent: the failed attempt cost nothing.
        long spent = ((Number) done.get("spent_cents")).longValue();
        assertThat(ledgerAfterSeed(u).stream().mapToLong(CreditLedger::getDeltaCents).sum()).isEqualTo(-spent);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - spent);

        // The results file says which model each row ran on.
        String csv = csv(u, batchId);
        assertThat(csv.lines().findFirst().orElseThrow()).isEqualTo(
                "index,status,color,job_id,model,retried_job_id,cost_cents,error,output_1");
        assertThat(csv).contains("," + served.getId() + ",gpt-image-1.5," + failed.getId() + ",");
    }

    @Test
    void tick_givenAFallbackBatchWhoseRowIsRefused_expectsNoRetryOnAnotherModel() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        withImageFallbacks(u, List.of("provider_error", "timeout"));
        failForPrimary("forbidden", 400, "{\"error\":{\"message\":\"Your request was rejected by the safety system.\","
                + "\"type\":\"invalid_request_error\",\"code\":\"content_policy_violation\"}}");
        String batchId = fx.createBatch(u.bearer(), withFallbacks(batchBody(mugRecipe(u).versionId(),
                List.of(row("color", "forbidden")), 1)));

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        assertThat(done).containsEntry("rows_failed", 1);
        Map<String, Object> row = fx.batchRows(u.bearer(), batchId).get(0);
        assertThat(row).containsEntry("status", "FAILED").doesNotContainKeys("model", "retried_job_id");
        assertThat(jobsOfRow(row)).isEqualTo(1);
        assertThat(callsTo("gpt-image-1.5")).isZero();
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L);
    }

    @Test
    void tick_givenABatchWithoutFallbacks_expectsAFailedRowEndingAsBeforeAndTheCsvUnchanged() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        // The account has fallbacks for the model; the batch did not ask for them.
        withImageFallbacks(u, List.of("provider_error"));
        failForPrimary("doomed", 500, "{\"error\":{\"message\":\"upstream boom\",\"type\":\"server_error\"}}");
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(),
                List.of(row("color", "blue"), row("color", "doomed")), 1));

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        assertThat(done).doesNotContainKey("fallbacks").containsEntry("rows_done", 1).containsEntry("rows_failed", 1);
        Map<String, Object> failedRow = fx.batchRows(u.bearer(), batchId).get(1);
        assertThat(failedRow).containsEntry("status", "FAILED").doesNotContainKeys("model", "retried_job_id");
        assertThat(jobsOfRow(failedRow)).isEqualTo(1);
        assertThat(callsTo("gpt-image-1.5")).isZero();
        assertThat(csv(u, batchId).lines().findFirst().orElseThrow())
                .isEqualTo("index,status,color,job_id,cost_cents,error,output_1");
    }

    @Test
    void create_givenFallbacksAndNoPolicyWithFallbacksForTheModel_expects400AndNoBatch() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);

        http.post().uri("/v1/recipe-batches").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(withFallbacks(batchBody(recipe.versionId(), List.of(row("color", "blue")), 1)))
                .exchange()
                .expectStatus().isBadRequest().expectBody()
                .jsonPath("$.code").isEqualTo("fallbacks_unavailable")
                .jsonPath("$.detail").isEqualTo("none of your routing policies has fallbacks for gpt-image-2");
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_batches WHERE user_id = ?", Long.class, u.id()))
                .isZero();
    }

    @Test
    void alternativeFor_givenHealthBelowAndTooFewRecentJobsToJudge_expectsNoAlternative() {
        Signed u = fx.signupWithBalance(100_00L);
        http.put().uri("/v1/routing/policies/videos").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("default_model", "gpt-4o-mini", "fallbacks", List.of(Map.of(
                        "primary", "veo-3.1",
                        "alternatives", List.of(Map.of("model", "veo-3.1-fast", "on", List.of("health_below"),
                                "health_below", 0.9))))))
                .exchange()
                .expectStatus().isCreated();

        // No veo-3.1 job has run here: its health is unknown, so health_below does not move the row
        // (and the health query has run against the real generation_jobs table).
        assertThat(mediaFallbacks.alternativeFor(u.id(), "veo-3.1",
                "upstream: 503 Service Unavailable from POST http://veo.test")).isEmpty();
    }

    // ===== Helpers ======================================================

    private Recipe mugRecipe(Signed u) {
        return fx.createRecipe(u, "IMAGE", "A {{color}} mug on a shelf.", List.of(variable("color", "red")),
                "gpt-image-2", Map.of("n", 1));
    }

    /** A routing policy of the account's with fallbacks from gpt-image-2 to gpt-image-1.5 on {@code on}. */
    private void withImageFallbacks(Signed u, List<String> on) {
        http.put().uri("/v1/routing/policies/images").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("default_model", "gpt-4o-mini", "fallbacks", List.of(Map.of(
                        "primary", "gpt-image-2",
                        "alternatives", List.of(Map.of("model", "gpt-image-1.5", "on", on))))))
                .exchange()
                .expectStatus().isCreated();
    }

    private static Map<String, Object> withFallbacks(Map<String, Object> body) {
        Map<String, Object> out = new HashMap<>(body);
        out.put("fallbacks", true);
        return out;
    }

    /** gpt-image-2 answers {@code status} for a prompt with {@code word} in it; gpt-image-1.5 still answers. */
    private static void failForPrimary(String word, int status, String body) {
        openAiMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/images/generations"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.prompt", containing(word)))
                .withRequestBody(matchingJsonPath("$.model", equalTo("gpt-image-2")))
                .willReturn(aResponse().withStatus(status)
                        .withHeader("content-type", "application/json")
                        .withBody(body)));
    }

    private static long callsTo(String model) {
        return openAiMock.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getBodyAsString().contains("\"" + model + "\""))
                .count();
    }

    private long jobsOfRow(Map<String, Object> row) {
        UUID jobId = UUID.fromString((String) row.get("job_id"));
        UUID rowId = jobs.findById(jobId).orElseThrow().getBatchRowId();
        return jdbc.queryForObject("SELECT COUNT(*) FROM generation_jobs WHERE batch_row_id = ?", Long.class, rowId);
    }

    private String csv(Signed u, String batchId) {
        return new String(http.get().uri("/v1/recipe-batches/{id}/results.csv", batchId)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody().returnResult().getResponseBodyContent(), java.nio.charset.StandardCharsets.UTF_8);
    }

    /** The ledger rows of one job: its pre-debit, and its refund or settlement. */
    private List<CreditLedger> ledgerOf(Signed u, GenerationJob job) {
        return ledgerAfterSeed(u).stream().filter(e -> job.getRequestId().equals(e.getSourceRef())).toList();
    }

    /** The account's ledger rows other than the test's own top-ups. */
    private List<CreditLedger> ledgerAfterSeed(Signed u) {
        return ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 200)).getContent().stream()
                .filter(e -> !e.getSourceRef().startsWith("seed-"))
                .toList();
    }
}
