package ai.godance.web;

import static ai.godance.web.RecipeBatchFixture.IMAGE_CENTS;
import static ai.godance.web.RecipeBatchFixture.TINY_PNG;
import static ai.godance.web.RecipeBatchFixture.batchBody;
import static ai.godance.web.RecipeBatchFixture.row;
import static ai.godance.web.RecipeBatchFixture.rows;
import static ai.godance.web.RecipeBatchFixture.stubImage;
import static ai.godance.web.RecipeBatchFixture.variable;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.AutowireCapableBeanFactory;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.EntityExchangeResult;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.AgeStatus;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.User;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AccountClosureService;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionJob;
import ai.godance.service.InputService;
import ai.godance.service.RecipeBatchRunner;
import ai.godance.web.RecipeBatchFixture.Key;
import ai.godance.web.RecipeBatchFixture.Recipe;
import ai.godance.web.RecipeBatchFixture.Signed;

/**
 * What a recipe batch leaves behind (round 12 brief 01): its results as a
 * CSV and as a zip, and how long each part of it is kept. Row values go
 * with their job's prompt at the purge, a batch with nothing left to show
 * is deleted, closing an account deletes its batches, and the input files
 * a batch's rows name are held until the batch ends.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RecipeBatchResultsIntegrationTest {

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
        r.add("godance.inputs.enabled", () -> "true");
        r.add("godance.inputs.allow-people", () -> "true");
        // A zip may hold two of the test's outputs and no more, so three is one too many.
        r.add("godance.batches.results-zip-max", () -> (2 * TINY_PNG.length) + "B");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired InputFileRepository files;
    @Autowired InputService inputs;
    @Autowired AccountClosureService closure;
    @Autowired RecipeBatchRunner runner;
    @Autowired AutowireCapableBeanFactory beans;
    @Autowired JdbcTemplate jdbc;

    RecipeBatchFixture fx;

    @BeforeEach
    void reset() {
        RecipeBatchFixture.endLeftoverBatches(jdbc);
        openAiMock.resetAll();
        String images = "{\"created\":1700000000,\"data\":[{\"b64_json\":\""
                + Base64.getEncoder().encodeToString(TINY_PNG) + "\"}]}";
        openAiMock.stubFor(post(urlEqualTo("/v1/images/edits"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(images)));
        stubImage(openAiMock);
        fx = new RecipeBatchFixture(http, users, ledger, runner);
    }

    // ===== results.csv ================================================

    @Test
    void resultsCsv_givenAFinishedBatch_expectsOneLinePerRowWithItsValuesItsJobAndASignedLinkToEachOutput()
            throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.prompt", containing("doomed")))
                .willReturn(aResponse().withStatus(500).withHeader("content-type", "application/json")
                        .withBody("{\"error\":{\"message\":\"upstream boom\"}}")));
        Recipe recipe = fx.createRecipe(u, "IMAGE", "A {{color}} mug on a {{surface}}.",
                List.of(variable("color", "red"), variable("surface", "shelf")), "gpt-image-2", Map.of("n", 1));
        // A comma, quotes and a line break in one value: everything RFC 4180 has to quote.
        String awkward = "teal, \"sea\"\nglass";
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), List.of(
                row("color", awkward), row("color", "doomed"), row("color", "green", "surface", "table")), 1));
        fx.runUntil(u.bearer(), batchId, "DONE");
        List<Map<String, Object>> rowViews = fx.batchRows(u.bearer(), batchId);

        EntityExchangeResult<byte[]> answer = download(u.bearer(), batchId, "results.csv");

        assertThat(answer.getResponseHeaders().getContentType().toString()).startsWith("text/csv");
        assertThat(answer.getResponseHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment").contains("batch-" + batchId + "-results.csv");
        List<List<String>> csv = parseCsv(new String(answer.getResponseBody(), StandardCharsets.UTF_8));
        assertThat(csv).hasSize(4);
        // index, status, a column for each variable in the recipe's order, the job, its cost, its error, its outputs.
        assertThat(csv.get(0)).containsExactly("index", "status", "color", "surface", "job_id", "cost_cents", "error",
                "output_1");

        List<String> first = csv.get(1);
        assertThat(first.subList(0, 4)).containsExactly("0", "DONE", awkward, "shelf");   // the default, written out
        assertThat(first.get(4)).isEqualTo(rowViews.get(0).get("job_id"));
        assertThat(first.get(5)).isEqualTo(Long.toString(IMAGE_CENTS));
        assertThat(first.get(6)).isEmpty();
        // The link is signed: it opens with no credentials, and it is the row's output.
        assertThat(InputFixtures.fetch(first.get(7)).body()).isEqualTo(TINY_PNG);

        List<String> failed = csv.get(2);
        assertThat(failed.subList(0, 4)).containsExactly("1", "FAILED", "doomed", "shelf");
        assertThat(failed.get(5)).isEqualTo("0");
        assertThat(failed.get(6)).isNotBlank();
        assertThat(failed.get(7)).isEmpty();

        assertThat(csv.get(3).subList(0, 4)).containsExactly("2", "DONE", "green", "table");
        assertThat(csv.get(3).get(7)).startsWith("http");

        // An API key of the account reads the same file.
        Key key = fx.apiKey(u);
        assertThat(parseCsv(new String(download(key.bearer(), batchId, "results.csv").getResponseBody(),
                StandardCharsets.UTF_8))).hasSize(4);
    }

    @Test
    void resultsCsv_givenABatchThatHasNotStarted_expectsEveryRowWithNoJobAndNoOutput() {
        Signed u = fx.signupWithBalance(100_00L);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(250, "color", "shade"), null));

        List<List<String>> csv = parseCsv(new String(download(u.bearer(), batchId, "results.csv").getResponseBody(),
                StandardCharsets.UTF_8));

        // More rows than one page of the writer: every one is there, in order.
        assertThat(csv).hasSize(251);
        assertThat(csv.get(0)).containsExactly("index", "status", "color", "job_id", "cost_cents", "error", "output_1");
        assertThat(csv.get(1)).containsExactly("0", "PENDING", "shade 0", "", "", "", "");
        assertThat(csv.get(250)).containsExactly("249", "PENDING", "shade 249", "", "", "", "");
    }

    // ===== results.zip ================================================

    @Test
    void resultsZip_givenAFinishedBatch_expectsEveryOutputNamedByItsRowAndItsFirstValue() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(),
                List.of(row("color", "Blue Mug!"), row("color", "茶杯 / red")), 2));
        fx.runUntil(u.bearer(), batchId, "DONE");

        EntityExchangeResult<byte[]> answer = download(u.bearer(), batchId, "results.zip");

        assertThat(answer.getResponseHeaders().getContentType()).isEqualTo(MediaType.parseMediaType("application/zip"));
        Map<String, byte[]> entries = new LinkedHashMap<>();
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(answer.getResponseBody()),
                StandardCharsets.UTF_8)) {
            for (ZipEntry entry = zip.getNextEntry(); entry != null; entry = zip.getNextEntry()) {
                entries.put(entry.getName(), zip.readAllBytes());
            }
        }
        // <index>-<the first variable's value, made safe for a file name>-<n>.<ext>, in row order.
        assertThat(entries.keySet()).containsExactly("0-Blue-Mug-1.png", "1-茶杯-red-1.png");
        assertThat(entries.values()).allSatisfy(bytes -> assertThat(bytes).isEqualTo(TINY_PNG));
    }

    @Test
    void resultsZip_givenOutputsPastTheConfiguredSize_expects413ResultsTooLargePointingToTheCsv() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(3, "color", "shade"), 3));
        fx.runUntil(u.bearer(), batchId, "DONE");

        http.get().uri("/v1/recipe-batches/" + batchId + "/results.zip")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange()
                .expectStatus().isEqualTo(413)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody()
                .jsonPath("$.code").isEqualTo("results_too_large")
                .jsonPath("$.detail").value(detail -> assertThat((String) detail)
                        .contains("/v1/recipe-batches/" + batchId + "/results.csv"));
        // The CSV it points to is there.
        assertThat(parseCsv(new String(download(u.bearer(), batchId, "results.csv").getResponseBody(),
                StandardCharsets.UTF_8))).hasSize(4);
    }

    // ===== Retention ==================================================

    @Test
    void retention_givenABatchWhoseJobsArePurged_expectsEachRowsValuesGoneWithItsJobAndThenTheBatch() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        String batchId = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(),
                List.of(row("color", "blue"), row("color", "green")), 2));
        fx.runUntil(u.bearer(), batchId, "DONE");
        List<Map<String, Object>> before = fx.batchRows(u.bearer(), batchId);
        String purgedJob = (String) before.get(0).get("job_id");
        String savedJob = (String) before.get(1).get("job_id");
        // The owner saves the second row's job, which keeps it past the retention period.
        http.put().uri("/v1/jobs/{id}/saved", savedJob).header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange().expectStatus().isNoContent();
        // A batch that has not ended is never deleted, however old.
        String waiting = fx.createBatch(u.bearer(), batchBody(mugRecipe(u).versionId(), rows(1, "color", "shade"), null));
        age(u, 91);

        runRetention();

        // The purged job's row lost its values with the job's prompt, and its output; cost and job stay.
        List<Map<String, Object>> after = fx.batchRows(u.bearer(), batchId);
        assertThat(after.get(0)).containsEntry("variables", null).containsEntry("status", "DONE")
                .containsEntry("job_id", purgedJob).containsEntry("cost_cents", (int) IMAGE_CENTS);
        assertThat((List<?>) after.get(0).get("outputs")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT variables IS NULL FROM recipe_batch_rows WHERE job_id = ?::uuid",
                Boolean.class, purgedJob)).isTrue();
        // The saved job's row is untouched, and so the batch is kept.
        assertThat(((Map<?, ?>) after.get(1).get("variables")).get("color")).isEqualTo("green");
        assertThat((List<?>) after.get(1).get("outputs")).hasSize(1);
        List<List<String>> csv = parseCsv(new String(download(u.bearer(), batchId, "results.csv").getResponseBody(),
                StandardCharsets.UTF_8));
        assertThat(csv.get(1).subList(0, 3)).containsExactly("0", "DONE", "");
        assertThat(csv.get(2).subList(0, 3)).containsExactly("1", "DONE", "green");
        assertThat(fx.batch(u.bearer(), waiting)).containsEntry("status", "QUEUED");

        // Un-saved, the second job is purged on the next run, and with no unpurged job left the batch goes.
        http.delete().uri("/v1/jobs/{id}/saved", savedJob).header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange().expectStatus().isNoContent();
        runRetention();

        http.get().uri("/v1/recipe-batches/" + batchId).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo("batch_not_found");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batch_rows WHERE batch_id = ?::uuid",
                Integer.class, batchId)).isZero();
        // The jobs stay, purged, with what they cost; they just no longer point at a batch.
        http.get().uri("/v1/jobs/{id}", purgedJob).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.purged").isEqualTo(true)
                .jsonPath("$.channel").isEqualTo("BATCH")
                .jsonPath("$.actualCostCents").isEqualTo((int) IMAGE_CENTS)
                .jsonPath("$.batch").value(batch -> assertThat(batch).isNull());
        assertThat(fx.batch(u.bearer(), waiting)).containsEntry("status", "QUEUED");
    }

    // ===== Account closure ============================================

    @Test
    void closeAccount_givenBatches_expectsThemDeletedWithTheirRowsAndNothingStartedAfterwards() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        String finished = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(1, "color", "shade"), 1));
        fx.runUntil(u.bearer(), finished, "DONE");
        fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(3, "color", "shade"), 1));
        Signed other = fx.signupWithBalance(100_00L);
        String kept = fx.createBatch(other.bearer(), batchBody(mugRecipe(other).versionId(), rows(2, "color", "shade"), null));

        closure.close(u.id(), null, "203.0.113.9");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batches WHERE user_id = ?", Integer.class, u.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batch_rows r JOIN recipe_batches b ON b.id = r.batch_id "
                + "WHERE b.user_id = ?", Integer.class, u.id())).isZero();
        // The runner finds nothing of the closed account to start.
        int jobsBefore = jobs.findByUserIdOrderByCreatedAtDesc(u.id()).size();
        for (int i = 0; i < 3; i++) fx.tick();
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(jobsBefore);
        // Another account's batch is untouched, and runs.
        fx.runUntil(other.bearer(), kept, "DONE");
    }

    // ===== Input files a batch's rows name ============================

    @Test
    void create_givenRowsThatNameFiles_expectsTheFilesHeldUntilTheBatchEnds() throws Exception {
        Signed u = withInputs(fx.signupWithBalance(100_00L));
        UUID mug = upload(u, "mug.png");
        UUID cup = upload(u, "cup.png");
        Recipe recipe = slotRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(),
                List.of(slotRow("oak", mug), slotRow("marble", cup)), 1));

        // Held from the moment the batch exists: no expiry, in use, and the nightly run passes them by.
        assertThat(files.findById(mug).orElseThrow().getExpiresAt()).isNull();
        assertThat(files.findById(cup).orElseThrow().getExpiresAt()).isNull();
        assertThat(files.isInUse(mug)).isTrue();
        inputs.deleteExpired(Instant.now().plus(Duration.ofDays(365)));
        assertThat(files.findById(mug)).isPresent();
        assertThat(files.findById(cup)).isPresent();

        // Cancelled before any row ran: nothing uses the files any more, so each gets an expiry again.
        http.post().uri("/v1/recipe-batches/" + batchId + "/cancel").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange().expectStatus().isOk();
        assertThat(files.findById(mug).orElseThrow().getExpiresAt()).isNotNull();
        assertThat(files.isInUse(mug)).isFalse();
        inputs.deleteExpired(Instant.now().plus(Duration.ofDays(365)));
        assertThat(files.findById(mug)).isEmpty();
        assertThat(files.findById(cup)).isEmpty();
    }

    @Test
    void tick_givenRowsWithInputs_expectsEachRunAsAnEditAndTheFilesItsJobsUsedStillHeldByThoseJobs() throws Exception {
        Signed u = withInputs(fx.signupWithBalance(100_00L));
        UUID mug = upload(u, "mug.png");
        UUID cup = upload(u, "cup.png");
        Recipe recipe = slotRecipe(u);
        Map<String, Object> body = batchBody(recipe.versionId(), List.of(slotRow("oak", mug), slotRow("marble", cup)), 2);

        // The estimate is the sum of the rows' estimates, input image included.
        int perRow = estimateWithOneInputImage(u);
        http.post().uri("/v1/recipe-batches/estimate").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.total_cents").isEqualTo(2 * perRow);

        String batchId = fx.createBatch(u.bearer(), body);
        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        // Each row ran as an edit with its own image, as a single run of the recipe would.
        assertThat(done).containsEntry("rows_done", 2);
        assertThat(openAiMock.findAll(postRequestedFor(urlEqualTo("/v1/images/edits")))).hasSize(2);
        assertThat(openAiMock.findAll(postRequestedFor(urlEqualTo("/v1/images/generations")))).isEmpty();
        long charged = jobs.findByUserIdOrderByCreatedAtDesc(u.id()).stream()
                .mapToLong(GenerationJob::getActualCostCents).sum();
        assertThat(((Number) done.get("spent_cents")).longValue()).isEqualTo(charged);
        // The batch has ended and let go of the files; the jobs that used them hold them now, as any job does.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batch_inputs WHERE batch_id = ?::uuid",
                Integer.class, batchId)).isZero();
        assertThat(files.isInUse(mug)).isTrue();
        assertThat(files.findById(mug).orElseThrow().getExpiresAt()).isNull();
        inputs.deleteExpired(Instant.now().plus(Duration.ofDays(365)));
        assertThat(files.findById(cup)).isPresent();
    }

    @Test
    void create_givenARowWhoseInputIsNotTheCallersOrIsMissing_expects400NamingTheRowAndNothingHeld() {
        Signed u = withInputs(fx.signupWithBalance(100_00L));
        Signed other = withInputs(fx.signupWithBalance(0L));
        UUID mine = upload(u, "mug.png");
        UUID theirs = upload(other, "cup.png");
        Recipe recipe = slotRecipe(u);

        // Another user's file is, to this caller, a file that does not exist.
        http.post().uri("/v1/recipe-batches").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(batchBody(recipe.versionId(), List.of(slotRow("oak", mine), slotRow("marble", theirs)), null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("file_not_found")
                .jsonPath("$.row").isEqualTo(1);
        // A row that fills no slot is refused as a single run would be.
        http.post().uri("/v1/recipe-batches").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(batchBody(recipe.versionId(), List.of(row("surface", "oak")), null))
                .exchange()
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("recipe_input_missing")
                .jsonPath("$.row").isEqualTo(0)
                .jsonPath("$.slot").isEqualTo("product photo");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batches WHERE user_id = ?", Integer.class, u.id()))
                .isZero();
        // Nothing was held: the file still has the expiry its upload gave it.
        assertThat(files.findById(mine).orElseThrow().getExpiresAt()).isNotNull();
        assertThat(files.isInUse(mine)).isFalse();
    }

    // ===== Helpers ====================================================

    private Recipe mugRecipe(Signed u) {
        return fx.createRecipe(u, "IMAGE", "A {{color}} mug on a shelf.", List.of(variable("color", "red")),
                "gpt-image-2", Map.of("n", 1));
    }

    /** A recipe with one image slot, "product photo". */
    private Recipe slotRecipe(Signed u) {
        return fx.createRecipe(u, "IMAGE", "Place the product on {{surface}}.", List.of(variable("surface", "wood")),
                "gpt-image-2", Map.of("n", 1),
                List.of(Map.of("slot", "product photo", "role", "image", "hint", "One product on a plain background")));
    }

    private static Map<String, Object> slotRow(String surface, UUID file) {
        Map<String, Object> row = new LinkedHashMap<>();
        row.put("variables", Map.of("surface", surface));
        row.put("inputs", Map.of("product photo", Map.of("file_id", file.toString())));
        return row;
    }

    /** The account, allowed to use inputs: an adult's, with the input terms accepted. */
    private Signed withInputs(Signed u) {
        User user = users.findById(u.id()).orElseThrow();
        user.setAgeStatus(AgeStatus.ADULT);
        user.setInputsTermsVersion(1);
        user.setInputsTermsAcceptedAt(Instant.now());
        users.save(user);
        return u;
    }

    private UUID upload(Signed u, String filename) {
        Map<?, ?> file = InputFixtures.upload(http, u.bearer(), InputFixtures.image("plain.png"), filename,
                        MediaType.IMAGE_PNG)
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return UUID.fromString((String) file.get("id"));
    }

    private int estimateWithOneInputImage(Signed u) {
        Map<?, ?> estimate = http.post().uri("/v1/estimates").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "n", 1, "input_images", 1))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();
        return ((Number) estimate.get("estimated_cost_cents")).intValue();
    }

    private EntityExchangeResult<byte[]> download(String bearer, String batchId, String file) {
        return http.get().uri("/v1/recipe-batches/" + batchId + "/" + file)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody(byte[].class).returnResult();
    }

    /** Makes the account's jobs and batches {@code days} old, as the nightly run sees them. */
    private void age(Signed u, int days) {
        jdbc.update("UPDATE generation_jobs SET created_at = now() - make_interval(days => ?) WHERE user_id = ?",
                days, u.id());
        jdbc.update("UPDATE recipe_batches SET created_at = now() - make_interval(days => ?) WHERE user_id = ?",
                days, u.id());
    }

    private void runRetention() {
        beans.createBean(DataRetentionJob.class).execute(null);
    }

    /** RFC 4180, as a reader of the file would parse it: quoted fields, doubled quotes, line breaks in a field. */
    private static List<List<String>> parseCsv(String text) {
        List<List<String>> records = new ArrayList<>();
        List<String> record = new ArrayList<>();
        StringBuilder field = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quoted) {
                if (c == '"' && i + 1 < text.length() && text.charAt(i + 1) == '"') {
                    field.append('"');
                    i++;
                } else if (c == '"') {
                    quoted = false;
                } else {
                    field.append(c);
                }
            } else if (c == '"') {
                quoted = true;
            } else if (c == ',') {
                record.add(field.toString());
                field.setLength(0);
            } else if (c == '\r' && i + 1 < text.length() && text.charAt(i + 1) == '\n') {
                record.add(field.toString());
                field.setLength(0);
                records.add(record);
                record = new ArrayList<>();
                i++;
            } else {
                field.append(c);
            }
        }
        assertThat(quoted).as("the file ends inside a quoted field").isFalse();
        assertThat(field.length() == 0 && record.isEmpty()).as("the file ends with a line break").isTrue();
        return records;
    }
}
