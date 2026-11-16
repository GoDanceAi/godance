package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
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

import ai.godance.OpenJobsGuard;
import ai.godance.domain.JobStatus;
import ai.godance.domain.UserRole;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.DataRetentionService;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.CommunityFixture.Published;
import ai.godance.web.CommunityFixture.Ran;
import ai.godance.web.CommunityFixture.Signed;
import ai.godance.web.dto.ImageDtos.JobView;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Published recipes on the public routes (round 14 brief 01): nothing is
 * public before a moderator approves it, and what is public is exactly the
 * fields the contract lists, never an account id, an email, a source job or
 * an input file.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class PublishedRecipeIntegrationTest {

    /** Every key of a public recipe, and no other. */
    static final Set<String> RECIPE_KEYS = Set.of("public_id", "title", "modality", "template", "variables",
            "inputs", "model", "params", "examples", "made_from", "runs", "runners", "licence", "author",
            "tags", "published_at");
    static final Set<String> EXAMPLE_KEYS = Set.of("url", "content_type");
    static final Set<String> AUTHOR_KEYS = Set.of("handle", "followers");
    static final Set<String> VARIABLE_KEYS = Set.of("name", "default");

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
        r.add("godance.community.enabled", () -> "true");
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired CreditLedgerService ledger;
    @Autowired ObjectMapper json;
    @Autowired DataRetentionService retention;
    @Autowired ObjectStorageClient storage;
    @Autowired GenerationJobRepository jobs;
    @Autowired JdbcTemplate jdbc;

    CommunityFixture community;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        CommunityFixture.stubImage(openAiMock);
        community = new CommunityFixture(http, users, twoFactors, ledger);
    }

    // ===== Nothing public before approval ==============================

    @Test
    void publicRecipes_givenPendingThenApproved_expectsAbsentThenExactFields() {
        Signed author = community.author(100_00L);
        String handle = community.profile(author, "mina");
        Ran ran = community.recipeWithRun(author, "Teapot still life");
        String publicId = community.publish(author, ran, List.of(ran.jobId()));

        // PENDING: on no public route.
        assertThat(publicIds(listPublic())).doesNotContain(publicId);
        http.get().uri("/v1/public/recipes/{id}", publicId).exchange().expectStatus().isNotFound();

        Signed moderator = community.staff(UserRole.SUPPORT);
        community.approve(moderator, publicId);

        // PUBLIC: in the list and on its own route, with exactly the listed fields.
        JsonNode listed = only(listPublic(), publicId);
        String body = http.get().uri("/v1/public/recipes/{id}", publicId).exchange()
                .expectStatus().isOk()
                .expectHeader().value("Cache-Control", value -> assertThat(value).contains("max-age="))
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode recipe = json.readTree(body);
        for (JsonNode node : List.of(listed, recipe)) {
            assertThat(node.propertyNames()).containsExactlyInAnyOrderElementsOf(RECIPE_KEYS);
            assertThat(node.get("public_id").asString()).isEqualTo(publicId);
            assertThat(node.get("title").asString()).isEqualTo("Teapot still life");
            assertThat(node.get("modality").asString()).isEqualTo("image");
            assertThat(node.get("template").asString()).isEqualTo("A {{thing}} on a wooden table, soft window light.");
            assertThat(node.get("variables")).hasSize(1);
            assertThat(node.get("variables").get(0).propertyNames()).containsExactlyInAnyOrderElementsOf(VARIABLE_KEYS);
            assertThat(node.get("inputs")).isEmpty();
            assertThat(node.get("model").asString()).isEqualTo("gpt-image-2");
            assertThat(node.get("params").get("size").asString()).isEqualTo("1024x1024");
            assertThat(node.get("examples")).hasSize(1);
            assertThat(node.get("examples").get(0).propertyNames()).containsExactlyInAnyOrderElementsOf(EXAMPLE_KEYS);
            assertThat(node.get("examples").get(0).get("content_type").asString()).startsWith("image/");
            assertThat(node.get("made_from").isNull()).isTrue();
            assertThat(node.get("runs").asLong()).isZero();
            assertThat(node.get("runners").asLong()).isZero();
            assertThat(node.get("licence").asString()).isEqualTo("CC-BY-4.0");
            assertThat(node.get("author").propertyNames()).containsExactlyInAnyOrderElementsOf(AUTHOR_KEYS);
            assertThat(node.get("author").get("handle").asString()).isEqualTo(handle);
            assertThat(node.get("author").get("followers").asLong()).isZero();
        }
        // Nothing private, anywhere in the body.
        assertThat(body).doesNotContain(author.id().toString(), author.email(), ran.jobId().toString(),
                ran.recipeId(), ran.versionId());
    }

    // ===== Examples are copies =========================================

    @Test
    void approve_givenSourceJobPurgedAfterwards_expectsExampleStillServed() throws Exception {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "copier", List.of());
        String prefix = "community/" + pub.publicId() + "/";
        assertThat(storage.list(prefix)).hasSize(1);

        // The nightly purge deletes the source job's files, 90 days on.
        assertThat(retention.purgeJob(pub.ran().jobId(), Instant.now())).isTrue();
        assertThat(storage.list("images/" + pub.ran().jobId() + "/")).isEmpty();

        // The copy is untouched, and the public route still serves it.
        assertThat(storage.list(prefix)).hasSize(1);
        JsonNode recipe = publicRecipe(pub.publicId());
        assertThat(recipe.get("examples")).hasSize(1);
        String link = exampleLocation(pub.publicId(), 1);
        HttpResponse<byte[]> image = HttpClient.newHttpClient().send(
                HttpRequest.newBuilder(URI.create(link)).GET().build(), HttpResponse.BodyHandlers.ofByteArray());
        assertThat(image.statusCode()).isEqualTo(200);
        assertThat(image.body()).isEqualTo(storage.download(storage.list(prefix).get(0).key()));
        // The nightly steps that touch job media and community copies leave it too.
        retention.purgeExpiredJobs(Instant.now());
        retention.deleteClosedCommunityCopies();
        assertThat(storage.list(prefix)).hasSize(1);
    }

    @Test
    void unpublish_givenPublicRecipe_expectsRemovedEverywhereAndCopiesDeleted() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "leaver", List.of());
        assertThat(storage.list("community/" + pub.publicId() + "/")).hasSize(1);

        http.delete().uri("/v1/recipes/{id}/publish", pub.ran().recipeId())
                .header("Authorization", pub.author().bearer())
                .exchange()
                .expectStatus().isNoContent();

        http.get().uri("/v1/public/recipes/{id}", pub.publicId()).exchange().expectStatus().isNotFound();
        http.get().uri("/v1/public/recipes/{id}/examples/1", pub.publicId()).exchange().expectStatus().isNotFound();
        http.get().uri("/v1/public/profiles/{handle}", pub.handle()).exchange().expectStatus().isNotFound();
        assertThat(publicIds(listPublic())).doesNotContain(pub.publicId());
        assertThat(storage.list("community/" + pub.publicId() + "/")).isEmpty();
        // Kept for the record: the author sees it removed, with no moderator's reason.
        Map<String, Object> mine = community.submission(pub.author(), pub.ran().recipeId());
        assertThat(mine).containsEntry("status", "REMOVED").containsEntry("reason", null);
        // Unpublishing again: nothing is published.
        String code = CommunityFixture.codeOf(http.delete().uri("/v1/recipes/{id}/publish", pub.ran().recipeId())
                .header("Authorization", pub.author().bearer()).exchange(), 404);
        assertThat(code).isEqualTo("not_published");
    }

    @Test
    void deleteRecipe_givenPublicSubmission_expectsItUnpublishedFirst() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "deleter", List.of());

        http.delete().uri("/v1/recipes/{id}", pub.ran().recipeId())
                .header("Authorization", pub.author().bearer())
                .exchange()
                .expectStatus().isNoContent();

        http.get().uri("/v1/public/recipes/{id}", pub.publicId()).exchange().expectStatus().isNotFound();
        assertThat(storage.list("community/" + pub.publicId() + "/")).isEmpty();
        assertThat(jdbc.queryForObject("SELECT status FROM published_recipes WHERE public_id = ?", String.class,
                pub.publicId())).isEqualTo("REMOVED");
    }

    // ===== Running a published recipe ===================================

    @Test
    void generateImage_givenPublishedRecipe_expectsItRenderedAndRecordedOnTheJob() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "runme", List.of());
        Signed runner = community.account("runner", 100_00L, false);

        UUID jobId = community.runPublished(runner, pub.publicId(), Map.of("thing", "copper kettle"));

        JobView view = community.awaitStatus(jobId, runner.bearer(), JobStatus.DONE);
        assertThat(view.prompt()).isEqualTo("A copper kettle on a wooden table, soft window light.");
        assertThat(view.publishedRecipe()).isEqualTo(pub.publicId());
        // The version is its author's: the runner's job names no recipe of theirs.
        assertThat(view.recipe()).isNull();
        assertThat(jdbc.queryForObject("SELECT recipe_version_id FROM generation_jobs WHERE id = ?", UUID.class, jobId))
                .isNull();
        assertThat(jdbc.queryForObject("SELECT prompt_json->'recipe_variables'->>'thing' FROM generation_jobs WHERE id = ?",
                String.class, jobId)).isEqualTo("copper kettle");
        // The author's recipe is untouched: still the author's one attempt.
        Map<String, Object> recipe = http.get().uri("/v1/recipes/{id}", pub.ran().recipeId())
                .header("Authorization", pub.author().bearer())
                .exchange().expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
        assertThat(((Number) recipe.get("attempts")).longValue()).isEqualTo(1);
        // And the runner has no recipe of their own.
        http.get().uri("/v1/recipes").header("Authorization", runner.bearer()).exchange()
                .expectStatus().isOk().expectBody().json("[]");
    }

    @Test
    void generateImage_givenPublishedRecipeNotPublic_expects404AndNothingCharged() {
        Signed author = community.author(100_00L);
        community.profile(author, "waiting");
        Ran ran = community.recipeWithRun(author, "Waiting recipe");
        String publicId = community.publish(author, ran, List.of(ran.jobId()));
        Signed runner = community.account("runner", 100_00L, true);

        String code = CommunityFixture.codeOf(http.post().uri("/v1/images/generations")
                .header("Authorization", runner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "recipe", Map.of("published_id", publicId)))
                .exchange(), 404);

        assertThat(code).isEqualTo("recipe_not_found");
        assertThat(ledger.authoritativeBalance(runner.id())).isEqualTo(100_00L);
        assertThat(jobs.findByUserIdOrderByCreatedAtDesc(runner.id())).isEmpty();
    }

    @Test
    void generateImage_givenPublishedIdAndVersionId_expects400() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "both", List.of());
        http.post().uri("/v1/images/generations")
                .header("Authorization", pub.author().bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "recipe",
                        Map.of("published_id", pub.publicId(), "version_id", pub.ran().versionId())))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void publicRecipe_givenRunsByOthersAndByItsAuthor_expectsCountsWithoutTheAuthor() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "counted", List.of());
        // The author's own run is not counted.
        community.runPublished(pub.author(), pub.publicId(), Map.of());
        Signed a = community.account("runner-a", 100_00L, true);
        Signed b = community.account("runner-b", 100_00L, true);
        community.runPublished(a, pub.publicId(), Map.of("thing", "bowl"));
        community.runPublished(a, pub.publicId(), Map.of("thing", "vase"));
        community.runPublished(b, pub.publicId(), Map.of());

        JsonNode recipe = publicRecipe(pub.publicId());
        assertThat(recipe.get("runs").asLong()).isEqualTo(3);
        assertThat(recipe.get("runners").asLong()).isEqualTo(2);
    }

    @Test
    void publicRecipe_givenFailedRun_expectsItNotCounted() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "failing", List.of());
        Signed runner = community.account("runner", 100_00L, true);
        openAiMock.resetAll();
        openAiMock.stubFor(com.github.tomakehurst.wiremock.client.WireMock.post(
                com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo("/v1/images/generations"))
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse().withStatus(500)));
        UUID jobId = community.submit(runner.bearer(), Map.of("model", "gpt-image-2",
                "recipe", Map.of("published_id", pub.publicId())));
        community.awaitStatus(jobId, runner.bearer(), JobStatus.FAILED);

        assertThat(publicRecipe(pub.publicId()).get("runs").asLong()).isZero();
    }

    // ===== Where a recipe came from, tags and pages =====================

    @Test
    void publicRecipe_givenRecipeStartedFromGalleryOrPublished_expectsMadeFrom() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Signed first = community.author(100_00L);
        community.profile(first, "gallery-fan");
        Ran fromGallery = community.recipeWithRun(first, "From the gallery",
                Map.of("source_gallery_recipe", "paper-lantern-street-at-dusk"));
        String firstId = community.publish(first, fromGallery, List.of(fromGallery.jobId()));
        community.approve(moderator, firstId);
        assertThat(publicRecipe(firstId).get("made_from").get("kind").asString()).isEqualTo("gallery");
        assertThat(publicRecipe(firstId).get("made_from").get("id").asString()).isEqualTo("paper-lantern-street-at-dusk");

        Signed second = community.author(100_00L);
        community.profile(second, "remixer");
        Ran fromPublished = community.recipeWithRun(second, "From a published recipe",
                Map.of("source_published_recipe", firstId));
        String secondId = community.publish(second, fromPublished, List.of(fromPublished.jobId()));
        community.approve(moderator, secondId);
        JsonNode madeFrom = publicRecipe(secondId).get("made_from");
        assertThat(madeFrom.get("kind").asString()).isEqualTo("published");
        assertThat(madeFrom.get("id").asString()).isEqualTo(firstId);

        // Once the source is no longer public, it is never named.
        community.moderate(moderator, firstId, "remove", "test removal").expectStatus().isOk();
        assertThat(publicRecipe(secondId).get("made_from").isNull()).isTrue();
    }

    @Test
    void createRecipe_givenSourcePublishedRecipeNotPublic_expects400() {
        Signed author = community.author(100_00L);
        http.post().uri("/v1/recipes")
                .header("Authorization", author.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("title", "Orphan", "modality", "image", "template", "A cup",
                        "model", "gpt-image-2", "source_published_recipe", "zzzzzzzzzzzz"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void publicRecipes_givenTagAndPages_expectsOnlyTaggedNewestFirstAndPaged() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        String tag = "t-" + UUID.randomUUID().toString().substring(0, 8);
        Published oldest = community.publicRecipe(moderator, "paged-a", List.of(tag, "product"));
        Published middle = community.publicRecipe(moderator, "paged-b", List.of(tag));
        Published newest = community.publicRecipe(moderator, "paged-c", List.of(tag));
        community.publicRecipe(moderator, "untagged", List.of());

        JsonNode tagged = json.readTree(http.get().uri("/v1/public/recipes?tag={tag}", tag).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
        assertThat(publicIds(tagged)).containsExactly(newest.publicId(), middle.publicId(), oldest.publicId());
        assertThat(tagged.get(2).get("tags").valueStream().map(JsonNode::asString).toList())
                .containsExactly(tag, "product");

        JsonNode first = json.readTree(http.get().uri("/v1/public/recipes?tag={tag}&limit=2", tag).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
        assertThat(publicIds(first)).containsExactly(newest.publicId(), middle.publicId());
        JsonNode next = json.readTree(http.get().uri("/v1/public/recipes?tag={tag}&limit=2&before={b}", tag,
                middle.publicId()).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
        assertThat(publicIds(next)).containsExactly(oldest.publicId());

        http.get().uri("/v1/public/recipes?limit=0").exchange().expectStatus().isBadRequest();
        http.get().uri("/v1/public/recipes?tag=Not A Tag").exchange().expectStatus().isBadRequest();
    }

    @Test
    void publicProfile_givenApprovedAuthor_expectsApprovedFieldsFollowersAndRecipesOnly() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        Published pub = community.publicRecipe(moderator, "profiled", List.of());

        String body = http.get().uri("/v1/public/profiles/{handle}", pub.handle()).exchange()
                .expectStatus().isOk()
                .expectHeader().value("Cache-Control", value -> assertThat(value).contains("max-age="))
                .expectBody(String.class).returnResult().getResponseBody();
        JsonNode profile = json.readTree(body);
        assertThat(profile.propertyNames()).containsExactlyInAnyOrder("handle", "display_name", "link", "followers", "recipes");
        assertThat(profile.get("display_name").asString()).isEqualTo("Display profiled");
        assertThat(profile.get("link").asString()).isEqualTo("https://example.test/profiled");
        assertThat(profile.get("followers").asLong()).isZero();
        assertThat(publicIds(profile.get("recipes"))).containsExactly(pub.publicId());
        assertThat(profile.get("recipes").get(0).propertyNames()).containsExactlyInAnyOrderElementsOf(RECIPE_KEYS);
        assertThat(body).doesNotContain(pub.author().id().toString(), pub.author().email());
        // An account with a profile and no public recipe has no public page.
        Signed quiet = community.author(0);
        String quietHandle = community.profile(quiet, "quiet");
        http.get().uri("/v1/public/profiles/{handle}", quietHandle).exchange().expectStatus().isNotFound();
    }

    // ===== Helpers ======================================================

    private JsonNode publicRecipe(String publicId) {
        return json.readTree(http.get().uri("/v1/public/recipes/{id}", publicId).exchange()
                .expectStatus().isOk().expectBody(String.class).returnResult().getResponseBody());
    }

    private String exampleLocation(String publicId, int position) {
        URI location = http.get().uri("/v1/public/recipes/{id}/examples/{n}", publicId, position).exchange()
                .expectStatus().isFound()
                .expectHeader().value("Cache-Control", value -> assertThat(value).contains("max-age="))
                .returnResult(Void.class).getResponseHeaders().getLocation();
        assertThat(location).isNotNull();
        return location.toString();
    }

    private JsonNode listPublic() {
        return json.readTree(http.get().uri("/v1/public/recipes?limit=100").exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody());
    }

    private static List<String> publicIds(JsonNode list) {
        assertThat(list.isArray()).isTrue();
        return list.valueStream().map(node -> node.get("public_id").asString()).toList();
    }

    private static JsonNode only(JsonNode list, String publicId) {
        List<JsonNode> matches = list.valueStream()
                .filter(node -> publicId.equals(node.get("public_id").asString())).toList();
        assertThat(matches).hasSize(1);
        return matches.get(0);
    }
}
