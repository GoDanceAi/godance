package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
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

import ai.godance.domain.CreditSource;
import ai.godance.domain.JobStatus;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AccountClosureService;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * Running a recipe (round 10 brief 03): a generation request names a
 * recipe version and values for its variables in place of a prompt. The
 * server renders the template, and the rendered text is the job's prompt
 * everywhere: what the provider is sent, what the job stores and shows.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RecipeRunIntegrationTest {

    private static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

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
    @Autowired AccountClosureService closure;
    @Autowired JdbcTemplate jdbc;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
    }

    // ===== Rendering ==================================================

    @Test
    void generateImage_givenRecipe_expectsRenderedPromptAndRecipeVersionOnTheJob() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug on a {{surface}}.",
                List.of(variable("color", "red"), variable("surface", "kitchen shelf")), "gpt-image-2");

        UUID jobId = submit("/v1/images/generations", u.bearer(), Map.of(
                "model", "gpt-image-2",
                "recipe", Map.of("version_id", recipe.versionId(), "variables", Map.of("color", "blue"))));

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer())
                // A value replaces its default; a variable with no value keeps its default.
                .jsonPath("$.prompt").isEqualTo("A blue mug on a kitchen shelf.")
                .jsonPath("$.recipe.id").isEqualTo(recipe.id())
                .jsonPath("$.recipe.version").isEqualTo(1)
                .jsonPath("$.recipe.variables.color").isEqualTo("blue")
                .jsonPath("$.recipe.variables.surface").isEqualTo("kitchen shelf")
                .jsonPath("$.status").isEqualTo("DONE")
                .jsonPath("$.actualCostCents").isEqualTo(6);
        // The provider was sent the rendered text, and nothing of the template.
        openAiMock.verify(postRequestedFor(urlEqualTo("/v1/images/generations"))
                .withRequestBody(equalToJson("{\"prompt\":\"A blue mug on a kitchen shelf.\"}", true, true)));
    }

    @Test
    void generateVideo_givenRecipe_expectsRenderedPromptAndRecipeVersionOnTheJob() throws Exception {
        Signed u = signupWithBalance(1000_00L);
        stubVideo();
        Recipe recipe = createRecipe(u, "VIDEO", "{{subject}} circling under falling rain.",
                List.of(variable("subject", "Koi")), "sora-2");

        UUID jobId = submit("/v1/videos/generations", apiKeyBearer(u), Map.of(
                "model", "sora-2", "seconds", 4,
                "recipe", Map.of("version_id", recipe.versionId(), "variables", Map.of("subject", "Paper boats"))));

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer())
                .jsonPath("$.prompt").isEqualTo("Paper boats circling under falling rain.")
                .jsonPath("$.recipe.id").isEqualTo(recipe.id())
                .jsonPath("$.recipe.version").isEqualTo(1)
                .jsonPath("$.recipe.variables.subject").isEqualTo("Paper boats")
                .jsonPath("$.channel").isEqualTo("API")
                .jsonPath("$.params.seconds").isEqualTo(4);
    }

    @Test
    void generateImage_givenRecipeAndNoVariables_expectsEveryDefault() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");

        UUID jobId = submit("/v1/images/generations", u.bearer(), Map.of(
                "model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId())));

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer())
                .jsonPath("$.prompt").isEqualTo("A red mug.")
                .jsonPath("$.recipe.variables.color").isEqualTo("red");
    }

    @Test
    void generateImage_givenAnEmptyValueAndAValueWithAMarker_expectsBothInsertedAsTheyAre() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug{{extra}}.",
                List.of(variable("color", "red"), variable("extra", " on a shelf")), "gpt-image-2");

        // An empty string is a value. One pass: a value that holds a marker is not expanded.
        UUID jobId = submit("/v1/images/generations", u.bearer(), Map.of(
                "model", "gpt-image-2",
                "recipe", Map.of("version_id", recipe.versionId(), "variables", Map.of("color", "{{extra}}", "extra", ""))));

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer()).jsonPath("$.prompt").isEqualTo("A {{extra}} mug.");
    }

    @Test
    void generateImage_givenAnOlderVersion_expectsThatVersionsTemplate() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        String second = newVersion(u, recipe.id(), "A {{color}} teapot.", List.of(variable("color", "green")), "gpt-image-2");

        UUID first = submit("/v1/images/generations", u.bearer(), Map.of(
                "model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId())));
        UUID latest = submit("/v1/images/generations", u.bearer(), Map.of(
                "model", "gpt-image-2", "recipe", Map.of("version_id", second)));

        awaitDone(first, u.bearer());
        awaitDone(latest, u.bearer());
        readJob(first, u.bearer()).jsonPath("$.prompt").isEqualTo("A red mug.").jsonPath("$.recipe.version").isEqualTo(1);
        readJob(latest, u.bearer()).jsonPath("$.prompt").isEqualTo("A green teapot.").jsonPath("$.recipe.version").isEqualTo(2);
    }

    @Test
    void generateImage_givenRecipeAndGalleryRecipe_expectsBothRecorded() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");

        UUID jobId = submit("/v1/images/generations", u.bearer(), Map.of(
                "model", "gpt-image-2", "gallery_recipe", "paper-lantern-street-at-dusk",
                "recipe", Map.of("version_id", recipe.versionId())));

        awaitDone(jobId, u.bearer());
        readJob(jobId, u.bearer())
                .jsonPath("$.galleryRecipe").isEqualTo("paper-lantern-street-at-dusk")
                .jsonPath("$.channel").isEqualTo("STUDIO")
                .jsonPath("$.recipe.version").isEqualTo(1);
    }

    @Test
    void generateImage_givenNoRecipe_expectsRecipeNullAndThePromptAsSent() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();

        UUID jobId = submit("/v1/images/generations", u.bearer(),
                Map.of("model", "gpt-image-2", "prompt", "A {{color}} mug, braces and all."));

        awaitDone(jobId, u.bearer());
        // A plain prompt is never rendered: its braces are the author's.
        readJob(jobId, u.bearer())
                .jsonPath("$.prompt").isEqualTo("A {{color}} mug, braces and all.")
                .jsonPath("$.recipe").value(v -> assertThat(v).isNull());
    }

    // ===== Refused before any charge ==================================

    @Test
    void generateImage_givenPromptAndRecipe_expects400PromptAndRecipeAndNoLedgerRow() {
        Signed u = signupWithBalance(100_00L);
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");

        refused("/v1/images/generations", u, Map.of("model", "gpt-image-2", "prompt", "A mug.",
                "recipe", Map.of("version_id", recipe.versionId())), 400, "prompt_and_recipe");
    }

    @Test
    void generateVideo_givenPromptAndRecipe_expects400PromptAndRecipeAndNoLedgerRow() {
        Signed u = signupWithBalance(1000_00L);
        Recipe recipe = createRecipe(u, "VIDEO", "{{subject}} in the rain.", List.of(variable("subject", "Koi")), "sora-2");

        refused("/v1/videos/generations", u, Map.of("model", "sora-2", "prompt", "Koi.",
                "recipe", Map.of("version_id", recipe.versionId())), 400, "prompt_and_recipe");
    }

    @Test
    void generateImage_givenAnotherUsersRecipe_expects404RecipeNotFoundAndNoLedgerRow() {
        Signed owner = signupWithBalance(100_00L);
        Recipe recipe = createRecipe(owner, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        Signed other = signupWithBalance(100_00L);

        refused("/v1/images/generations", other, Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", recipe.versionId())), 404, "recipe_not_found");
        // A version that does not exist answers the same.
        refused("/v1/images/generations", other, Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", UUID.randomUUID().toString())), 404, "recipe_not_found");
    }

    @Test
    void generate_givenARecipeOfTheOtherModality_expects400RecipeModalityMismatchAndNoLedgerRow() {
        Signed u = signupWithBalance(1000_00L);
        Recipe image = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        Recipe video = createRecipe(u, "VIDEO", "{{subject}} in the rain.", List.of(variable("subject", "Koi")), "sora-2");

        refused("/v1/videos/generations", u, Map.of("model", "sora-2",
                "recipe", Map.of("version_id", image.versionId())), 400, "recipe_modality_mismatch");
        refused("/v1/images/generations", u, Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", video.versionId())), 400, "recipe_modality_mismatch");
    }

    @Test
    void generateImage_givenAVariableTheVersionDoesNotDeclare_expects400RecipeVariableUnknownAndNoLedgerRow() {
        Signed u = signupWithBalance(100_00L);
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");

        refused("/v1/images/generations", u, Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", recipe.versionId(), "variables", Map.of("colour", "blue"))),
                400, "recipe_variable_unknown");
    }

    @Test
    void generateImage_givenAValueLongerThan2000Characters_expects400AndNoLedgerRow() {
        Signed u = signupWithBalance(100_00L);
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");

        refused("/v1/images/generations", u, Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", recipe.versionId(), "variables", Map.of("color", "b".repeat(2001)))),
                400, "recipe_variable_too_long");
    }

    @Test
    void generateImage_givenNeitherPromptNorRecipe_expects400AsBefore() {
        Signed u = signupWithBalance(100_00L);

        for (Map<String, Object> body : List.<Map<String, Object>>of(
                Map.of("model", "gpt-image-2"), Map.of("model", "gpt-image-2", "prompt", "   "))) {
            http.post().uri("/v1/images/generations")
                    .header(HttpHeaders.AUTHORIZATION, u.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }
        expectNothingCharged(u, 100_00L);
    }

    @Test
    void generateImage_givenARecipeWithNoVersionId_expects400AndNoLedgerRow() {
        Signed u = signupWithBalance(100_00L);

        http.post().uri("/v1/images/generations")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "recipe", Map.of("variables", Map.of("color", "blue"))))
                .exchange()
                .expectStatus().isBadRequest();
        expectNothingCharged(u, 100_00L);
    }

    // ===== Jobs of a recipe ===========================================

    @Test
    void listJobs_givenRecipe_expectsOnlyThatRecipesJobs() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe mug = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        Recipe pot = createRecipe(u, "IMAGE", "A {{color}} teapot.", List.of(variable("color", "green")), "gpt-image-2");
        String mugV2 = newVersion(u, mug.id(), "A {{color}} mug, studio light.", List.of(variable("color", "red")), "gpt-image-2");
        UUID a = submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", mug.versionId())));
        UUID b = submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", mugV2)));
        UUID c = submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", pot.versionId())));
        UUID d = submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "prompt", "No recipe at all."));
        for (UUID id : List.of(a, b, c, d)) awaitDone(id, u.bearer());

        // Every version of the recipe, newest first; the other recipe's and the plain one are left out.
        http.get().uri("/v1/jobs?recipe={id}", mug.id())
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(2)
                .jsonPath("$[0].id").isEqualTo(b.toString())
                .jsonPath("$[0].recipe.version").isEqualTo(2)
                .jsonPath("$[1].id").isEqualTo(a.toString())
                .jsonPath("$[1].recipe.version").isEqualTo(1);
        http.get().uri("/v1/jobs?recipe={id}&limit=1", mug.id())
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].id").isEqualTo(b.toString());
        http.get().uri("/v1/jobs?recipe={id}&limit=1&before={before}", mug.id(), b)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(1).jsonPath("$[0].id").isEqualTo(a.toString());
        // Without the filter, all four, each with its recipe or none.
        http.get().uri("/v1/jobs").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(4)
                .jsonPath("$[?(@.id == '" + d + "')].recipe").value(v -> assertThat((List<?>) v).containsOnlyNulls());
    }

    @Test
    void listJobs_givenAnotherUsersRecipe_expectsNothingAndNoError() throws Exception {
        Signed owner = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(owner, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        awaitDone(submit("/v1/images/generations", owner.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId()))), owner.bearer());
        Signed other = signupWithBalance(0L);

        // No route says whether another user's recipe exists, even by a count.
        http.get().uri("/v1/jobs?recipe={id}", recipe.id()).header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
        http.get().uri("/v1/jobs?recipe={id}", UUID.randomUUID()).header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isOk().expectBody().jsonPath("$.length()").isEqualTo(0);
        http.get().uri("/v1/jobs?recipe=not-an-id").header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void getRecipe_givenJobsOfIt_expectsAttemptsAndWhatTheFinishedOnesCost() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        for (int i = 0; i < 2; i++) {
            awaitDone(submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId()))), u.bearer());
        }
        // A third attempt fails upstream: it counts as an attempt and costs nothing.
        openAiMock.resetAll();
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations")).willReturn(aResponse().withStatus(500)));
        UUID failed = submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId())));
        awaitTerminal(failed, u.bearer());

        http.get().uri("/v1/recipes/{id}", recipe.id()).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.attempts").isEqualTo(3)
                // gpt-image-2: 6¢ an image, twice.
                .jsonPath("$.spent_cents").isEqualTo(12);
        http.get().uri("/v1/recipes").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody().jsonPath("$[0].attempts").isEqualTo(3).jsonPath("$[0].spent_cents").isEqualTo(12);
    }

    // ===== Purge, deletion and closure ================================

    @Test
    void purge_givenAJobOfARecipe_expectsTheValuesRedactedWithThePrompt() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        UUID jobId = submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", recipe.versionId(), "variables", Map.of("color", "a private colour"))));
        awaitDone(jobId, u.bearer());
        assertThat(storedRequest(jobId)).contains("a private colour");

        assertThat(retention.purgeJob(jobId, Instant.now())).isTrue();

        // The job still says which recipe and version it ran; what was typed into it is gone.
        readJob(jobId, u.bearer())
                .jsonPath("$.purged").isEqualTo(true)
                .jsonPath("$.prompt").value(v -> assertThat(v).isNull())
                .jsonPath("$.recipe.id").isEqualTo(recipe.id())
                .jsonPath("$.recipe.version").isEqualTo(1)
                .jsonPath("$.recipe.variables").value(v -> assertThat(v).isNull());
        assertThat(storedRequest(jobId)).doesNotContain("a private colour").doesNotContain("recipe_variables");
        // It still counts as an attempt, with its cost.
        http.get().uri("/v1/recipes/{id}", recipe.id()).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody().jsonPath("$.attempts").isEqualTo(1).jsonPath("$.spent_cents").isEqualTo(6);
    }

    @Test
    void deleteRecipe_givenJobsOfIt_expectsTheJobsReadableAndUnlinked() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        UUID jobId = submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId())));
        awaitDone(jobId, u.bearer());

        http.delete().uri("/v1/recipes/{id}", recipe.id()).header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectStatus().isNoContent();

        // The job keeps its prompt, outputs and cost; it just no longer points at a recipe.
        readJob(jobId, u.bearer())
                .jsonPath("$.status").isEqualTo("DONE")
                .jsonPath("$.prompt").isEqualTo("A red mug.")
                .jsonPath("$.outputs.length()").isEqualTo(1)
                .jsonPath("$.actualCostCents").isEqualTo(6)
                .jsonPath("$.recipe").value(v -> assertThat(v).isNull());
        http.get().uri("/v1/jobs").header(HttpHeaders.AUTHORIZATION, u.bearer()).exchange()
                .expectBody().jsonPath("$.length()").isEqualTo(1);
        // Its version can no longer be run.
        refusedWithJobs("/v1/images/generations", u, Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId())), 404, "recipe_not_found", 1);
    }

    @Test
    void closeAccount_givenRecipes_expectsThemDeleted() throws Exception {
        Signed u = signupWithBalance(100_00L);
        stubImage();
        Recipe recipe = createRecipe(u, "IMAGE", "A {{color}} mug.", List.of(variable("color", "red")), "gpt-image-2");
        newVersion(u, recipe.id(), "A {{color}} teapot.", List.of(variable("color", "green")), "gpt-image-2");
        awaitDone(submit("/v1/images/generations", u.bearer(), Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", recipe.versionId()))), u.bearer());
        Signed other = signupWithBalance(0L);
        Recipe kept = createRecipe(other, "IMAGE", "A {{color}} kite.", List.of(variable("color", "red")), "gpt-image-2");

        closure.close(u.id(), null, "203.0.113.9");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipes WHERE user_id = ?", Integer.class, u.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM recipe_versions WHERE recipe_id = ?::uuid", Integer.class, recipe.id())).isZero();
        // Another account's recipes are untouched.
        http.get().uri("/v1/recipes/{id}", kept.id()).header(HttpHeaders.AUTHORIZATION, other.bearer()).exchange()
                .expectStatus().isOk();
    }

    // ===== Helpers ====================================================

    private record Signed(UUID id, String bearer) { }

    private record Recipe(String id, String versionId) { }

    private Signed signupWithBalance(long cents) {
        String email = "recipe-run-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Recipe Runner"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        return new Signed(userId, "Bearer " + resp.accessToken());
    }

    private String apiKeyBearer(Signed u) {
        Map<?, ?> key = http.post().uri("/v1/keys")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "recipe-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return "Bearer " + key.get("plaintext");
    }

    private static Map<String, String> variable(String name, String fallback) {
        return Map.of("name", name, "default", fallback);
    }

    private Recipe createRecipe(Signed u, String modality, String template, List<Map<String, String>> variables, String model) {
        Map<String, Object> body = new HashMap<>();
        body.put("title", "A test recipe");
        body.put("modality", modality);
        body.put("template", template);
        body.put("variables", variables);
        body.put("model", model);
        body.put("params", Map.of("n", 1));
        Map<?, ?> created = http.post().uri("/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return new Recipe((String) created.get("id"), (String) ((Map<?, ?>) created.get("latest_version")).get("id"));
    }

    private String newVersion(Signed u, String recipeId, String template, List<Map<String, String>> variables, String model) {
        Map<?, ?> version = http.post().uri("/v1/recipes/{id}/versions", recipeId)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("template", template, "variables", variables, "model", model, "params", Map.of("n", 1)))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) version.get("id");
    }

    private UUID submit(String path, String bearer, Map<String, Object> body) {
        return http.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(AsyncJobAcknowledged.class).returnResult().getResponseBody().jobId();
    }

    private void refused(String path, Signed u, Map<String, Object> body, int status, String code) {
        refusedWithJobs(path, u, body, status, code, 0);
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    /** The request is refused with this code, and nothing was charged for it: no new ledger row, no new job. */
    private void refusedWithJobs(String path, Signed u, Map<String, Object> body, int status, String code, int jobsBefore) {
        long rowsBefore = ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 50)).getTotalElements();
        long balanceBefore = ledger.authoritativeBalance(u.id());
        http.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(code);
        assertThat(ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 50)).getTotalElements()).isEqualTo(rowsBefore);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(balanceBefore);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).hasSize(jobsBefore);
    }

    private void expectNothingCharged(Signed u, long balanceCents) {
        assertThat(ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 10)).getTotalElements()).isEqualTo(1);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(balanceCents);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(u.id())).isEmpty();
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    private JobView awaitTerminal(UUID jobId, String bearer) throws InterruptedException {
        JobView view = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId)
                    .header(HttpHeaders.AUTHORIZATION, bearer)
                    .exchange()
                    .expectStatus().isOk()
                    .expectBody(JobView.class).returnResult().getResponseBody();
            if (view != null && (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED)) return view;
            Thread.sleep(100);
        }
        return view;
    }

    private void awaitDone(UUID jobId, String bearer) throws InterruptedException {
        JobView view = awaitTerminal(jobId, bearer);
        assertThat(view).isNotNull();
        assertThat(view.status()).isEqualTo(JobStatus.DONE);
    }

    private WebTestClient.BodyContentSpec readJob(UUID jobId, String bearer) {
        return http.get().uri("/v1/jobs/{id}", jobId)
                .header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody();
    }

    private String storedRequest(UUID jobId) {
        return jdbc.queryForObject("SELECT prompt_json::text FROM generation_jobs WHERE id = ?", String.class, jobId);
    }

    private static void stubImage() {
        String b64 = Base64.getEncoder().encodeToString(TINY_PNG);
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + b64 + "\"}]}")));
    }

    private static void stubVideo() {
        openAiMock.stubFor(post(urlEqualTo("/v1/videos"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_recipe\",\"status\":\"queued\"}")));
        openAiMock.stubFor(get(urlPathMatching("/v1/videos/vid_recipe"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"vid_recipe\",\"status\":\"completed\"}")));
        openAiMock.stubFor(get(urlEqualTo("/v1/videos/vid_recipe/content"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "video/mp4")
                        .withBody("FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8))));
    }
}
