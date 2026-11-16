package ai.godance.mcp;

import static ai.godance.mcp.McpFixture.call;
import static ai.godance.mcp.McpFixture.job;
import static ai.godance.mcp.McpFixture.ok;
import static ai.godance.mcp.McpFixture.problem;
import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseCookie;
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

import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.spec.McpSchema.CallToolResult;
import io.modelcontextprotocol.spec.McpSchema.InitializeResult;
import io.modelcontextprotocol.spec.McpSchema.Tool;

import ai.godance.OpenJobsGuard;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.ModelCatalog;
import ai.godance.mcp.McpFixture.Account;
import ai.godance.mcp.McpFixture.Key;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.security.SessionCookies;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.InputPersonCheck;
import ai.godance.service.RecipeBatchRunner;
import ai.godance.web.InputFixtures;
import ai.godance.web.MinioTestImage;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Round 13 brief 01: Godance's API as MCP tools at {@code /mcp}, over
 * Streamable HTTP, behind an API key. These tests talk to the server the way
 * an agent's MCP client does: the official MCP Java SDK's client, over HTTP,
 * on a real port, with every provider a WireMock.
 *
 * <p>They cover who may call (an API key; never a session), the transport's
 * rules, each tool's happy path, {@code max_cost_cents} refusing above the
 * price with nothing charged (for a batch too, when a price rises while it
 * runs), a key's caps refusing and pausing as on REST, and the input policy's
 * codes. The regional allowlist and the rate limit have their own classes,
 * which need their own settings.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "godance.images.sync-poll-interval=50ms",
        "godance.inputs.enabled=true",
        "godance.inputs.allow-people=false",
        "godance.inputs.terms-version=" + McpServerIntegrationTest.TERMS,
        "godance.inputs.max-bytes=64KB",
        "godance.inputs.max-megapixels=1"})
@AutoConfigureWebTestClient(timeout = "30s")
@Testcontainers
@ExtendWith(OpenJobsGuard.class)
class McpServerIntegrationTest {

    static final int TERMS = 1;

    /** The tools of the round 13 contract ({@code 00-coordination.md}); client configurations name them. */
    static final Set<String> CONTRACT_TOOLS = Set.of(
            "search_recipes", "get_recipe", "list_models", "estimate_cost",
            "generate_image", "generate_video", "run_recipe", "start_batch",
            "get_job", "get_batch", "upload_input");

    static final Set<String> SPENDING_TOOLS = Set.of("generate_image", "generate_video", "run_recipe", "start_batch");

