package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.containers.MinIOContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.CommunityFixture.Ran;
import ai.godance.web.CommunityFixture.Signed;
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

    // ===== Helpers ======================================================

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
