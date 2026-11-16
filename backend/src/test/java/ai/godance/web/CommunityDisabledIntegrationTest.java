package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.CommunityFixture.Ran;
import ai.godance.web.CommunityFixture.Signed;

/**
 * The community ships off (round 14 brief 01): with
 * {@code godance.community.enabled} at its default, false, nothing can be
 * set, published, followed, reported or run, and every public route answers
 * 404 {@code community_disabled}. The staff routes stay open.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class CommunityDisabledIntegrationTest {

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
        // No godance.community.enabled: the default is what this checks.
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
    @Autowired JdbcTemplate jdbc;

    CommunityFixture community;

    @BeforeEach
    void reset() {
        openAiMock.resetAll();
        CommunityFixture.stubImage(openAiMock);
        community = new CommunityFixture(http, users, twoFactors, ledger);
    }

    @Test
    void publicRoutes_givenCommunityOff_expect404CommunityDisabled() {
        for (String path : List.of("/v1/public/recipes", "/v1/public/recipes/abcdefabcdef",
                "/v1/public/recipes/abcdefabcdef/examples/1", "/v1/public/profiles/mina")) {
            assertThat(CommunityFixture.codeOf(http.get().uri(path).exchange(), 404)).as(path).isEqualTo("community_disabled");
        }
    }

    @Test
    void accountRoutes_givenCommunityOff_expect404CommunityDisabled() {
        Signed author = community.author(100_00L);
        assertThat(CommunityFixture.codeOf(http.get().uri("/v1/profile").header("Authorization", author.bearer())
                .exchange(), 404)).isEqualTo("community_disabled");
        assertThat(CommunityFixture.codeOf(community.putProfile(author, "mina-off", "Mina", null), 404))
                .isEqualTo("community_disabled");
        Ran ran = community.recipeWithRun(author, "Off");
        assertThat(CommunityFixture.codeOf(community.postPublish(author, ran.recipeId(),
                CommunityFixture.publishBody(1, "CC-BY-4.0", List.of(ran.jobId()))), 404)).isEqualTo("community_disabled");
        assertThat(CommunityFixture.codeOf(community.follow(author, "mina"), 404)).isEqualTo("community_disabled");
        assertThat(CommunityFixture.codeOf(community.report(author, "profile", "mina", "x"), 404))
                .isEqualTo("community_disabled");
        assertThat(CommunityFixture.codeOf(http.get().uri("/v1/follows/feed").header("Authorization", author.bearer())
                .exchange(), 404)).isEqualTo("community_disabled");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM published_recipes", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM profiles", Long.class)).isZero();
    }

    @Test
    void generateImage_givenPublishedIdWhileOff_expects404RecipeNotFoundAndNothingCharged() {
        Signed runner = community.account("runner", 100_00L, true);
        String code = CommunityFixture.codeOf(http.post().uri("/v1/images/generations")
                .header("Authorization", runner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "recipe", Map.of("published_id", "abcdefabcdef")))
                .exchange(), 404);
        assertThat(code).isEqualTo("recipe_not_found");
        assertThat(ledger.authoritativeBalance(runner.id())).isEqualTo(100_00L);
    }

    @Test
    void ownRecipeRuns_givenCommunityOff_expectUnchanged() {
        Signed author = community.author(100_00L);
        Ran ran = community.recipeWithRun(author, "Still mine");
        Map<String, Object> job = http.get().uri("/v1/jobs/{id}", ran.jobId()).header("Authorization", author.bearer())
                .exchange().expectStatus().isOk()
                .expectBody(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() { })
                .returnResult().getResponseBody();
        // A job that ran no published recipe has no publishedRecipe field at all, as before this round.
        assertThat(job).doesNotContainKey("publishedRecipe");
        assertThat(job.get("recipe")).isNotNull();
    }

    @Test
    void moderationRoutes_givenCommunityOff_expectStillOpenToStaff() {
        Signed moderator = community.staff(UserRole.SUPPORT);
        http.get().uri("/v1/admin/community/queue").header("Authorization", moderator.bearer()).exchange()
                .expectStatus().isOk().expectBody().json("[]");
    }
}
