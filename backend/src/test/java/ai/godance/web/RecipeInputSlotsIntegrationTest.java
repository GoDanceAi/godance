package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
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
import com.github.tomakehurst.wiremock.http.Request;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ai.godance.OpenJobsGuard;
import ai.godance.domain.AgeStatus;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.User;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ImageDtos.JobView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Input slots on a recipe (V35), with inputs switched on: the rules for a
 * slot, a new version when the slots change, and running a recipe with its
 * slots filled, each placed by its role: an image recipe as an edit, a
 * video recipe through the video input fields.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class RecipeInputSlotsIntegrationTest {

    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAi;
    static WireMockServer ark;

    @BeforeAll
    static void startMocks() {
        openAi = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAi.start();
        ark = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        ark.start();
    }

    @AfterAll
    static void stopMocks() {
        if (openAi != null) openAi.stop();
        if (ark != null) ark.stop();
    }

    @DynamicPropertySource
    static void dynamicProps(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAi.baseUrl());
        r.add("godance.providers.volcengine.api-base", () -> ark.baseUrl());
        r.add("godance.providers.volcengine.poll-interval", () -> "50ms");
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
        r.add("godance.inputs.enabled", () -> "true");
        r.add("godance.inputs.allow-people", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired GenerationJobOutputRepository outputRows;
    @Autowired InputFileRepository files;
    @Autowired ObjectStorageClient storage;
    @Autowired ObjectMapper json;

    @BeforeEach
    void stubProviders() {
        openAi.resetAll();
        ark.resetAll();
        String images = "{\"created\":1700000000,\"data\":[{\"b64_json\":\"" + Base64.getEncoder().encodeToString(TINY_PNG) + "\"}]}";
        for (String path : List.of("/v1/images/edits", "/v1/images/generations")) {
            openAi.stubFor(post(urlEqualTo(path)).willReturn(okJson(images)));
        }
        ark.stubFor(post(urlEqualTo("/api/v3/contents/generations/tasks")).willReturn(okJson("{\"id\":\"task_1\"}")));
        ark.stubFor(get(urlPathMatching("/api/v3/contents/generations/tasks/task_1")).willReturn(okJson(
                "{\"id\":\"task_1\",\"status\":\"succeeded\",\"content\":{\"video_url\":\"" + ark.baseUrl() + "/_test/out.mp4\"}}")));
        ark.stubFor(get(urlEqualTo("/_test/out.mp4")).willReturn(
                aResponse().withStatus(200).withHeader("content-type", "video/mp4").withBody("FAKE-MP4".getBytes())));
    }

    // ===== Running a recipe with its slots filled =====================

    @Test
    void generateImage_givenARecipeWithAnImageSlotFilled_expectsAnEditJobWithThatInput() throws Exception {
        Account me = account(100_00L);
        UUID photo = upload(me, InputFixtures.image("plain.png"), "product.png");
        JsonNode recipe = createRecipe(me, "IMAGE", "Place the product on a {{surface}}.", "gpt-image-2",
                List.of(slot("product photo", "image", "One product on a plain background")));

        // The request goes to the generations route, with no images of its own: the server decides, from the slots.
        JobView done = run(me, "/v1/images/generations", Map.of("model", "gpt-image-2", "recipe", Map.of(
                "version_id", versionId(recipe), "variables", Map.of("surface", "marble counter"),
                "inputs", Map.of("product photo", Map.of("file_id", photo.toString())))));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.inputs()).hasSize(1);
        assertThat(done.inputs().get(0).role()).isEqualTo("image");
        assertThat(done.inputs().get(0).fileId()).isEqualTo(photo);
        // It ran as an edit: the provider's edits endpoint got the rendered prompt and the stored image.
        LoggedRequest sent = onlyPost(openAi, "/v1/images/edits");
        assertThat(part(sent, "prompt").getBody().asString()).isEqualTo("Place the product on a marble counter.");
        assertThat(part(sent, "image").getBody().asBytes()).isEqualTo(stored(photo));
        assertThat(openAi.findAll(postRequestedFor(urlEqualTo("/v1/images/generations")))).isEmpty();
        // And it is an attempt of the recipe, as any run of it is.
        assertThat(jobs.findById(done.id()).orElseThrow().getRecipeVersionId()).isEqualTo(UUID.fromString(versionId(recipe)));
    }

    @Test
    void generateImage_givenARecipeWithoutSlots_expectsAGenerationAsBefore() throws Exception {
        Account me = account(100_00L);
        JsonNode recipe = createRecipe(me, "IMAGE", "A {{color}} lighthouse.", "gpt-image-2", List.of());

        JobView done = run(me, "/v1/images/generations", Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", versionId(recipe), "variables", Map.of("color", "red"))));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.inputs()).isEmpty();
        assertThat(onlyPost(openAi, "/v1/images/generations").getBodyAsString()).contains("A red lighthouse.");
        assertThat(openAi.findAll(postRequestedFor(urlEqualTo("/v1/images/edits")))).isEmpty();
    }

    @Test
    void generateImage_givenSeveralImageSlotsAndAJobOutput_expectsThemInTheSlotsOrder() throws Exception {
        Account me = account(100_00L);
        UUID photo = upload(me, InputFixtures.image("plain.png"), "product.png");
        GenerationJob source = doneImageJob(me.id(), InputFixtures.image("progressive.jpg"));
        JsonNode recipe = createRecipe(me, "IMAGE", "The product in the room.", "gpt-image-2",
                List.of(slot("product", "image", "The product"), slot("room", "image", "The room")));

        // The request names the slots in another order; the recipe's order is what counts.
        Map<String, Object> inputs = new LinkedHashMap<>();
        inputs.put("room", Map.of("job_output", Map.of("job_id", source.getId().toString(), "index", 0)));
        inputs.put("product", Map.of("file_id", photo.toString()));
        JobView done = run(me, "/v1/images/edits", Map.of("model", "gpt-image-2",
                "recipe", Map.of("version_id", versionId(recipe), "inputs", inputs)));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("image", "image");
        assertThat(done.inputs().get(0).fileId()).isEqualTo(photo);
        assertThat(done.inputs().get(1).jobOutput().jobId()).isEqualTo(source.getId());
        List<Request.Part> sent = new ArrayList<>();
        for (Request.Part each : onlyPost(openAi, "/v1/images/edits").getParts()) if (each.getName().equals("image[]")) sent.add(each);
        assertThat(sent).hasSize(2);
        assertThat(sent.get(0).getBody().asBytes()).isEqualTo(stored(photo));
        assertThat(sent.get(1).getBody().asBytes()).isEqualTo(InputFixtures.image("progressive.jpg"));
    }

    @Test
    void generateVideo_givenFirstAndLastFrameSlots_expectsEachPlacedByItsRole() throws Exception {
        Account me = account(100_00L);
        UUID start = upload(me, InputFixtures.image("plain.jpg"), "start.jpg");
        UUID end = upload(me, InputFixtures.image("plain.png"), "end.png");
        // Declared last frame first: the role decides where each goes, not the order.
        JsonNode recipe = createRecipe(me, "VIDEO", "{{subject}} drifts away.", "seedance-2.0",
                List.of(slot("ending", "last_frame", ""), slot("opening", "first_frame", "The still the clip starts from")));

        JobView done = run(me, "/v1/videos/generations", Map.of("model", "seedance-2.0", "seconds", 5, "recipe", Map.of(
                "version_id", versionId(recipe), "variables", Map.of("subject", "The boat"),
                "inputs", Map.of("opening", Map.of("file_id", start.toString()), "ending", Map.of("file_id", end.toString())))));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        JsonNode content = json.readTree(onlyPost(ark, "/api/v3/contents/generations/tasks").getBodyAsString()).get("content");
        assertThat(content.get(0).get("text").asString()).isEqualTo("The boat drifts away.");
        assertThat(content.get(1).get("role").asString()).isEqualTo("first_frame");
        assertThat(content.get(1).get("image_url").get("url").asString()).isEqualTo(dataUrl("image/jpeg", stored(start)));
        assertThat(content.get(2).get("role").asString()).isEqualTo("last_frame");
        assertThat(content.get(2).get("image_url").get("url").asString()).isEqualTo(dataUrl("image/png", stored(end)));
        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("first_frame", "last_frame");
    }

    @Test
    void generateVideo_givenReferenceImageSlots_expectsThemInTheSlotsOrder() throws Exception {
        Account me = account(100_00L);
        UUID a = upload(me, InputFixtures.image("plain.png"), "a.png");
        UUID b = upload(me, InputFixtures.image("plain.webp"), "b.webp");
        JsonNode recipe = createRecipe(me, "VIDEO", "Image 1 in the place from Image 2.", "seedance-2.0",
                List.of(slot("subject", "reference_image", "Who or what"), slot("place", "reference_image", "Where")));

        JobView done = run(me, "/v1/videos/generations", Map.of("model", "seedance-2.0", "seconds", 5, "recipe", Map.of(
                "version_id", versionId(recipe),
                "inputs", Map.of("place", Map.of("file_id", b.toString()), "subject", Map.of("file_id", a.toString())))));

        assertThat(done.status()).isEqualTo(JobStatus.DONE);
        JsonNode content = json.readTree(onlyPost(ark, "/api/v3/contents/generations/tasks").getBodyAsString()).get("content");
        assertThat(content).hasSize(3);
        assertThat(content.get(1).get("image_url").get("url").asString()).isEqualTo(dataUrl("image/png", stored(a)));
        assertThat(content.get(2).get("image_url").get("url").asString()).isEqualTo(dataUrl("image/webp", stored(b)));
        assertThat(done.inputs()).extracting(input -> input.role()).containsExactly("reference_image", "reference_image");
    }

    // ===== Refused before any charge ==================================

    @Test
    void generate_givenASlotWithNoValue_expects400RecipeInputMissingNamingTheSlot() {
        Account me = account(100_00L);
        UUID photo = upload(me, InputFixtures.image("plain.png"), "product.png");
        JsonNode recipe = createRecipe(me, "IMAGE", "The product in the room.", "gpt-image-2",
                List.of(slot("product", "image", ""), slot("room", "image", "")));
        String version = versionId(recipe);

        // No inputs at all, and one of two: each names the first slot that is missing.
        refused(me, "/v1/images/generations", Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", version)),
                400, "recipe_input_missing").jsonPath("$.slot").isEqualTo("product");
        refused(me, "/v1/images/generations", Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", version,
                "inputs", Map.of("product", Map.of("file_id", photo.toString())))),
                400, "recipe_input_missing").jsonPath("$.slot").isEqualTo("room");
        refused(me, "/v1/images/edits", Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", version)),
                400, "recipe_input_missing");
        // A name the recipe has no slot for.
        refused(me, "/v1/images/generations", Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", version,
                "inputs", Map.of("product", Map.of("file_id", photo.toString()), "room", Map.of("file_id", photo.toString()),
                        "sky", Map.of("file_id", photo.toString())))),
                400, "recipe_input_unknown").jsonPath("$.slot").isEqualTo("sky");
    }

    @Test
    void generate_givenInputsOutsideARecipeThatNamesItsOwn_expects400() {
        Account me = account(100_00L);
        UUID photo = upload(me, InputFixtures.image("plain.png"), "product.png");
        Map<String, Object> ref = Map.of("file_id", photo.toString());
        JsonNode image = createRecipe(me, "IMAGE", "The product.", "gpt-image-2", List.of(slot("product", "image", "")));
        JsonNode video = createRecipe(me, "VIDEO", "It moves.", "seedance-2.0", List.of(slot("opening", "first_frame", "")));

        refused(me, "/v1/images/edits", Map.of("model", "gpt-image-2", "images", List.of(ref),
                "recipe", Map.of("version_id", versionId(image), "inputs", Map.of("product", ref))), 400, "recipe_inputs_conflict");
        refused(me, "/v1/videos/generations", Map.of("model", "seedance-2.0", "seconds", 5, "reference_images", List.of(ref),
                "recipe", Map.of("version_id", versionId(video), "inputs", Map.of("opening", ref))), 400, "recipe_inputs_conflict");
    }

    @Test
    void generate_givenAnotherUsersFileInASlot_expects404AndNothingCharged() {
        Account me = account(100_00L);
        Account other = account(100_00L);
        UUID theirs = upload(other, InputFixtures.image("plain.png"), "theirs.png");
        JsonNode recipe = createRecipe(me, "IMAGE", "The product.", "gpt-image-2", List.of(slot("product", "image", "")));

        refused(me, "/v1/images/generations", Map.of("model", "gpt-image-2", "recipe", Map.of("version_id", versionId(recipe),
                "inputs", Map.of("product", Map.of("file_id", theirs.toString())))), 404, "file_not_found");
        // A model that takes no input images cannot run a recipe that has them.
        refused(me, "/v1/images/generations", Map.of("model", "imagen-4", "recipe", Map.of("version_id", versionId(recipe),
                "inputs", Map.of("product", Map.of("file_id", upload(me, InputFixtures.image("plain.png"), "mine.png").toString())))),
                400, "input_not_supported");
    }

    // ===== The slots themselves =======================================

    @Test
    void createRecipe_givenSlots_expectsThemOnTheVersionAndInTheContractsShape() {
        Account me = account(0L);

        JsonNode recipe = createRecipe(me, "IMAGE", "The product on a counter.", "gpt-image-2",
                List.of(slot("product photo", "image", "  One product on a plain background  ")));

        assertThat(recipe.get("latest_version").get("inputs")).isEqualTo(json.readTree(
                "[{\"slot\":\"product photo\",\"role\":\"image\",\"hint\":\"One product on a plain background\"}]"));
        // A recipe saved without slots has an empty list, never a missing key.
        JsonNode plain = createRecipe(me, "IMAGE", "A lighthouse.", "gpt-image-2", List.of());
        assertThat(plain.get("latest_version").get("inputs")).isEqualTo(json.readTree("[]"));
        JsonNode read = json.readTree(http.get().uri("/v1/recipes/{id}", recipe.get("id").asString())
                .header(HttpHeaders.AUTHORIZATION, me.bearer()).exchange().expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
        assertThat(read.get("latest_version").get("inputs").get(0).get("slot").asString()).isEqualTo("product photo");
    }

    @Test
    void createRecipe_givenSlotsThatBreakARule_expects400InvalidRecipe() {
        Account me = account(0L);
        Map<String, Object> ok = slot("product", "image", "");

        // More than four.
        invalid(me, "IMAGE", List.of(ok, slot("b", "image", ""), slot("c", "image", ""), slot("d", "image", ""), slot("e", "image", "")),
                "a recipe has at most 4 inputs");
        // A name the template rule refuses, and the same name twice.
        invalid(me, "IMAGE", List.of(slot("product!", "image", "")), "an input's name is 1–40 ASCII letters");
        invalid(me, "IMAGE", List.of(slot(" product", "image", "")), "an input's name is 1–40 ASCII letters");
        invalid(me, "IMAGE", List.of(ok, slot("product", "image", "")), "the input \"product\" is declared more than once");
        // A role the modality does not use.
        invalid(me, "IMAGE", List.of(slot("product", "first_frame", "")), "must be image in an image recipe");
        invalid(me, "IMAGE", List.of(slot("product", "mask", "")), "must be image in an image recipe");
        invalid(me, "VIDEO", List.of(slot("opening", "image", "")), "must be first_frame or last_frame or reference_image in a video recipe");
        invalid(me, "VIDEO", List.of(slot("a", "first_frame", ""), slot("b", "first_frame", "")), "at most one first_frame input");
        invalid(me, "VIDEO", List.of(slot("a", "last_frame", ""), slot("b", "last_frame", "")), "at most one last_frame input");
        // A hint that is too long.
        invalid(me, "IMAGE", List.of(slot("product", "image", "x".repeat(201))), "the hint of \"product\" is at most 200 characters");

        // Four reference images are fine, and so is a hint left out.
        Map<String, Object> noHint = new LinkedHashMap<>(Map.of("slot", "d", "role", "reference_image"));
        JsonNode video = createRecipe(me, "VIDEO", "It moves.", "seedance-2.0", List.of(slot("a", "reference_image", ""),
                slot("b", "reference_image", ""), slot("c", "reference_image", ""), noHint));
        assertThat(video.get("latest_version").get("inputs")).hasSize(4);
        assertThat(video.get("latest_version").get("inputs").get(3).get("hint").asString()).isEmpty();
    }

    @Test
    void newVersion_givenChangedSlots_expectsANewVersionAndTheSameSlotsNone() {
        Account me = account(0L);
        JsonNode recipe = createRecipe(me, "IMAGE", "The product.", "gpt-image-2", List.of(slot("product", "image", "One product")));
        String id = recipe.get("id").asString();
        Map<String, Object> same = Map.of("template", "The product.", "model", "gpt-image-2", "variables", List.of(),
                "inputs", List.of(slot("product", "image", "One product")));

        // Exactly what the latest version holds: no new version.
        newVersion(me, id, same).expectStatus().isOk().expectBody().jsonPath("$.number").isEqualTo(1);
        // A changed hint, a renamed slot, a slot added, the slots removed: a new version each time.
        int number = 1;
        for (List<Map<String, Object>> slots : List.of(
                List.of(slot("product", "image", "One product, alone")),
                List.of(slot("product photo", "image", "One product, alone")),
                List.of(slot("product photo", "image", "One product, alone"), slot("room", "image", "")),
                List.<Map<String, Object>>of())) {
            number += 1;
            newVersion(me, id, Map.of("template", "The product.", "model", "gpt-image-2", "variables", List.of(), "inputs", slots))
                    .expectStatus().isCreated().expectBody()
                    .jsonPath("$.number").isEqualTo(number)
                    .jsonPath("$.inputs.length()").isEqualTo(slots.size());
        }
        // A version saved by a client that knows nothing of slots has none.
        newVersion(me, id, Map.of("template", "The product, again.", "model", "gpt-image-2", "variables", List.of()))
                .expectStatus().isCreated().expectBody().jsonPath("$.inputs.length()").isEqualTo(0);
        // The role is checked against the recipe's own modality on a new version too.
        newVersion(me, id, Map.of("template", "The product.", "model", "gpt-image-2", "variables", List.of(),
                "inputs", List.of(slot("opening", "first_frame", ""))))
                .expectStatus().isBadRequest().expectBody().jsonPath("$.code").isEqualTo("invalid_recipe");
    }

    // ===== Helpers ====================================================

    private record Account(UUID id, String bearer) { }

    private Account account(long balanceCents) {
        String email = "recipe-slots-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        User user = users.findByEmail(email).orElseThrow();
        user.setAgeStatus(AgeStatus.ADULT);
        user.setInputsTermsVersion(1);
        user.setInputsTermsAcceptedAt(Instant.now());
        users.save(user);
        if (balanceCents > 0) {
            ledger.topUp(user.getId(), balanceCents, CreditSource.PROMO, "seed-" + user.getId(), "seed-" + user.getId(), "test seed");
        }
        return new Account(user.getId(), bearer);
    }

    private static Map<String, Object> slot(String name, String role, String hint) {
        Map<String, Object> slot = new LinkedHashMap<>();
        slot.put("slot", name);
        slot.put("role", role);
        slot.put("hint", hint);
        return slot;
    }

    private WebTestClient.ResponseSpec postRecipe(Account account, String modality, String template, String model,
                                                  List<Map<String, Object>> slots) {
        List<Map<String, String>> variables = new ArrayList<>();
        java.util.regex.Matcher marker = java.util.regex.Pattern.compile("\\{\\{([^{}]+)\\}\\}").matcher(template);
        while (marker.find()) variables.add(Map.of("name", marker.group(1), "default", "default " + marker.group(1)));
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("title", "Recipe " + UUID.randomUUID());
        body.put("modality", modality);
        body.put("template", template);
        body.put("variables", variables);
        body.put("model", model);
        body.put("inputs", slots);
        return http.post().uri("/v1/recipes")
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private JsonNode createRecipe(Account account, String modality, String template, String model, List<Map<String, Object>> slots) {
        return json.readTree(postRecipe(account, modality, template, model, slots)
                .expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody());
    }

    private void invalid(Account account, String modality, List<Map<String, Object>> slots, String detail) {
        postRecipe(account, modality, "A template.", modality.equals("VIDEO") ? "seedance-2.0" : "gpt-image-2", slots)
                .expectStatus().isBadRequest()
                .expectBody()
                .jsonPath("$.code").isEqualTo("invalid_recipe")
                .jsonPath("$.detail").value(text -> assertThat((String) text).contains(detail));
    }

    private WebTestClient.ResponseSpec newVersion(Account account, String recipeId, Map<String, Object> body) {
        return http.post().uri("/v1/recipes/{id}/versions", recipeId)
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange();
    }

    private static String versionId(JsonNode recipe) {
        return recipe.get("latest_version").get("id").asString();
    }

    private UUID upload(Account account, byte[] bytes, String filename) {
        String body = InputFixtures.upload(http, account.bearer(), bytes, filename, InputFixtures.declaredType(filename))
                .expectStatus().isCreated().expectBody(String.class).returnResult().getResponseBody();
        return UUID.fromString(json.readTree(body).get("id").asString());
    }

    private byte[] stored(UUID fileId) {
        return storage.download(files.findById(fileId).orElseThrow().getStorageKey());
    }

    private GenerationJob doneImageJob(UUID owner, byte[] image) {
        GenerationJob job = new GenerationJob();
        job.setUserId(owner);
        job.setModality(Modality.IMAGE);
        job.setProvider(Provider.OPENAI);
        job.setModel("gpt-image-2");
        job.setPrompt(Map.of("prompt", "a room", "n", 1));
        job.setStatus(JobStatus.DONE);
        job.setEstimatedCostCents(6L);
        job.setActualCostCents(6L);
        job.setRequestId("img_" + UUID.randomUUID());
        job = jobs.save(job);
        ImageMetadata.Format format = ImageMetadata.sniff(image).orElseThrow();
        String key = "images/" + job.getId() + "/0." + format.extension();
        storage.upload(key, image, format.contentType());
        outputRows.save(new GenerationJobOutput(job.getId(), 0, key, format.contentType(), image.length, null));
        return job;
    }

    /** Submits the request, expects a job, and waits for it to end. */
    private JobView run(Account account, String path, Map<String, Object> body) throws InterruptedException {
        String response = http.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isAccepted()
                .expectBody(String.class).returnResult().getResponseBody();
        UUID jobId = UUID.fromString(json.readTree(response).get("job_id").asString());
        JobView view = null;
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            view = http.get().uri("/v1/jobs/{id}", jobId).header(HttpHeaders.AUTHORIZATION, account.bearer()).exchange()
                    .expectStatus().isOk().expectBody(JobView.class).returnResult().getResponseBody();
            if (view.status() == JobStatus.DONE || view.status() == JobStatus.FAILED) return view;
            Thread.sleep(100);
        }
        return view;
    }

    /** Expects the request refused with this status and code, with no job written, nothing charged and no provider called. */
    private WebTestClient.BodyContentSpec refused(Account account, String path, Map<String, Object> body, int status, String code) {
        long balance = users.findById(account.id()).orElseThrow().getCreditBalanceCents();
        int jobsBefore = jobs.findByUserIdOrderByCreatedAtDesc(account.id()).size();
        int calls = openAi.findAll(anyRequestedFor(anyUrl())).size() + ark.findAll(anyRequestedFor(anyUrl())).size();
        WebTestClient.BodyContentSpec response = http.post().uri(path)
                .header(HttpHeaders.AUTHORIZATION, account.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .exchange()
                .expectStatus().isEqualTo(status)
                .expectBody()
                .jsonPath("$.code").isEqualTo(code);
        assertThat(users.findById(account.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(balance);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(account.id())).hasSize(jobsBefore);
        assertThat(openAi.findAll(anyRequestedFor(anyUrl())).size() + ark.findAll(anyRequestedFor(anyUrl())).size()).isEqualTo(calls);
        return response;
    }

    private static LoggedRequest onlyPost(WireMockServer server, String path) {
        List<LoggedRequest> requests = server.findAll(postRequestedFor(urlPathEqualTo(path)));
        assertThat(requests).hasSize(1);
        return requests.get(0);
    }

    private static Request.Part part(LoggedRequest request, String name) {
        for (Request.Part part : request.getParts()) if (part.getName().equals(name)) return part;
        throw new AssertionError("no part named " + name);
    }

    private static String dataUrl(String contentType, byte[] bytes) {
        return "data:" + contentType + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder okJson(String body) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(body);
    }
}
