package ai.godance.web;

import static ai.godance.web.RecipeBatchFixture.IMAGE_CENTS;
import static ai.godance.web.RecipeBatchFixture.batchBody;
import static ai.godance.web.RecipeBatchFixture.row;
import static ai.godance.web.RecipeBatchFixture.stubImage;
import static ai.godance.web.RecipeBatchFixture.variable;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.RecipeBatchRunner;
import ai.godance.web.RecipeBatchFixture.Key;
import ai.godance.web.RecipeBatchFixture.Recipe;
import ai.godance.web.RecipeBatchFixture.Signed;

/**
 * Recipe batches (round 12 brief 01): one version of a recipe run over many
 * rows, each row an ordinary job. What is pinned here first is the money: a
 * batch charges nothing itself, so what its rows' jobs took from the ledger
 * is exactly what the batch says it spent.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RecipeBatchIntegrationTest {

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
    @Autowired ApiKeyRepository apiKeys;
    @Autowired RecipeBatchRunner runner;
    @Autowired JdbcTemplate jdbc;

    RecipeBatchFixture fx;

    @BeforeEach
    void reset() {
        RecipeBatchFixture.endLeftoverBatches(jdbc);
        openAiMock.resetAll();
        fx = new RecipeBatchFixture(http, users, ledger, runner);
    }

    // ===== The opening test: three rows, one at a time ==================

    @Test
    void tick_givenThreeRowsAndMaxConcurrencyOne_expectsThreeJobsInOrderAndTheLedgerSummingToSpentCents()
            throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        Recipe recipe = fx.createRecipe(u, "IMAGE", "A {{color}} mug on a shelf.",
                List.of(variable("color", "red")), "gpt-image-2", Map.of("n", 1));

        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(),
                List.of(row("color", "blue"), row("color", "green"), row("color", "yellow")), 1));

        // Creating a batch charges nothing and starts nothing.
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L);

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        // Three rows, three jobs, one job each.
        List<Map<String, Object>> rows = fx.batchRows(u.bearer(), batchId);
        assertThat(rows).extracting(r -> r.get("index")).containsExactly(0, 1, 2);
        assertThat(rows).extracting(r -> r.get("status")).containsOnly("DONE");
        List<GenerationJob> made = new ArrayList<>();
        for (Map<String, Object> r : rows) {
            made.add(jobs.findById(UUID.fromString((String) r.get("job_id"))).orElseThrow());
        }
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(3).containsExactlyInAnyOrderElementsOf(made);

        // In order, and one at a time: a row's job ended before the next row's job was made.
        assertThat(made).extracting(j -> j.getPrompt().get("prompt")).containsExactly(
                "A blue mug on a shelf.", "A green mug on a shelf.", "A yellow mug on a shelf.");
        for (int i = 0; i < made.size() - 1; i++) {
            assertThat(made.get(i).getStatus()).isEqualTo(JobStatus.DONE);
            assertThat(made.get(i).getCompletedAt()).isBeforeOrEqualTo(made.get(i + 1).getCreatedAt());
        }

        // The money. The batch spent what its rows' jobs were charged...
        long spent = ((Number) done.get("spent_cents")).longValue();
        assertThat(spent).isEqualTo(3 * IMAGE_CENTS);
        assertThat(rows).extracting(r -> ((Number) r.get("cost_cents")).longValue()).containsOnly(IMAGE_CENTS);
        assertThat(done.get("estimated_total_cents")).isEqualTo((int) (3 * IMAGE_CENTS));

        // ...and the ledger holds those jobs' entries and nothing else: every row
        // of it after the seed belongs to one of the three jobs, and they sum to
        // exactly what the batch says it spent.
        Set<String> requestIds = made.stream().map(GenerationJob::getRequestId).collect(Collectors.toSet());
        List<CreditLedger> entries = ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 100))
                .getContent().stream().filter(e -> !e.getSourceRef().startsWith("seed-")).toList();
        assertThat(entries).isNotEmpty().allSatisfy(e -> assertThat(requestIds).contains(e.getSourceRef()));
        assertThat(entries.stream().mapToLong(CreditLedger::getDeltaCents).sum()).isEqualTo(-spent);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - spent);

        // Each job says which batch and row it was made for, and how it was made.
        assertThat(made).extracting(j -> j.getChannel().name()).containsOnly("BATCH");
        http.get().uri("/v1/jobs/{id}", made.get(1).getId())
                .header("Authorization", u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.channel").isEqualTo("BATCH")
                .jsonPath("$.batch.id").isEqualTo(batchId)
                .jsonPath("$.batch.row").isEqualTo(1);
    }

    // ===== Estimate and create ========================================

    @Test
    void estimate_givenRows_expectsTheSumOfTheRowsEstimatesAgainstTheBalanceAndNothingWritten() {
        Signed u = fx.signupWithBalance(15_00L);
        Recipe recipe = mugRecipe(u);

        post(u.bearer(), "/v1/recipe-batches/estimate", batchBody(recipe.versionId(), rows(120, "color", "shade"), null))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.rows").isEqualTo(120)
                .jsonPath("$.total_cents").isEqualTo(720)      // 120 × the 6 cents POST /v1/estimates gives one image
                .jsonPath("$.balance_cents").isEqualTo(1500)
                .jsonPath("$.enough").isEqualTo(true)
                .jsonPath("$.limits.max_rows").isEqualTo(500)
                .jsonPath("$.limits.max_concurrency").isEqualTo(4);

        expectNothingWritten(u, 15_00L);
    }

    @Test
    void estimate_givenABalanceBelowTheTotal_expectsEnoughFalse() {
        Signed u = fx.signupWithBalance(17L);
        Recipe recipe = mugRecipe(u);

        post(u.bearer(), "/v1/recipe-batches/estimate", batchBody(recipe.versionId(), rows(3, "color", "shade"), null))
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.total_cents").isEqualTo(18)
                .jsonPath("$.balance_cents").isEqualTo(17)
                .jsonPath("$.enough").isEqualTo(false);
    }

    @Test
    void create_givenABadRowAtIndexSeven_expects400NamingTheRowAndNothingWritten() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        List<Map<String, Object>> rows = new ArrayList<>(rows(10, "color", "shade"));
        rows.set(7, row("colour", "teal"));      // not a variable of the recipe
        rows.set(9, row("hue", "also wrong"));   // a later bad row is not the one reported

        for (String path : List.of("/v1/recipe-batches/estimate", "/v1/recipe-batches")) {
            post(u.bearer(), path, batchBody(recipe.versionId(), rows, null))
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                    .expectBody()
                    // The code a single run of that row would answer, and which row.
                    .jsonPath("$.code").isEqualTo("recipe_variable_unknown")
                    .jsonPath("$.row").isEqualTo(7)
                    .jsonPath("$.detail").value(detail -> assertThat((String) detail).startsWith("row 7: ")
                            .contains("colour"));
        }

        expectNothingWritten(u, 100_00L);
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void create_givenAValueLongerThanAVariableMayHold_expects400WithThatRow() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);

        post(u.bearer(), "/v1/recipe-batches", batchBody(recipe.versionId(),
                List.of(row("color", "blue"), row("color", "x".repeat(2_001))), null))
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("recipe_variable_too_long")
                .jsonPath("$.row").isEqualTo(1);
        expectNothingWritten(u, 100_00L);
    }

    @Test
    void create_givenMoreRowsThanABatchMayHave_expects400BatchTooLarge() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);

        refused(u.bearer(), batchBody(recipe.versionId(), rows(501, "color", "shade"), null), 400, "batch_too_large");
        refused(u.bearer(), batchBody(recipe.versionId(), List.of(), null), 400, "invalid_batch");
        expectNothingWritten(u, 100_00L);
    }

    @Test
    void create_givenSettingsABatchDoesNotTake_expects400InvalidBatch() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        List<Map<String, Object>> one = List.of(row("color", "blue"));

        refused(u.bearer(), batchBody(recipe.versionId(), one, 5), 400, "invalid_batch");   // past the cap of 4
        refused(u.bearer(), batchBody(recipe.versionId(), one, 0), 400, "invalid_batch");
        Map<String, Object> typo = batchBody(recipe.versionId(), one, null);
        typo.put("params", Map.of("sise", "1024x1024"));
        refused(u.bearer(), typo, 400, "invalid_batch");
        Map<String, Object> notANumber = batchBody(recipe.versionId(), one, null);
        notANumber.put("params", Map.of("n", "two"));
        refused(u.bearer(), notANumber, 400, "invalid_batch");
        expectNothingWritten(u, 100_00L);
    }

    @Test
    void create_givenAModelThatCannotRunTheRecipe_expectsTheSingleRunsAnswer() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        List<Map<String, Object>> one = List.of(row("color", "blue"));

        Map<String, Object> unknown = batchBody(recipe.versionId(), one, null);
        unknown.put("model", "no-such-model");
        post(u.bearer(), "/v1/recipe-batches", unknown).expectStatus().isNotFound();
        Map<String, Object> video = batchBody(recipe.versionId(), one, null);
        video.put("model", "sora-2");
        post(u.bearer(), "/v1/recipe-batches", video).expectStatus().isBadRequest();
        // More outputs than the model returns per request.
        Map<String, Object> tooMany = batchBody(recipe.versionId(), one, null);
        tooMany.put("params", Map.of("n", 99));
        post(u.bearer(), "/v1/recipe-batches", tooMany).expectStatus().isBadRequest();
        expectNothingWritten(u, 100_00L);
    }

    @Test
    void create_givenParams_expectsThemInPlaceOfTheVersionsOnEveryJob() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        Recipe recipe = fx.createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")),
                "gpt-image-2", Map.of("n", 1, "size", "1024x1024", "quality", "high"));

        // No params: the version's. Params sent: those, and none of the version's.
        String asSaved = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), List.of(row("color", "blue")), 1));
        fx.runUntil(u.bearer(), asSaved, "DONE");
        Map<String, Object> other = batchBody(recipe.versionId(), List.of(row("color", "green")), 1);
        other.put("params", Map.of("size", "1536x1024"));
        String replaced = fx.createBatch(u.bearer(), other);
        fx.runUntil(u.bearer(), replaced, "DONE");

        Map<String, Object> first = jobOfRow(u, asSaved, 0).getPrompt();
        assertThat(first).containsEntry("size", "1024x1024").containsEntry("quality", "high").containsEntry("n", 1);
        Map<String, Object> second = jobOfRow(u, replaced, 0).getPrompt();
        assertThat(second).containsEntry("size", "1536x1024").doesNotContainKeys("quality", "n");
        assertThat(fx.batch(u.bearer(), replaced)).containsEntry("model", "gpt-image-2");
    }

    @Test
    void create_givenTwoUnfinishedBatches_expects409BatchLimitReachedUntilOneEnds() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        Map<String, Object> body = batchBody(recipe.versionId(), List.of(row("color", "blue")), null);
        String first = fx.createBatch(u.bearer(), body);
        fx.createBatch(u.bearer(), body);

        refused(u.bearer(), body, 409, "batch_limit_reached");

        post(u.bearer(), "/v1/recipe-batches/" + first + "/cancel", Map.of()).expectStatus().isOk();
        fx.createBatch(u.bearer(), body);
    }

    @Test
    void create_givenAnApiKey_expectsJobsChargedToThatKeyAndCountedAsItsBatch() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        Recipe recipe = mugRecipe(u);
        Key key = fx.apiKey(u);

        String batchId = fx.createBatch(key.bearer(), batchBody(recipe.versionId(),
                List.of(row("color", "blue"), row("color", "green")), 2));
        fx.runUntil(key.bearer(), batchId, "DONE");

        // The key reads its batch, and each job is the key's spend.
        assertThat(jdbc.queryForObject("SELECT created_by_channel FROM recipe_batches WHERE id = ?::uuid",
                String.class, batchId)).isEqualTo("API");
        List<GenerationJob> made = jobs.findByUserIdOrderByCreatedAtDesc(u.id());
        assertThat(made).hasSize(2).allSatisfy(job -> {
            assertThat(job.getChannel().name()).isEqualTo("BATCH");
            assertThat(job.getBatchChannel().name()).isEqualTo("API");
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM usage_records WHERE user_id = ? AND api_key_id = ?",
                Integer.class, u.id(), key.id())).isEqualTo(2);
    }

    @Test
    void tick_givenAVideoRecipe_expectsEachRowAVideoJobWithTheBatchsSettings() throws Exception {
        Signed u = fx.signupWithBalance(1000_00L);
        openAiMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/videos"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_batch\",\"status\":\"queued\"}")));
        openAiMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlPathMatching("/v1/videos/vid_batch"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_batch\",\"status\":\"completed\"}")));
        openAiMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.get(urlEqualTo("/v1/videos/vid_batch/content"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "video/mp4")
                        .withBody("FAKE-MP4-BYTES".getBytes(java.nio.charset.StandardCharsets.UTF_8))));
        Recipe recipe = fx.createRecipe(u, "VIDEO", "{{subject}} circling under falling rain.",
                List.of(variable("subject", "Koi")), "sora-2", Map.of("seconds", 4));

        // The estimate is two rows of what POST /v1/estimates gives one 4-second clip.
        Map<?, ?> one = http.post().uri("/v1/estimates").header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON).bodyValue(Map.of("model", "sora-2", "seconds", 4))
                .exchange().expectStatus().isOk().expectBody(Map.class).returnResult().getResponseBody();
        int perRow = ((Number) one.get("estimated_cost_cents")).intValue();
        Map<String, Object> body = batchBody(recipe.versionId(), List.of(row("subject", "Paper boats"), row()), 1);
        post(u.bearer(), "/v1/recipe-batches/estimate", body)
                .expectStatus().isOk()
                .expectBody().jsonPath("$.total_cents").isEqualTo(2 * perRow);

        String batchId = fx.createBatch(u.bearer(), body);
        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        assertThat(done).containsEntry("rows_done", 2).containsEntry("modality", "VIDEO")
                .containsEntry("spent_cents", 2 * perRow);
        GenerationJob first = jobOfRow(u, batchId, 0);
        assertThat(first.getModality().name()).isEqualTo("VIDEO");
        assertThat(first.getChannel().name()).isEqualTo("BATCH");
        assertThat(first.getPrompt()).containsEntry("prompt", "Paper boats circling under falling rain.")
                .containsEntry("duration_seconds", 4);
        // A row that gives no value runs with the variable's default.
        assertThat(jobOfRow(u, batchId, 1).getPrompt()).containsEntry("prompt", "Koi circling under falling rain.");
        List<Map<String, Object>> rowViews = fx.batchRows(u.bearer(), batchId);
        assertThat(((Map<?, ?>) rowViews.get(1).get("variables")).get("subject")).isEqualTo("Koi");
        assertThat(((Map<?, ?>) ((List<?>) rowViews.get(0).get("outputs")).get(0)).get("content_type")).isEqualTo("video/mp4");
    }

    // ===== Owner only =================================================

    @Test
    void everyRoute_givenAnotherUsersBatchOrRecipe_expects404AsIfItDidNotExist() {
        Signed owner = fx.signupWithBalance(100_00L);
        Signed other = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(owner);
        String batchId = fx.createBatch(owner.bearer(), batchBody(recipe.versionId(), List.of(row("color", "blue")), null));

        // Another user's recipe version makes no batch.
        refused(other.bearer(), batchBody(recipe.versionId(), List.of(row("color", "blue")), null), 404, "recipe_not_found");
        for (String path : List.of("", "/rows", "/results.csv", "/results.zip")) {
            http.get().uri("/v1/recipe-batches/" + batchId + path)
                    .header(HttpHeaders.AUTHORIZATION, other.bearer())
                    .exchange()
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo("batch_not_found");
        }
        for (String action : List.of("/cancel", "/resume")) {
            post(other.bearer(), "/v1/recipe-batches/" + batchId + action, Map.of())
                    .expectStatus().isNotFound()
                    .expectBody().jsonPath("$.code").isEqualTo("batch_not_found");
        }
        http.get().uri("/v1/recipe-batches").header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
        // Untouched by any of it.
        assertThat(fx.batch(owner.bearer(), batchId)).containsEntry("status", "QUEUED");
        // And no credentials at all is a 401.
        http.get().uri("/v1/recipe-batches/" + batchId).exchange().expectStatus().isUnauthorized();
    }

    // ===== Reading ====================================================

    @Test
    void list_givenBatchesOfTwoRecipes_expectsNewestFirstWithPagingAndTheRecipeFilter() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe mugs = mugRecipe(u);
        Recipe kites = fx.createRecipe(u, "IMAGE", "A {{color}} kite.", List.of(variable("color", "red")),
                "gpt-image-2", Map.of("n", 1));
        // Each is cancelled at once, so the limit on unfinished batches never stands in the way.
        String first = cancelled(u, mugs);
        String second = cancelled(u, kites);
        String third = cancelled(u, mugs);

        assertThat(ids(get(u.bearer(), "/v1/recipe-batches"))).containsExactly(third, second, first);
        assertThat(ids(get(u.bearer(), "/v1/recipe-batches?limit=1"))).containsExactly(third);
        assertThat(ids(get(u.bearer(), "/v1/recipe-batches?before=" + third))).containsExactly(second, first);
        assertThat(ids(get(u.bearer(), "/v1/recipe-batches?recipe_id=" + mugs.id()))).containsExactly(third, first);
        get(u.bearer(), "/v1/recipe-batches?limit=1")
                .jsonPath("$[0].recipe_id").isEqualTo(mugs.id())
                .jsonPath("$[0].recipe_title").isEqualTo("A batch recipe")
                .jsonPath("$[0].version_id").isEqualTo(mugs.versionId())
                .jsonPath("$[0].rows_total").isEqualTo(1)
                .jsonPath("$[0].rows_skipped").isEqualTo(1);
        http.get().uri("/v1/recipe-batches?limit=0").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void rows_givenAfter_expectsTheNextRowsInRowOrder() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(5, "color", "shade"), null));

        get(u.bearer(), "/v1/recipe-batches/" + batchId + "/rows?limit=2")
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].index").isEqualTo(0)
                .jsonPath("$[0].status").isEqualTo("PENDING")
                .jsonPath("$[0].variables.color").isEqualTo("shade 0")
                .jsonPath("$[0].outputs.length()").isEqualTo(0)
                .jsonPath("$[1].index").isEqualTo(1);
        get(u.bearer(), "/v1/recipe-batches/" + batchId + "/rows?limit=2&after=1")
                .jsonPath("$[0].index").isEqualTo(2)
                .jsonPath("$[1].index").isEqualTo(3);
        get(u.bearer(), "/v1/recipe-batches/" + batchId + "/rows?after=3")
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].index").isEqualTo(4);
    }

    // ===== Running: concurrency =======================================

    @Test
    void tick_givenMaxConcurrencyTwo_expectsNeverMoreThanTwoRowsRunning() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock, 400);   // slow enough for rows to overlap
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(5, "color", "shade"), 2));

        int mostAtOnce = 0;
        Map<String, Object> view = Map.of();
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            fx.tick();
            int open = jdbc.queryForObject(
                    "SELECT count(*) FROM generation_jobs WHERE user_id = ? AND status IN ('QUEUED', 'RUNNING')",
                    Integer.class, u.id());
            view = fx.batch(u.bearer(), batchId);
            assertThat(open).as("jobs open at once").isLessThanOrEqualTo(2);
            assertThat(((Number) view.get("rows_running")).intValue()).isLessThanOrEqualTo(2);
            mostAtOnce = Math.max(mostAtOnce, open);
            if ("DONE".equals(view.get("status"))) break;
            Thread.sleep(40);
        }

        assertThat(view).containsEntry("status", "DONE").containsEntry("rows_done", 5);
        // It did run two at a time, not one.
        assertThat(mostAtOnce).isEqualTo(2);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(5);
    }

    // ===== Pausing and resuming =======================================

    @Test
    void tick_givenCreditRunsOut_expectsPausedWithTheRowBackToPendingAndNoLedgerRowThenResumeContinues()
            throws Exception {
        Signed u = fx.signupWithBalance(2 * IMAGE_CENTS);   // enough for two of three rows
        stubImage(openAiMock);
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(3, "color", "shade"), 1));

        Map<String, Object> paused = fx.runUntil(u.bearer(), batchId, "PAUSED");

        assertThat(paused).containsEntry("pause_reason", "insufficient_credit").containsEntry("rows_done", 2)
                .containsEntry("rows_running", 0).containsEntry("rows_failed", 0).containsEntry("completed_at", null);
        List<Map<String, Object>> rows = fx.batchRows(u.bearer(), batchId);
        assertThat(rows).extracting(r -> r.get("status")).containsExactly("DONE", "DONE", "PENDING");
        // The refused row has no job, and nothing was written for it: the ledger holds the two jobs' debits only.
        assertThat(rows.get(2).get("job_id")).isNull();
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(2);
        assertThat(ledgerAfterSeed(u)).hasSize(2).allSatisfy(e -> assertThat(e.getDeltaCents()).isEqualTo(-IMAGE_CENTS));
        assertThat(ledger.authoritativeBalance(u.id())).isZero();
        assertThat(auditEvents(u, "batch.paused")).isEqualTo(1);

        // A paused batch stays paused however often the runner passes: nothing is retried, nothing charged.
        for (int i = 0; i < 5; i++) fx.tick();
        assertThat(fx.batch(u.bearer(), batchId)).containsEntry("status", "PAUSED");
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(2);

        // With credit again, resume continues from the row that was refused.
        ledger.topUp(u.id(), 10_00L, ai.godance.domain.CreditSource.PROMO, "more-" + u.id(), "more-" + u.id(), "test top-up");
        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/resume", Map.of())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("RUNNING")
                .jsonPath("$.pause_reason").value(reason -> assertThat(reason).isNull());
        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        assertThat(done).containsEntry("rows_done", 3).containsEntry("spent_cents", (int) (3 * IMAGE_CENTS));
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(3);
        assertThat(jobOfRow(u, batchId, 2).getPrompt()).containsEntry("prompt", "A shade 2 mug on a shelf.");
    }

    @Test
    void tick_givenTheApiKeysCapIsReached_expectsPausedForTheCapThenResumeOnceItIsRaised() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        Recipe recipe = mugRecipe(u);
        Key key = fx.apiKey(u);
        setKeyDailyCap(key, 10L);   // one 6-cent row fits; a second would pass it

        String batchId = fx.createBatch(key.bearer(), batchBody(recipe.versionId(), rows(3, "color", "shade"), 1));
        Map<String, Object> paused = fx.runUntil(u.bearer(), batchId, "PAUSED");

        assertThat(paused).containsEntry("pause_reason", "spending_cap").containsEntry("rows_done", 1);
        // Which cap, in words: the key's daily one.
        assertThat((String) paused.get("pause_detail")).contains("API-key daily spending cap");
        assertThat(fx.batchRows(u.bearer(), batchId)).extracting(r -> r.get("status"))
                .containsExactly("DONE", "PENDING", "PENDING");
        // Plenty of credit, and still nothing was taken for the refused row.
        assertThat(ledgerAfterSeed(u)).hasSize(1);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - IMAGE_CENTS);

        // Resuming under the same cap pauses again at the same row, with nothing charged.
        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/resume", Map.of()).expectStatus().isOk();
        fx.runUntil(u.bearer(), batchId, "PAUSED");
        assertThat(ledgerAfterSeed(u)).hasSize(1);

        setKeyDailyCap(key, 10_00L);
        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/resume", Map.of()).expectStatus().isOk();
        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");
        assertThat(done).containsEntry("rows_done", 3).containsEntry("spent_cents", (int) (3 * IMAGE_CENTS));
    }

    @Test
    void tick_givenTheAccountsOwnCapIsReached_expectsPausedForTheCap() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        Recipe recipe = mugRecipe(u);
        ai.godance.domain.User account = users.findById(u.id()).orElseThrow();
        account.setDailyCreditCapCents(10L);
        users.save(account);

        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(2, "color", "shade"), 1));
        Map<String, Object> paused = fx.runUntil(u.bearer(), batchId, "PAUSED");

        assertThat(paused).containsEntry("pause_reason", "spending_cap").containsEntry("rows_done", 1);
        assertThat((String) paused.get("pause_detail")).startsWith("daily spending cap");
        assertThat(ledgerAfterSeed(u)).hasSize(1);
    }

    @Test
    void resume_givenABatchThatIsNotPaused_expects409BatchNotResumable() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), List.of(row("color", "blue")), null));

        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/resume", Map.of())
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("batch_not_resumable");
        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/cancel", Map.of()).expectStatus().isOk();
        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/resume", Map.of())
                .expectStatus().isEqualTo(409)
                .expectBody().jsonPath("$.code").isEqualTo("batch_not_resumable");
    }

    // ===== Cancel =====================================================

    @Test
    void cancel_givenARunningBatch_expectsPendingRowsSkippedAndTheRunningRowFinished() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock, 600);
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(4, "color", "shade"), 1));
        fx.tick();   // row 0 is running

        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/cancel", Map.of())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED")
                .jsonPath("$.rows_skipped").isEqualTo(3)
                .jsonPath("$.rows_running").isEqualTo(1)
                // Not over until the running row has finished.
                .jsonPath("$.completed_at").value(at -> assertThat(at).isNull());

        Map<String, Object> over = fx.runUntil(u.bearer(), batchId, "CANCELLED");
        assertThat(over).containsEntry("rows_done", 1).containsEntry("rows_skipped", 3).containsEntry("rows_running", 0)
                .containsEntry("spent_cents", (int) IMAGE_CENTS);
        assertThat(fx.batchRows(u.bearer(), batchId)).extracting(r -> r.get("status"))
                .containsExactly("DONE", "SKIPPED", "SKIPPED", "SKIPPED");
        // The running row was charged as any job is; the skipped rows never became jobs.
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(1);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - IMAGE_CENTS);
        assertThat(auditEvents(u, "batch.cancelled")).isEqualTo(1);

        // Cancelling again changes nothing.
        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/cancel", Map.of())
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("CANCELLED");
        for (int i = 0; i < 3; i++) fx.tick();
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(1);
        assertThat(auditEvents(u, "batch.cancelled")).isEqualTo(1);
    }

    @Test
    void cancel_givenABatchWithNothingRunning_expectsItOverAtOnce() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(2, "color", "shade"), null));

        post(u.bearer(), "/v1/recipe-batches/" + batchId + "/cancel", Map.of())
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.status").isEqualTo("CANCELLED")
                .jsonPath("$.rows_skipped").isEqualTo(2)
                .jsonPath("$.completed_at").value(at -> assertThat(at).isNotNull());
        expectNoJobsOrCharges(u, 100_00L);
    }

    // ===== A failed row ===============================================

    @Test
    void tick_givenARowWhoseJobFails_expectsItRefundedAndTheBatchCarryingOn() throws Exception {
        Signed u = fx.signupWithBalance(100_00L);
        stubImage(openAiMock);
        openAiMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(urlEqualTo("/v1/images/generations"))
                .atPriority(1)
                .withRequestBody(matchingJsonPath("$.prompt", containing("doomed")))
                .willReturn(aResponse().withStatus(500)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"error\":{\"message\":\"upstream boom\"}}")));
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(),
                List.of(row("color", "blue"), row("color", "doomed"), row("color", "green")), 1));

        Map<String, Object> done = fx.runUntil(u.bearer(), batchId, "DONE");

        // One failed row did not stop the batch.
        assertThat(done).containsEntry("rows_done", 2).containsEntry("rows_failed", 1)
                .containsEntry("spent_cents", (int) (2 * IMAGE_CENTS));
        List<Map<String, Object>> rows = fx.batchRows(u.bearer(), batchId);
        assertThat(rows).extracting(r -> r.get("status")).containsExactly("DONE", "FAILED", "DONE");
        assertThat((String) rows.get(1).get("error")).isNotBlank();
        assertThat(rows.get(1).get("cost_cents")).isEqualTo(0);
        // The failed job was refunded by the job's own path: its two ledger rows cancel out.
        GenerationJob failed = jobOfRow(u, batchId, 1);
        assertThat(failed.getStatus()).isEqualTo(JobStatus.FAILED);
        List<CreditLedger> ofFailed = ledgerAfterSeed(u).stream()
                .filter(e -> failed.getRequestId().equals(e.getSourceRef())).toList();
        assertThat(ofFailed).hasSize(2);
        assertThat(ofFailed.stream().mapToLong(CreditLedger::getDeltaCents).sum()).isZero();
        // And the whole ledger still sums to what the batch says it spent.
        assertThat(ledgerAfterSeed(u).stream().mapToLong(CreditLedger::getDeltaCents).sum())
                .isEqualTo(-2 * IMAGE_CENTS);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(100_00L - 2 * IMAGE_CENTS);
        assertThat(auditEvents(u, "batch.completed")).isEqualTo(1);
    }

    // ===== What a batch depends on going away ==========================

    @Test
    void tick_givenTheRecipeDeleted_expectsTheBatchCancelledAndNothingStarted() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        String batchId = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), rows(2, "color", "shade"), null));
        http.delete().uri("/v1/recipes/{id}", recipe.id()).header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange().expectStatus().isNoContent();

        fx.tick();

        Map<String, Object> view = fx.batch(u.bearer(), batchId);
        assertThat(view).containsEntry("status", "CANCELLED").containsEntry("rows_skipped", 2)
                .containsEntry("recipe_id", null).containsEntry("recipe_title", null).containsEntry("version_id", null);
        assertThat(view.get("completed_at")).isNotNull();
        expectNoJobsOrCharges(u, 100_00L);
    }

    @Test
    void tick_givenTheKeyThatMadeTheBatchDeleted_expectsTheBatchCancelled() {
        Signed u = fx.signupWithBalance(100_00L);
        Recipe recipe = mugRecipe(u);
        Key key = fx.apiKey(u);
        String batchId = fx.createBatch(key.bearer(), batchBody(recipe.versionId(), rows(2, "color", "shade"), null));
        http.delete().uri("/v1/keys/{id}", key.id()).header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange().expectStatus().is2xxSuccessful();

        fx.tick();

        // A deleted key stops what it started, as it stops any request made with it.
        assertThat(fx.batch(u.bearer(), batchId)).containsEntry("status", "CANCELLED").containsEntry("rows_skipped", 2);
        expectNoJobsOrCharges(u, 100_00L);
    }

    // ===== Helpers ====================================================

    private Recipe mugRecipe(Signed u) {
        return fx.createRecipe(u, "IMAGE", "A {{color}} mug on a shelf.", List.of(variable("color", "red")),
                "gpt-image-2", Map.of("n", 1));
    }

    private static List<Map<String, Object>> rows(int count, String name, String prefix) {
        return RecipeBatchFixture.rows(count, name, prefix);
    }

    private WebTestClient.ResponseSpec post(String bearer, String path, Object body) {
        return http.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private WebTestClient.BodyContentSpec get(String bearer, String path) {
        return http.get().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    /** The ids of a list answer, in the order returned. */
    private static List<String> ids(WebTestClient.BodyContentSpec list) {
        List<String> ids = new ArrayList<>();
        list.jsonPath("$[*].id").value(found -> {
            for (Object id : (List<?>) found) ids.add((String) id);
        });
        return ids;
    }

    private void refused(String bearer, Map<String, Object> body, int status, String code) {
        post(bearer, "/v1/recipe-batches", body)
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(code);
    }

    /** A one-row batch of the recipe, cancelled at once; its id. */
    private String cancelled(Signed u, Recipe recipe) {
        String id = fx.createBatch(u.bearer(), batchBody(recipe.versionId(), List.of(row("color", "blue")), null));
        post(u.bearer(), "/v1/recipe-batches/" + id + "/cancel", Map.of()).expectStatus().isOk();
        return id;
    }

    private GenerationJob jobOfRow(Signed u, String batchId, int index) {
        Map<String, Object> row = fx.batchRows(u.bearer(), batchId).get(index);
        return jobs.findById(UUID.fromString((String) row.get("job_id"))).orElseThrow();
    }

    /** The account's ledger rows other than the test's own top-ups. */
    private List<CreditLedger> ledgerAfterSeed(Signed u) {
        return ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 200)).getContent().stream()
                .filter(e -> !e.getSourceRef().startsWith("seed-") && !e.getSourceRef().startsWith("more-"))
                .toList();
    }

    private void setKeyDailyCap(Key key, long cents) {
        ai.godance.domain.ApiKey stored = apiKeys.findById(key.id()).orElseThrow();
        stored.setDailyCreditCapCents(cents);
        apiKeys.save(stored);
    }

    private int auditEvents(Signed u, String eventType) {
        return jdbc.queryForObject("SELECT count(*) FROM audit_logs WHERE user_id = ? AND event_type = ?",
                Integer.class, u.id(), eventType);
    }

    /** No batch, no row, no job, no ledger row beyond the seed, and the balance as it was. */
    private void expectNothingWritten(Signed u, long balanceCents) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batches WHERE user_id = ?", Integer.class, u.id()))
                .isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_batch_rows", Integer.class)
                - rowsOfOtherUsers(u)).isZero();
        expectNoJobsOrCharges(u, balanceCents);
    }

    private int rowsOfOtherUsers(Signed u) {
        return jdbc.queryForObject("""
                SELECT count(*) FROM recipe_batch_rows r JOIN recipe_batches b ON b.id = r.batch_id
                 WHERE b.user_id <> ?""", Integer.class, u.id());
    }

    private void expectNoJobsOrCharges(Signed u, long balanceCents) {
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
        assertThat(ledgerAfterSeed(u)).isEmpty();
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(balanceCents);
    }
}