    static final byte[] TINY_PNG = Base64.getDecoder().decode(
            "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAiMock;
    static WireMockServer galleryMock;

    @BeforeAll
    static void startMocks() {
        openAiMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        openAiMock.start();
        galleryMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        galleryMock.start();
        stubGallery();
    }

    @AfterAll
    static void stopMocks() {
        if (openAiMock != null) openAiMock.stop();
        if (galleryMock != null) galleryMock.stop();
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base", () -> openAiMock.baseUrl());
        r.add("godance.mcp.gallery-url", () -> galleryMock.baseUrl() + "/gallery");
        r.add("godance.object-storage.endpoint", MINIO::getS3URL);
        r.add("godance.object-storage.access-key", MINIO::getUserName);
        r.add("godance.object-storage.secret-key", MINIO::getPassword);
        r.add("godance.object-storage.bucket", () -> "godance-mcp-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    /** The person check of an input upload, standing in for the one that asks a model: never a person. */
    @TestConfiguration
    static class NoPersonCheck {
        @Bean
        @Primary
        InputPersonCheck inputPersonCheck() {
            return (userId, image, contentType) -> InputPersonCheck.Verdict.NO_PERSON;
        }
    }

    @LocalServerPort int port;
    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerService ledger;
    @Autowired UsageRecordRepository usage;
    @Autowired RecipeBatchRunner batchRunner;
    @Autowired ModelCatalog catalog;
    @Autowired SessionCookies cookies;
    @Autowired JdbcTemplate jdbc;

    McpFixture fx;

    @BeforeEach
    void setUp() {
        fx = new McpFixture(http, users, ledger, port);
        openAiMock.resetAll();
        // The batch runner advances every open batch; a batch an earlier test left open must not run here.
        jdbc.update("UPDATE recipe_batch_rows SET status = 'SKIPPED', completed_at = now() "
                + "WHERE status IN ('PENDING', 'RUNNING')");
        jdbc.update("UPDATE recipe_batches SET status = 'CANCELLED', pause_reason = NULL, pause_detail = NULL, "
                + "completed_at = now() WHERE completed_at IS NULL");
    }

    // ===== Initialise, list, and who may call =========================

    @Test
    void listTools_givenAnApiKey_expectsExactlyTheContractTools() {
        Key key = fx.key(fx.account(0));

        try (McpSyncClient client = fx.uninitialised(key.plaintext())) {
            InitializeResult init = client.initialize();
            assertThat(init.serverInfo().name()).isEqualTo("godance");
            assertThat(init.capabilities().tools()).isNotNull();
            assertThat(init.instructions()).contains("max_cost_cents", "estimate_cost");

            assertThat(client.listTools().tools()).extracting(Tool::name)
                    .containsExactlyInAnyOrderElementsOf(CONTRACT_TOOLS);
        }
    }

    @Test
    void listTools_givenTheTools_expectsADescriptionAndSchemasEachAndMaxCostCentsOnEverySpendingTool() {
        try (McpSyncClient client = fx.client(fx.key(fx.account(0)))) {
            for (Tool tool : client.listTools().tools()) {
                assertThat(tool.description()).as(tool.name()).isNotBlank();
                assertThat(tool.inputSchema()).as(tool.name()).isNotNull();
                assertThat(tool.outputSchema()).as(tool.name()).isNotNull();
                boolean spends = SPENDING_TOOLS.contains(tool.name());
                assertThat(map(tool.inputSchema().get("properties")).containsKey("max_cost_cents")).as(tool.name())
                        .isEqualTo(spends);
                if (spends) assertThat(tool.description()).as(tool.name()).contains("estimate_cost");
                assertThat(tool.annotations().readOnlyHint()).as(tool.name())
                        .isEqualTo(!spends && !"upload_input".equals(tool.name()));
            }
        }
    }

    @Test
    void post_givenASessionCookie_expects403() {
        String email = "mcp-cookie-" + UUID.randomUUID() + "@godance.test";
        http.post().uri("/v1/auth/signup")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(SessionCookies.CLIENT_HEADER, SessionCookies.COOKIE_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple", "fullName", "Cookie"))
                .exchange().expectStatus().isCreated();
        List<ResponseCookie> set = http.post().uri("/v1/auth/login")
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .header(SessionCookies.CLIENT_HEADER, SessionCookies.COOKIE_CLIENT)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple"))
                .exchange().expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseCookies().get(cookies.name());
        assertThat(set).hasSize(1);

        String cookie = set.get(0).getValue();

        // Sent as the app sends it, the cookie passes the cross-site check and may make a key...
        http.post().uri("/v1/keys").cookie(cookies.name(), cookie)
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "from-the-app"))
                .exchange()
                .expectStatus().isCreated();
        // ...but not reach /mcp. A cookie's POST is refused by the cross-site check, since no page origin
        // is allowed on /mcp, so the MCP server never sees it...
        rawPost(null).cookie(cookies.name(), cookie)
                .header(HttpHeaders.ORIGIN, "http://localhost:5173")
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("This request didn't come from the Godance app.");
        // ...and where that check does not apply (a GET), the session is refused by SecurityConfig's rule.
        http.get().uri("/mcp").cookie(cookies.name(), cookie)
                .exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Access denied.");
    }

    @Test
    void post_givenASignedInSessionsBearerToken_expects403() {
        rawPost(fx.account(0).bearer()).exchange()
                .expectStatus().isForbidden()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.detail").isEqualTo("Access denied.");
    }

    @Test
    void post_givenNoCredentials_expects401() {
        rawPost(null).exchange().expectStatus().isUnauthorized();
    }

    @Test
    void post_givenARevokedKey_expects401() {
        Account account = fx.account(0);
        Key key = fx.key(account);
        http.delete().uri("/v1/keys/{id}", key.id()).header(HttpHeaders.AUTHORIZATION, account.bearer())
                .exchange().expectStatus().is2xxSuccessful();

        rawPost(key.bearer()).exchange().expectStatus().isUnauthorized();
    }

    // ===== The transport ===============================================

    @Test
    void post_givenAnOriginHeaderAndAValidKey_expects403() {
        rawPost(fx.key(fx.account(0)).bearer()).header(HttpHeaders.ORIGIN, "https://attacker.example.test")
                .exchange()
                .expectStatus().isForbidden()
                .expectBody().jsonPath("$.error.code").isEqualTo(-32600);
    }

    @Test
    void getAndDelete_givenAKey_expect405AllowingPostOnly() {
        String bearer = fx.key(fx.account(0)).bearer();
        http.get().uri("/mcp").header(HttpHeaders.AUTHORIZATION, bearer)
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .exchange()
                .expectStatus().isEqualTo(405)
                .expectHeader().valueEquals(HttpHeaders.ALLOW, "POST");
        http.delete().uri("/mcp").header(HttpHeaders.AUTHORIZATION, bearer)
                .exchange()
                .expectStatus().isEqualTo(405);
    }

    @Test
    void post_givenAnUnsupportedProtocolVersion_expects400() {
        rawPost(fx.key(fx.account(0)).bearer()).header("MCP-Protocol-Version", "1999-01-01")
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void post_givenANotification_expects202WithNoBody() {
        http.post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, fx.key(fx.account(0)).bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream")
                .bodyValue("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")
                .exchange()
                .expectStatus().isAccepted()
                .expectBody().isEmpty();
    }

    @Test
    void post_givenAMessageLargerThanTheLargestUpload_expects413() {
        String huge = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"upload_input\","
                + "\"arguments\":{\"data\":\"" + "A".repeat(200 * 1024) + "\"}}}";
        http.post().uri("/mcp")
                .header(HttpHeaders.AUTHORIZATION, fx.key(fx.account(0)).bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(huge)
                .exchange()
                .expectStatus().isEqualTo(413);
    }

    @Test
    void callTool_givenAToolOutsideTheContract_expectsAnErrorAndNothingDone() {
        try (McpSyncClient client = fx.client(fx.key(fx.account(0)))) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> call(client, "create_api_key", Map.of()))
                    .hasMessageContaining("Unknown tool");
        }
    }

    // ===== Models and prices ==========================================

    @Test
    void listModels_givenImage_expectsOnlyImageModelsWithTheirPrices() {
        try (McpSyncClient client = fx.client(fx.key(fx.account(0)))) {
            Map<String, Object> out = ok(call(client, "list_models", Map.of("modality", "image")));

            List<Map<String, Object>> models = list(out.get("models"));
            assertThat(models).isNotEmpty().allSatisfy(model ->
                    assertThat(map(model.get("godance"))).containsEntry("modality", "IMAGE").containsKey("pricing"));
            assertThat(models).extracting(model -> model.get("id")).contains("gpt-image-2");
        }
    }

    @Test
    void estimateCost_givenARequest_expectsWhatRestEstimatesSays() {
        Account account = fx.account(0);
        Key key = fx.key(account);
        Map<?, ?> rest = http.post().uri("/v1/estimates").header(HttpHeaders.AUTHORIZATION, key.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "n", 2))
                .exchange().expectStatus().isOk()
                .expectBody(Map.class).returnResult().getResponseBody();

        try (McpSyncClient client = fx.client(key)) {
            Map<String, Object> out = ok(call(client, "estimate_cost", Map.of("model", "gpt-image-2", "n", 2)));

            assertThat(out).containsEntry("kind", "request").containsEntry("currency", "USD");
            assertThat(number(out.get("estimated_cost_cents"))).isEqualTo(number(rest.get("estimated_cost_cents")));
        }
    }

    // ===== generate_image =============================================

    @Test
    void generateImage_givenAKeyLeftOnAsync_expectsAnApiJobThatEndsDoneWithSignedLinksAndThePriceCharged()
            throws InterruptedException {
        Account account = fx.account(10_00L);
        stubImage();
        long price;
        try (McpSyncClient client = fx.client(fx.key(account))) {
            price = price(client, Map.of("model", "gpt-image-2"));

            Map<String, Object> started = ok(call(client, "generate_image",
                    Map.of("model", "gpt-image-2", "prompt", "A brass compass on an old map", "max_cost_cents", price)));
            assertThat(started).containsEntry("status", "QUEUED").containsKey("job_id");
            assertThat(number(started.get("estimated_cost_cents"))).isEqualTo(price);

            Map<String, Object> done = job(client, started.get("job_id"), "DONE");
            assertThat(done).containsEntry("channel", "API").containsEntry("prompt", "A brass compass on an old map");
            assertThat(list(done.get("outputs"))).singleElement()
                    .satisfies(output -> assertThat((String) output.get("url")).contains("X-Amz-Signature"));
            assertThat(number(done.get("actualCostCents"))).isEqualTo(price);
        }
        assertThat(balance(account)).isEqualTo(10_00L - price);
    }

    @Test
    void generateImage_givenAKeySetToWait_expectsTheFinishedImagesInTheAnswer() {
        Account account = fx.account(10_00L);
        stubImage();
        Key key = fx.key(account, Map.of("name", "waits", "image_response", "sync"));
        try (McpSyncClient client = fx.client(key)) {
            Map<String, Object> done = ok(call(client, "generate_image",
                    Map.of("model", "gpt-image-2", "prompt", "A lantern boat at dusk")));

            assertThat(done).containsEntry("status", "DONE").containsKey("job_id");
            assertThat(list(done.get("images"))).singleElement()
                    .satisfies(image -> assertThat((String) image.get("url")).startsWith("http"));
        }
    }

    @Test
    void generateImage_givenMaxCostCentsBelowThePrice_expectsMaxCostExceededAndNothingChargedOrWritten() {
        Account account = fx.account(10_00L);
        stubImage();
        long ledgerRows = ledgerRows(account);
        try (McpSyncClient client = fx.client(fx.key(account))) {
            long price = price(client, Map.of("model", "gpt-image-2", "n", 2));

            Map<String, Object> refused = problem(call(client, "generate_image",
                    Map.of("model", "gpt-image-2", "prompt", "Two moons", "n", 2, "max_cost_cents", price - 1)));

            assertThat(refused).containsEntry("status", 402).containsEntry("code", "max_cost_exceeded");
            assertThat(number(refused.get("estimated_cost_cents"))).isEqualTo(price);
            assertThat(number(refused.get("max_cost_cents"))).isEqualTo(price - 1);
        }
        assertThat(ledgerRows(account)).isEqualTo(ledgerRows);
        assertThat(jobs(account)).isZero();
        assertThat(balance(account)).isEqualTo(10_00L);
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    @Test
    void generateImage_givenImagesFromAnUpload_expectsAnEditJobThatEndsDone() throws InterruptedException {
        Account account = fx.account(10_00L);
        InputFixtures.acceptTerms(http, account.bearer(), TERMS).expectStatus().isNoContent();
        openAiMock.stubFor(post(urlEqualTo("/v1/images/edits")).willReturn(imageAnswer()));
        try (McpSyncClient client = fx.client(fx.key(account))) {
            String fileId = (String) ok(call(client, "upload_input",
                    Map.of("data", Base64.getEncoder().encodeToString(InputFixtures.png(32, 32))))).get("id");
            long price = price(client, Map.of("model", "gpt-image-2", "input_images", 1));

            Map<String, Object> started = ok(call(client, "generate_image", Map.of("model", "gpt-image-2",
                    "prompt", "The same, at night", "images", List.of(Map.of("file_id", fileId)),
                    "max_cost_cents", price)));

            Map<String, Object> done = job(client, started.get("job_id"), "DONE");
            assertThat(list(done.get("inputs"))).singleElement()
                    .satisfies(input -> assertThat(input).containsEntry("file_id", fileId));
            assertThat(number(done.get("actualCostCents"))).isEqualTo(price);
        }
    }

    // ===== generate_video =============================================

    @Test
    void generateVideo_givenAPrompt_expectsAnApiJobThatEndsDone() throws InterruptedException {
        Account account = fx.account(50_00L);
        stubSora();
        try (McpSyncClient client = fx.client(fx.key(account))) {
            long price = price(client, Map.of("model", "sora-2", "seconds", 4));

            Map<String, Object> started = ok(call(client, "generate_video",
                    Map.of("model", "sora-2", "prompt", "Koi under neon rain", "seconds", 4, "max_cost_cents", price)));
            assertThat(started).containsEntry("status", "QUEUED");

            Map<String, Object> done = job(client, started.get("job_id"), "DONE");
            assertThat(done).containsEntry("modality", "VIDEO").containsEntry("channel", "API");
            assertThat(number(done.get("actualCostCents"))).isLessThanOrEqualTo(price);
        }
    }

    @Test
    void generateVideo_givenMaxCostCentsBelowThePrice_expectsMaxCostExceededAndNoProviderCall() {
        Account account = fx.account(50_00L);
        stubSora();
        try (McpSyncClient client = fx.client(fx.key(account))) {
            long price = price(client, Map.of("model", "sora-2", "seconds", 4));

            Map<String, Object> refused = problem(call(client, "generate_video", Map.of("model", "sora-2",
                    "prompt", "Koi", "seconds", 4, "max_cost_cents", price - 1)));

            assertThat(refused).containsEntry("code", "max_cost_exceeded");
            assertThat(number(refused.get("estimated_cost_cents"))).isEqualTo(price);
        }
        assertThat(jobs(account)).isZero();
        assertThat(balance(account)).isEqualTo(50_00L);
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
    }

    // ===== run_recipe =================================================

    @Test
    void runRecipe_givenVariables_expectsTheRenderedPromptRunAsAnApiJobOfThatVersion() throws InterruptedException {
        Account account = fx.account(10_00L);
        stubImage();
        McpFixture.Recipe recipe = fx.recipe(account, "IMAGE", "A {{animal}} reading a newspaper",
                List.of(Map.of("name", "animal", "default", "cat")), "gpt-image-2", Map.of("size", "1024x1024"),
                List.of());
        try (McpSyncClient client = fx.client(fx.key(account))) {
            long price = number(ok(call(client, "estimate_cost", Map.of("recipe_id", recipe.id())))
                    .get("estimated_cost_cents"));

            Map<String, Object> started = ok(call(client, "run_recipe", Map.of("recipe_id", recipe.id(),
                    "variables", Map.of("animal", "heron"), "max_cost_cents", price)));
            assertThat(started).containsEntry("recipe_id", recipe.id()).containsEntry("version_id", recipe.versionId());

            Map<String, Object> done = job(client, started.get("job_id"), "DONE");
            assertThat(done).containsEntry("prompt", "A heron reading a newspaper").containsEntry("channel", "API");
            assertThat(map(done.get("recipe"))).containsEntry("id", recipe.id()).containsEntry("version", 1)
                    .containsEntry("variables", Map.of("animal", "heron"));
            assertThat(number(done.get("actualCostCents"))).isEqualTo(price);
        }
    }

    @Test
    void runRecipe_givenMaxCostCentsBelowThePrice_expectsMaxCostExceededAndNoJob() {
        Account account = fx.account(10_00L);
        stubImage();
        McpFixture.Recipe recipe = fx.recipe(account, "IMAGE", "A quiet harbour", List.of(), "gpt-image-2",
                Map.of("n", 2), List.of());
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> refused = problem(call(client, "run_recipe",
                    Map.of("recipe_id", recipe.id(), "max_cost_cents", 1)));

            assertThat(refused).containsEntry("code", "max_cost_exceeded");
        }
        assertThat(jobs(account)).isZero();
        assertThat(balance(account)).isEqualTo(10_00L);
    }

    @Test
    void runRecipe_givenAnotherUsersRecipe_expectsRecipeNotFound() {
        McpFixture.Recipe theirs = fx.recipe(fx.account(0), "IMAGE", "Theirs", List.of(), "gpt-image-2", Map.of(),
                List.of());
        try (McpSyncClient client = fx.client(fx.key(fx.account(10_00L)))) {
            Map<String, Object> refused = problem(call(client, "run_recipe", Map.of("recipe_id", theirs.id())));

            assertThat(refused).containsEntry("status", 404).containsEntry("code", "recipe_not_found");
        }
    }

    // ===== start_batch and get_batch ==================================

    @Test
    void startBatch_givenRowsAndItsEstimateAsMaxCost_expectsTheBatchDoneAtThatCost() throws InterruptedException {
        Account account = fx.account(10_00L);
        stubImage();
        McpFixture.Recipe recipe = fx.recipe(account, "IMAGE", "A {{thing}} in fog",
                List.of(Map.of("name", "thing", "default", "lighthouse")), "gpt-image-2", Map.of(), List.of());
        Map<String, Object> batch = Map.of("version_id", recipe.versionId(),
                "rows", List.of(Map.of("variables", Map.of("thing", "bridge")), Map.of("variables", Map.of("thing", "tower"))));
        try (McpSyncClient client = fx.client(fx.key(account))) {
            long estimate = number(ok(call(client, "estimate_cost", Map.of("batch", batch))).get("estimated_cost_cents"));

            Map<String, Object> args = new HashMap<>(batch);
            args.put("max_cost_cents", estimate);
            Map<String, Object> started = ok(call(client, "start_batch", args));
            assertThat(started).containsEntry("status", "QUEUED").containsEntry("rows_total", 2);
            assertThat(number(started.get("max_cost_cents"))).isEqualTo(estimate);

            Map<String, Object> done = runBatch(client, (String) started.get("id"), "DONE");
            assertThat(number(done.get("rows_done"))).isEqualTo(2);
            assertThat(number(done.get("spent_cents"))).isEqualTo(estimate);
            assertThat(list(done.get("rows"))).hasSize(2)
                    .allSatisfy(row -> assertThat(row).containsEntry("status", "DONE"));
        }
    }

    @Test
    void startBatch_givenMaxCostCentsBelowItsEstimate_expectsMaxCostExceededAndNoBatch() {
        Account account = fx.account(10_00L);
        McpFixture.Recipe recipe = fx.recipe(account, "IMAGE", "A {{thing}}",
                List.of(Map.of("name", "thing", "default", "kite")), "gpt-image-2", Map.of(), List.of());
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> refused = problem(call(client, "start_batch", Map.of("version_id", recipe.versionId(),
                    "rows", List.of(Map.of(), Map.of()), "max_cost_cents", 1)));

            assertThat(refused).containsEntry("code", "max_cost_exceeded");
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM recipe_batches WHERE user_id = ?", Long.class,
                account.id())).isZero();
    }

    /**
     * A batch prices each row when it starts. When the model's price rises
     * after the batch was made, a row that would take the batch past its
     * ceiling fails before its pre-debit, and the batch never costs more.
     */
    @Test
    void startBatch_givenThePriceRisesWhileItRuns_expectsNoRowToTakeItPastItsCeiling() throws InterruptedException {
        Account account = fx.account(10_00L);
        stubImage();
        McpFixture.Recipe recipe = fx.recipe(account, "IMAGE", "Row {{i}}",
                List.of(Map.of("name", "i", "default", "0")), "gpt-image-2", Map.of(), List.of());
        Long perImage = jdbc.queryForObject(
                "SELECT per_image_microcents FROM model_catalog WHERE model_name = 'gpt-image-2'", Long.class);
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> args = new HashMap<>(Map.of("version_id", recipe.versionId(), "max_concurrency", 1,
                    "rows", List.of(Map.of("variables", Map.of("i", "1")), Map.of("variables", Map.of("i", "2")),
                            Map.of("variables", Map.of("i", "3")))));
            long estimate = number(ok(call(client, "estimate_cost", Map.of("batch", args))).get("estimated_cost_cents"));
            args.put("max_cost_cents", estimate);
            String batchId = (String) ok(call(client, "start_batch", args)).get("id");

            // One pass starts the first row (one at a time), at the price the batch was estimated at.
            batchRunner.tick();
            assertThat(number(ok(call(client, "get_batch", Map.of("batch_id", batchId))).get("rows_running")))
                    .isEqualTo(1);

            // Then the price triples: each later row would cost more than what is left of the ceiling.
            setImagePrice(perImage * 3);
            Map<String, Object> done = runBatch(client, batchId, "DONE");

            assertThat(number(done.get("rows_done"))).isEqualTo(1);
            assertThat(number(done.get("rows_failed"))).isEqualTo(2);
            assertThat(number(done.get("spent_cents"))).isEqualTo(estimate / 3).isLessThanOrEqualTo(estimate);
            assertThat(list(done.get("rows")).subList(1, 3)).allSatisfy(row -> assertThat((String) row.get("error"))
                    .contains("max_cost_cents").contains("nothing was charged"));
        } finally {
            setImagePrice(perImage);
        }
        // One job was made and charged; the two refused rows took no pre-debit.
        assertThat(jobs(account)).isEqualTo(1);
    }

    // ===== Key caps, as on REST =======================================

    @Test
    void generateImage_givenAKeyWhoseDailyCapIsSpent_expects402AsRestAndNoProviderCall() {
        Account account = fx.account(10_00L);
        stubImage();
        Key key = fx.key(account, Map.of("name", "capped", "daily_cap_cents", 6));
        seedKeyUsage(account, key, 6);

        http.post().uri("/v1/images/generations").header(HttpHeaders.AUTHORIZATION, key.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-image-2", "prompt", "REST, for comparison"))
                .exchange()
                .expectStatus().isEqualTo(402)
                .expectBody().jsonPath("$.detail").value(detail ->
                        assertThat((String) detail).startsWith("API-key daily spending cap"));
        try (McpSyncClient client = fx.client(key)) {
            Map<String, Object> refused = problem(call(client, "generate_image",
                    Map.of("model", "gpt-image-2", "prompt", "Over the cap")));

            assertThat(refused).containsEntry("status", 402);
            assertThat((String) refused.get("detail")).startsWith("API-key daily spending cap");
        }
        assertThat(openAiMock.getAllServeEvents()).isEmpty();
        assertThat(jobs(account)).isZero();
    }

    @Test
    void startBatch_givenAKeyCapThatCoversOneRow_expectsTheBatchPausedOnTheCapAsRest() throws InterruptedException {
        Account account = fx.account(10_00L);
        stubImage();
        McpFixture.Recipe recipe = fx.recipe(account, "IMAGE", "Cap {{i}}",
                List.of(Map.of("name", "i", "default", "0")), "gpt-image-2", Map.of(), List.of());
        try (McpSyncClient probe = fx.client(fx.key(account))) {
            long perRow = price(probe, Map.of("model", "gpt-image-2"));
            Key capped = fx.key(account, Map.of("name", "one-row", "daily_cap_cents", perRow));
            try (McpSyncClient client = fx.client(capped)) {
                String batchId = (String) ok(call(client, "start_batch", Map.of("version_id", recipe.versionId(),
                        "max_concurrency", 1, "rows", List.of(Map.of(), Map.of())))).get("id");

                Map<String, Object> paused = runBatch(client, batchId, "PAUSED");

                assertThat(paused).containsEntry("pause_reason", "spending_cap");
                assertThat(number(paused.get("rows_done"))).isEqualTo(1);
            }
        }
    }

    // ===== Inputs and their codes ======================================

    @Test
    void uploadInput_givenAnAccountThatHasNotAcceptedTheTerms_expectsInputsTermsRequiredWithTheVersion() {
        try (McpSyncClient client = fx.client(fx.key(fx.account(0)))) {
            Map<String, Object> refused = problem(call(client, "upload_input",
                    Map.of("data", Base64.getEncoder().encodeToString(InputFixtures.png(8, 8)))));

            assertThat(refused).containsEntry("status", 403).containsEntry("code", "inputs_terms_required")
                    .containsEntry("terms_version", TERMS);
        }
    }

    @Test
    void uploadInput_givenADataUrl_expectsTheStoredFile() {
        Account account = fx.account(0);
        InputFixtures.acceptTerms(http, account.bearer(), TERMS).expectStatus().isNoContent();
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> file = ok(call(client, "upload_input", Map.of("data",
                    "data:image/png;base64," + Base64.getEncoder().encodeToString(InputFixtures.png(16, 12)))));

            assertThat(file).containsEntry("object", "file").containsEntry("content_type", "image/png")
                    .containsEntry("width", 16).containsEntry("height", 12).containsEntry("contains_person", false);
        }
    }

    @Test
    void uploadInput_givenMoreBytesThanAnInputMayHave_expectsInputTooLarge() {
        Account account = fx.account(0);
        InputFixtures.acceptTerms(http, account.bearer(), TERMS).expectStatus().isNoContent();
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> refused = problem(call(client, "upload_input",
                    Map.of("data", Base64.getEncoder().encodeToString(new byte[70 * 1024]))));

            assertThat(refused).containsEntry("status", 413).containsEntry("code", "input_too_large");
        }
    }

    @Test
    void uploadInput_givenSomethingThatIsNotAnImage_expectsInputTypeUnsupported() {
        Account account = fx.account(0);
        InputFixtures.acceptTerms(http, account.bearer(), TERMS).expectStatus().isNoContent();
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> refused = problem(call(client, "upload_input",
                    Map.of("data", Base64.getEncoder().encodeToString("not an image".getBytes(StandardCharsets.UTF_8)))));

            assertThat(refused).containsEntry("status", 415).containsEntry("code", "input_type_unsupported");
        }
    }

    // ===== Recipes ======================================================

    @Test
    void searchRecipes_givenAQuery_expectsMatchingGalleryAndOwnRecipesMarkedBySource() {
        Account account = fx.account(0);
        McpFixture.Recipe mine = fx.recipe(account, "IMAGE", "A tin robot watering plants", List.of(),
                "gpt-image-2", Map.of(), List.of());
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> out = ok(call(client, "search_recipes", Map.of("query", "tin robot")));

            assertThat(out).containsEntry("gallery_available", true);
            List<Map<String, Object>> results = list(out.get("results"));
            assertThat(results).anySatisfy(r -> assertThat(r).containsEntry("source", "mine").containsEntry("id", mine.id()));
            assertThat(results).anySatisfy(r -> assertThat(r).containsEntry("source", "gallery")
                    .containsEntry("slug", "tin-robot-tea-party")
                    .containsEntry("url", galleryMock.baseUrl() + "/gallery/tin-robot-tea-party/"));
            assertThat(results).noneSatisfy(r -> assertThat(r).containsEntry("slug", "neon-koi"));

            // The Chinese prompt is searched too, and the modality narrows it.
            assertThat(list(ok(call(client, "search_recipes", Map.of("query", "锡制机器人"))).get("results")))
                    .extracting(r -> r.get("slug")).containsExactly("tin-robot-tea-party");
            assertThat(list(ok(call(client, "search_recipes", Map.of("modality", "video", "source", "gallery")))
                    .get("results"))).extracting(r -> r.get("slug")).containsExactly("neon-koi");
        }
    }

    @Test
    void getRecipe_givenAGallerySlug_expectsThePublishedRecipe() {
        try (McpSyncClient client = fx.client(fx.key(fx.account(0)))) {
            Map<String, Object> out = ok(call(client, "get_recipe", Map.of("slug", "tin-robot-tea-party")));

            assertThat(out).containsEntry("source", "gallery").containsEntry("slug", "tin-robot-tea-party");
            assertThat(map(out.get("prompt"))).containsEntry("en", "A tin robot pours tea for two");
            assertThat(map(out.get("run"))).containsEntry("model", "gpt-image-2");

            assertThat(problem(call(client, "get_recipe", Map.of("slug", "no-such-recipe")))).containsEntry("status", 404);
            assertThat(problem(call(client, "get_recipe", Map.of("slug", "../../v1/keys")))).containsEntry("status", 404);
        }
    }

    @Test
    void getRecipe_givenOneOfMine_expectsItsVersionAndThePriceOfARun() {
        Account account = fx.account(0);
        McpFixture.Recipe mine = fx.recipe(account, "IMAGE", "A {{animal}} in a hat",
                List.of(Map.of("name", "animal", "default", "fox")), "gpt-image-2", Map.of("n", 2), List.of());
        try (McpSyncClient client = fx.client(fx.key(account))) {
            Map<String, Object> out = ok(call(client, "get_recipe", Map.of("id", mine.id())));

            assertThat(out).containsEntry("source", "mine");
            assertThat(map(map(out.get("recipe")).get("latest_version"))).containsEntry("template", "A {{animal}} in a hat");
            assertThat(number(out.get("estimated_cost_cents")))
                    .isEqualTo(2 * price(client, Map.of("model", "gpt-image-2")));
            assertThat(list(out.get("recent_runs"))).isEmpty();
        }
    }

    @Test
    void getJob_givenAnotherUsersJob_expects404AsRest() throws InterruptedException {
        Account owner = fx.account(10_00L);
        stubImage();
        Object jobId;
        try (McpSyncClient client = fx.client(fx.key(owner))) {
            jobId = ok(call(client, "generate_image", Map.of("model", "gpt-image-2", "prompt", "Mine"))).get("job_id");
            job(client, jobId, "DONE");
        }
        try (McpSyncClient stranger = fx.client(fx.key(fx.account(0)))) {
            assertThat(problem(call(stranger, "get_job", Map.of("job_id", String.valueOf(jobId)))))
                    .containsEntry("status", 404);
        }
    }

    // ===== Helpers ======================================================

    /** A raw initialise request, as an MCP client sends it, with this bearer or none. */
    WebTestClient.RequestHeadersSpec<?> rawPost(String bearer) {
        WebTestClient.RequestBodySpec spec = http.post().uri("/mcp")
                .contentType(MediaType.APPLICATION_JSON)
                .header(HttpHeaders.ACCEPT, "application/json, text/event-stream");
        if (bearer != null) spec = spec.header(HttpHeaders.AUTHORIZATION, bearer);
        return spec.bodyValue("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{"
                + "\"protocolVersion\":\"2025-11-25\",\"capabilities\":{},"
                + "\"clientInfo\":{\"name\":\"test\",\"version\":\"1\"}}}");
    }

    /** What estimate_cost says a request costs. */
    static long price(McpSyncClient client, Map<String, Object> request) {
        return number(ok(call(client, "estimate_cost", request)).get("estimated_cost_cents"));
    }

    /** Runs the batch runner until get_batch reads {@code status}; returns the batch with its rows. */
    Map<String, Object> runBatch(McpSyncClient client, String batchId, String status) throws InterruptedException {
        Map<String, Object> batch = null;
        long deadline = System.currentTimeMillis() + 20_000;
        while (System.currentTimeMillis() < deadline) {
            batchRunner.tick();
            batch = ok(call(client, "get_batch", Map.of("batch_id", batchId, "rows_limit", 100)));
            boolean over = !"DONE".equals(status) || batch.get("completed_at") != null;
            if (status.equals(batch.get("status")) && over) return batch;
            Thread.sleep(50);
        }
        assertThat(batch).as("batch never reached %s; last read: %s", status, batch).isNull();
        return batch;
    }

    void setImagePrice(long perImageMicrocents) {
        jdbc.update("UPDATE model_catalog SET per_image_microcents = ? WHERE model_name = 'gpt-image-2'",
                perImageMicrocents);
        catalog.refresh();
    }

    void seedKeyUsage(Account account, Key key, long costCents) {
        UsageRecord r = new UsageRecord();
        r.setUserId(account.id());
        r.setApiKeyId(key.id());
        r.setModality(Modality.IMAGE);
        r.setModelName("gpt-image-2");
        r.setProvider(Provider.OPENAI);
        r.setInputTokens(0);
        r.setOutputTokens(0);
        r.setCostCents(costCents);
        r.setRegion(Region.LOCAL);
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setOutcome(CallOutcome.OK);
        r.setRequestId("seed-" + UUID.randomUUID());
        r.setRecordedAt(Instant.now());
        usage.save(r);
    }

    long balance(Account account) {
        return users.findById(account.id()).orElseThrow().getCreditBalanceCents();
    }

    long ledgerRows(Account account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM credit_ledger WHERE user_id = ?", Long.class, account.id());
    }

    long jobs(Account account) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM generation_jobs WHERE user_id = ?", Long.class, account.id());
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder imageAnswer() {
        return aResponse().withStatus(200).withHeader("content-type", "application/json")
                .withBody("{\"created\":1700000000,\"data\":[{\"b64_json\":\""
                        + Base64.getEncoder().encodeToString(TINY_PNG) + "\"}]}");
    }

    static void stubImage() {
        openAiMock.stubFor(post(urlEqualTo("/v1/images/generations")).willReturn(imageAnswer()));
    }

    /** Sora: the first poll already reports the clip done. */
    static void stubSora() {
        openAiMock.stubFor(post(urlEqualTo("/v1/videos")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json").withBody("{\"id\":\"vid_mcp\",\"status\":\"queued\"}")));
        openAiMock.stubFor(get(urlPathMatching("/v1/videos/vid_mcp")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json").withBody("{\"id\":\"vid_mcp\",\"status\":\"completed\"}")));
        openAiMock.stubFor(get(urlEqualTo("/v1/videos/vid_mcp/content")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "video/mp4").withBody("FAKE-MP4-BYTES".getBytes(StandardCharsets.UTF_8))));
    }

    /** The published gallery, as the site's static JSON has it: one image recipe, one video recipe. */
    static void stubGallery() {
        galleryMock.stubFor(get(urlEqualTo("/gallery/index.json")).willReturn(json("""
                {"recipes": [
                  {"slug": "tin-robot-tea-party", "title": {"en": "Tin robot tea party", "zh": "锡制机器人茶会"},
                   "modality": "image", "model": "gpt-image-2", "tags": ["robots", "tea"],
                   "thumb": "https://media.example.test/tin-robot.webp", "published_on": "2026-11-01"},
                  {"slug": "neon-koi", "title": {"en": "Neon koi"}, "modality": "video", "model": "sora-2",
                   "tags": ["animals"], "thumb": null, "published_on": "2026-11-02"}
                ]}""")));
        galleryMock.stubFor(get(urlEqualTo("/gallery/_search.json")).willReturn(json("""
                {"recipes": [
                  {"slug": "tin-robot-tea-party", "text": "tin robot tea party\\n锡制机器人茶会\\na tin robot pours tea for two\\n一个锡制机器人为两人倒茶"},
                  {"slug": "neon-koi", "text": "neon koi\\nkoi swimming under neon rain"}
                ]}""")));
        galleryMock.stubFor(get(urlEqualTo("/gallery/tin-robot-tea-party.json")).willReturn(json("""
                {"slug": "tin-robot-tea-party", "title": {"en": "Tin robot tea party"}, "modality": "image",
                 "prompt": {"en": "A tin robot pours tea for two", "zh": "一个锡制机器人为两人倒茶"},
                 "run": {"model": "gpt-image-2", "params": {"size": "1024x1024"}, "cost_cents": 6}}""")));
    }

    static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(body);
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> list(Object value) {
        return (List<Map<String, Object>>) value;
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }

    static long number(Object value) {
        return ((Number) value).longValue();
    }
}
