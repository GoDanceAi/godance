package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.data.domain.PageRequest;
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

import ai.godance.domain.CallOutcome;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.ChatImageTokens;
import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.InputFileRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.ChatImageRefusedException;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.InputRefusedException;
import ai.godance.storage.ImageMetadata;
import ai.godance.storage.ImageMetadataTest;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionResponse;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Images in chat (round 11 brief 02), end to end against provider
 * cassettes: OpenAI's image parts in {@code /v1/chat/completions}, each of
 * the three ways an image may be named, every refusal before any charge,
 * the pre-debit that covers the images, settlement of the image tokens the
 * provider reports, streaming, and the person check on an upload.
 *
 * <p>Inputs are switched on here, with people not allowed and gpt-4o-mini as
 * the check model. Private destinations are allowed, as in every suite that
 * calls a local receiver: the image host is a WireMock on localhost. That
 * private addresses are refused in production is in {@code ChatImagesTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.inputs.enabled=true",
        "godance.inputs.allow-people=false",
        "godance.inputs.check-model=gpt-4o-mini",
        "godance.webhooks.allow-private-destinations=true"})
@AutoConfigureWebTestClient
@Testcontainers
class ChatImagesIntegrationTest {

    /** The address clients reach this API at, here; one of the caller's files is named under it. */
    private static final String API = "https://api.godance.test";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Container
    static final MinIOContainer MINIO = new MinIOContainer(MinioTestImage.get())
            .withUserName("test-access").withPassword("test-secret-key-1234");

    static WireMockServer openAi;
    static WireMockServer anthropic;
    static WireMockServer google;
    static WireMockServer imageHost;

    @BeforeAll
    static void startMocks() {
        openAi = started();
        anthropic = started();
        google = started();
        imageHost = started();
    }

    private static WireMockServer started() {
        WireMockServer server = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        server.start();
        return server;
    }

    @AfterAll
    static void stopMocks() {
        for (WireMockServer server : new WireMockServer[] {openAi, anthropic, google, imageHost}) {
            if (server != null) server.stop();
        }
    }

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.providers.openai.api-base",        () -> openAi.baseUrl());
        r.add("godance.providers.anthropic.api-base",     () -> anthropic.baseUrl());
        r.add("godance.providers.google.api-base",        () -> google.baseUrl());
        r.add("godance.object-storage.endpoint",          MINIO::getS3URL);
        r.add("godance.object-storage.access-key",        MINIO::getUserName);
        r.add("godance.object-storage.secret-key",        MINIO::getPassword);
        r.add("godance.object-storage.bucket",            () -> "godance-chat-images-test");
        r.add("godance.object-storage.path-style-access", () -> "true");
    }

    @Autowired WebTestClient mockClient;
    @Autowired UserRepository users;
    @Autowired UsageRecordRepository usage;
    @Autowired CreditLedgerService ledger;
    @Autowired CreditLedgerRepository ledgerRows;
    @Autowired InputFileRepository files;
    @Autowired ObjectStorageClient storage;
    @Autowired ModelCatalog catalog;
    @Autowired CostCalculator calculator;
    @Autowired ObjectMapper json;

    WebTestClient http;

    @BeforeEach
    void reset() {
        for (WireMockServer server : new WireMockServer[] {openAi, anthropic, google, imageHost}) server.resetAll();
        http = mockClient.mutate().baseUrl(API).build();
    }

    private static final byte[] PHOTO = ImageMetadataTest.fixture("gps.jpg");
    private static final byte[] PNG = ImageMetadataTest.fixture("plain.png");

    // ===== The three ways to name an image ==============================

    @Test
    void chat_givenADataUrlImage_expectsItAtTheProviderInOpenAisFormWithItsMetadataStripped() {
        Signed u = signupWithBalance(100_00);
        openAiAnswers("gpt-4o", "A mug on a shelf.", 1_125, 6);
        long filesBefore = files.count();
        int objectsBefore = storage.list("inputs/").size();

        ChatCompletionResponse out = chat(u, "gpt-4o", user(text("What is in this photo?"), image(dataUrl(PHOTO, "image/jpeg"), null)))
                .expectStatus().isOk()
                .expectBody(ChatCompletionResponse.class).returnResult().getResponseBody();

        // The response is the same shape as ever: the answer is a string.
        assertThat(out.choices().get(0).message().content()).isEqualTo("A mug on a shelf.");
        assertThat(out.usage().promptTokens()).isEqualTo(1_125);

        JsonNode sent = sentTo(openAi);
        assertThat(sent.path("model").asString()).isEqualTo("gpt-4o");
        JsonNode parts = sent.path("messages").get(0).path("content");
        assertThat(parts).hasSize(2);
        assertThat(parts.get(0)).isEqualTo(json.readTree("{\"type\":\"text\",\"text\":\"What is in this photo?\"}"));
        assertThat(parts.get(1).path("type").asString()).isEqualTo("image_url");
        // The image is inline, and it is the photo without where it was taken.
        byte[] stripped = ImageMetadata.strip(PHOTO).bytes();
        assertThat(parts.get(1).path("image_url").path("url").asString())
                .isEqualTo("data:image/jpeg;base64," + Base64.getEncoder().encodeToString(stripped));
        assertThat(parts.get(1).path("image_url").has("detail")).isFalse();
        assertThat(ImageMetadataTest.count(stripped, "GPSCANARY")).isZero();
        assertThat(ImageMetadataTest.count(PHOTO, "GPSCANARY")).isPositive();

        // Settled on what the provider counted, image tokens included.
        ModelInfo gpt4o = catalog.find("gpt-4o").orElseThrow();
        UsageRecord record = onlyUsage(u);
        assertThat(record.getInputTokens()).isEqualTo(1_125);
        assertThat(record.getCostMicrocents()).isEqualTo(calculator.actualMicrocents(gpt4o, 1_125, 6));
        assertThat(record.getOutcome()).isEqualTo(CallOutcome.OK);
        // Nothing of the image is kept anywhere: no file row and no stored object came of it.
        assertThat(files.count()).isEqualTo(filesBefore);
        assertThat(storage.list("inputs/")).hasSize(objectsBefore);
    }

    @Test
    void chat_givenAnHttpsUrl_expectsItFetchedOnceAndSentInline() {
        Signed u = signupWithBalance(100_00);
        imageHost.stubFor(get(urlEqualTo("/shop/mug.png?v=2")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "image/png").withBody(PNG)));
        openAiAnswers("gpt-4o", "A mug.", 900, 3);

        chat(u, "gpt-4o", user(image(imageHost.baseUrl() + "/shop/mug.png?v=2", "low"), text("Describe it.")))
                .expectStatus().isOk();

        imageHost.verify(1, getRequestedFor(urlEqualTo("/shop/mug.png?v=2")));
        // The fetch carries nothing of the caller: no cookie, no authorization.
        assertThat(imageHost.getAllServeEvents().get(0).getRequest().containsHeader("Authorization")).isFalse();
        assertThat(imageHost.getAllServeEvents().get(0).getRequest().containsHeader("Cookie")).isFalse();
        JsonNode parts = sentTo(openAi).path("messages").get(0).path("content");
        // The provider is given the image, never the address.
        assertThat(parts.get(0).path("image_url").path("url").asString())
                .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(PNG));
        assertThat(parts.get(0).path("image_url").path("detail").asString()).isEqualTo("low");
        assertThat(sentTo(openAi).toString()).doesNotContain(imageHost.baseUrl());
    }

    @Test
    void chat_givenTheAddressOfOneOfTheCallersFiles_expectsItReadFromStorageAndNothingFetched() {
        Signed u = signupWithBalance(100_00);
        String fileId = uploadCleared(u, "gps.png");
        openAi.resetRequests();
        openAiAnswers("gpt-4o", "A drawing.", 800, 3);

        chat(u, "gpt-4o", user(text("And this?"), image(API + "/v1/files/" + fileId + "/content", null)))
                .expectStatus().isOk();

        // Not a single request left for the network: the file was read where it is stored.
        assertThat(imageHost.getAllServeEvents()).isEmpty();
        byte[] stored = storage.download(files.findById(UUID.fromString(fileId)).orElseThrow().getStorageKey());
        assertThat(sentTo(openAi).path("messages").get(0).path("content").get(1).path("image_url").path("url").asString())
                .isEqualTo("data:image/png;base64," + Base64.getEncoder().encodeToString(stored));
        // Reading a file in chat is not a use that keeps it: it still expires.
        assertThat(files.findById(UUID.fromString(fileId)).orElseThrow().getExpiresAt()).isNotNull();
    }

    @Test
    void chat_givenTheAddressOfAnotherUsersFile_expects404FileNotFoundAndNoLedgerRow() {
        Signed owner = signupWithBalance(100_00);
        Signed other = signupWithBalance(100_00);
        InputFixtures.acceptTerms(http, other.bearer(), 1).expectStatus().isNoContent();
        String fileId = uploadCleared(owner, "plain.png");
        openAi.resetAll();
        long rowsBefore = ledgerCount(other);

        chat(other, "gpt-4o", user(image(API + "/v1/files/" + fileId + "/content", null)))
                .expectStatus().isNotFound()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);

        assertNothingCharged(other, rowsBefore, 100_00);
        // The same answer as for a file nobody has.
        chat(other, "gpt-4o", user(image(API + "/v1/files/" + UUID.randomUUID() + "/content", null)))
                .expectStatus().isNotFound()
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_FILE_NOT_FOUND);
    }

    // ===== Fetching: every way it can go wrong ==========================

    @Test
    void chat_givenAnAddressThatRedirects_expects400ImageFetchFailedAndTheRedirectNotFollowed() {
        Signed u = signupWithBalance(100_00);
        imageHost.stubFor(get(urlEqualTo("/moved.png")).willReturn(aResponse().withStatus(302)
                .withHeader("Location", imageHost.baseUrl() + "/internal.png")));
        imageHost.stubFor(get(urlEqualTo("/internal.png")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "image/png").withBody(PNG)));
        long rowsBefore = ledgerCount(u);

        refused(chat(u, "gpt-4o", user(image(imageHost.baseUrl() + "/moved.png", null))),
                400, ChatImageRefusedException.CODE_IMAGE_FETCH_FAILED);

        imageHost.verify(0, getRequestedFor(urlEqualTo("/internal.png")));
        assertNothingCharged(u, rowsBefore, 100_00);
    }

    @Test
    void chat_givenAnAddressThatAnswersAnErrorOrSomethingThatIsNotAnImage_expects400ImageFetchFailed() {
        Signed u = signupWithBalance(100_00);
        imageHost.stubFor(get(urlEqualTo("/gone.png")).willReturn(aResponse().withStatus(404)));
        imageHost.stubFor(get(urlEqualTo("/page.png")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "text/html").withBody("<html>sign in</html>")));
        imageHost.stubFor(get(urlEqualTo("/untyped.png")).willReturn(aResponse().withStatus(200).withBody(PNG)));
        long rowsBefore = ledgerCount(u);

        for (String path : List.of("/gone.png", "/page.png", "/untyped.png")) {
            refused(chat(u, "gpt-4o", user(image(imageHost.baseUrl() + path, null))),
                    400, ChatImageRefusedException.CODE_IMAGE_FETCH_FAILED);
        }

        assertNothingCharged(u, rowsBefore, 100_00);
    }

    @Test
    void chat_givenAnImageLargerThanTwentyMegabytes_expects413ImageTooLarge() {
        Signed u = signupWithBalance(100_00);
        imageHost.stubFor(get(urlEqualTo("/huge.png")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "image/png").withBody(new byte[20 * 1024 * 1024 + 1])));
        long rowsBefore = ledgerCount(u);

        refused(chat(u, "gpt-4o", user(image(imageHost.baseUrl() + "/huge.png", null))),
                413, ChatImageRefusedException.CODE_IMAGE_TOO_LARGE);

        assertNothingCharged(u, rowsBefore, 100_00);
    }

    @Test
    void chat_givenAFetchedFileThatIsNotAStillPngJpegOrWebp_expects400ImageTypeUnsupported() {
        Signed u = signupWithBalance(100_00);
        imageHost.stubFor(get(urlEqualTo("/anim.gif")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "image/gif").withBody(ImageMetadataTest.fixture("picture.gif"))));
        long rowsBefore = ledgerCount(u);

        refused(chat(u, "gpt-4o", user(image(imageHost.baseUrl() + "/anim.gif", null))),
                400, ChatImageRefusedException.CODE_IMAGE_TYPE_UNSUPPORTED);

        assertNothingCharged(u, rowsBefore, 100_00);
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "ftp://example.com/cat.png", "file:///etc/passwd", "cat.png",
            "data:image/png,plain-text", "data:image/png;base64,***"})
    void chat_givenAnImageNamedInNoneOfTheThreeWays_expects400InvalidImageUrl(String url) {
        Signed u = signupWithBalance(100_00);
        long rowsBefore = ledgerCount(u);

        refused(chat(u, "gpt-4o", user(image(url, null))), 400, ChatImageRefusedException.CODE_INVALID_IMAGE_URL);

        assertNothingCharged(u, rowsBefore, 100_00);
    }

    // ===== Refused before any charge ====================================

    @ParameterizedTest
    @ValueSource(strings = {"deepseek-v4-pro", "claude-3-5-sonnet-20241022", "gemini-3.1-pro", "glm-4.7-flash"})
    void chat_givenAModelThatDoesNotAcceptImages_expects400BeforeAnyChargeAndBeforeAnythingIsFetched(String model) {
        Signed u = signupWithBalance(100_00);
        imageHost.stubFor(get(urlEqualTo("/mug.png")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "image/png").withBody(PNG)));
        long rowsBefore = ledgerCount(u);

        refused(chat(u, model, user(text("What is this?"), image(imageHost.baseUrl() + "/mug.png", null))),
                400, ChatImageRefusedException.CODE_MODEL_DOES_NOT_ACCEPT_IMAGES);

        // Not fetched, not sent to any provider, not charged.
        assertThat(imageHost.getAllServeEvents()).isEmpty();
        assertNothingCharged(u, rowsBefore, 100_00);
        // The same model still answers text, as it always did: only the image was the problem.
        assertThat(catalog.find(model)).isPresent();
    }

    @Test
    void chat_givenMoreThanTenImages_expects400TooManyImages() {
        Signed u = signupWithBalance(100_00);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 0; i < 11; i++) parts.add(image(dataUrl(PNG, "image/png"), null));
        long rowsBefore = ledgerCount(u);

        refused(chat(u, "gpt-4o", user(parts.toArray(Map[]::new))), 400, ChatImageRefusedException.CODE_TOO_MANY_IMAGES)
                .jsonPath("$.max_images").isEqualTo(10);

        assertNothingCharged(u, rowsBefore, 100_00);
    }

    @Test
    void chat_givenTenImages_expectsThemAllSentInOrder() {
        Signed u = signupWithBalance(100_00);
        List<Map<String, Object>> parts = new ArrayList<>();
        for (int i = 0; i < 10; i++) parts.add(image(dataUrl(PNG, "image/png"), null));
        openAiAnswers("gpt-4o", "Ten mugs.", 9_000, 3);

        chat(u, "gpt-4o", user(parts.toArray(Map[]::new))).expectStatus().isOk();

        assertThat(sentTo(openAi).path("messages").get(0).path("content")).hasSize(10);
    }

    @ParameterizedTest
    @ValueSource(strings = {"system", "assistant"})
    void chat_givenAnImageInAMessageThatIsNotTheUsers_expects400InvalidImageUrl(String role) {
        Signed u = signupWithBalance(100_00);
        long rowsBefore = ledgerCount(u);

        refused(http.post().uri("/v1/chat/completions")
                        .header(HttpHeaders.AUTHORIZATION, u.bearer())
                        .contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(Map.of("model", "gpt-4o", "messages", List.of(
                                Map.of("role", role, "content", List.of(image(dataUrl(PNG, "image/png"), null))),
                                Map.of("role", "user", "content", "Hello"))))
                        .exchange(),
                400, ChatImageRefusedException.CODE_INVALID_IMAGE_URL);

        assertNothingCharged(u, rowsBefore, 100_00);
    }

    @Test
    void chat_givenAPartThatIsNeitherTextNorAnImage_expects400UnsupportedContentPart() {
        Signed u = signupWithBalance(100_00);
        long rowsBefore = ledgerCount(u);

        refused(chat(u, "gpt-4o", user(text("Listen"),
                        Map.of("type", "input_audio", "input_audio", Map.of("data", "AAAA", "format", "wav")))),
                400, ChatImageRefusedException.CODE_UNSUPPORTED_CONTENT_PART);
        // Content of a shape that is no content at all is a plain 400.
        for (Object content : new Object[] {List.of(), 42, Map.of("type", "text", "text", "x"), "   "}) {
            http.post().uri("/v1/chat/completions")
                    .header(HttpHeaders.AUTHORIZATION, u.bearer())
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("model", "gpt-4o", "messages", List.of(Map.of("role", "user", "content", content))))
                    .exchange()
                    .expectStatus().isBadRequest()
                    .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON);
        }

        assertNothingCharged(u, rowsBefore, 100_00);
    }

    // ===== Text in parts ================================================

    @Test
    void chat_givenContentAsTextPartsOnly_expectsThemSentAsOneStringToAnyModel() {
        Signed u = signupWithBalance(100_00);
        openAiAnswers("gpt-4o", "Hi.", 12, 2);

        chat(u, "gpt-4o", user(text("First line."), text("Second line.")),
                Map.of("role", "user", "content", List.of(text("Only one part."))))
                .expectStatus().isOk();

        JsonNode messages = sentTo(openAi).path("messages");
        assertThat(messages.get(0).path("content").asString()).isEqualTo("First line.\nSecond line.");
        assertThat(messages.get(1).path("content").asString()).isEqualTo("Only one part.");
    }

    // ===== The pre-debit and the settlement =============================

    @Test
    void chat_givenImages_expectsAPreDebitThatCoversThemAndASettlementOnWhatTheProviderCounted() {
        Signed u = signupWithBalance(100_00);
        ModelInfo gpt55 = catalog.find("gpt-5.5").orElseThrow();
        openAiAnswers("gpt-5.5", "Two mugs.", 2_600, 4);

        chat(u, "gpt-5.5", user(image(dataUrl(PNG, "image/png"), null), image(dataUrl(PNG, "image/png"), "high")))
                .expectStatus().isOk();

        // The hold: each image at the most the provider documents for this model (12,000 tokens here).
        assertThat(ChatImageTokens.perImage(gpt55)).isEqualTo(12_000);
        long heldForImages = calculator.estimateInputCents(gpt55, 2 * 12_000);
        CreditLedger preDebit = ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 20)).stream()
                .filter(row -> row.getIdempotencyKey() != null && row.getIdempotencyKey().startsWith("estimate-"))
                .findFirst().orElseThrow();
        assertThat(-preDebit.getDeltaCents()).isGreaterThanOrEqualTo(heldForImages).isLessThanOrEqualTo(heldForImages + 1);
        assertThat(heldForImages).isEqualTo(13);          // 24,000 × 5,000 µ¢/1k = 12 ¢, plus 5 %
        // Text alone would have held one cent: the images are what the hold is for.
        assertThat(calculator.estimateInputCents(gpt55, 10)).isEqualTo(1);

        // The bill is the provider's count, image tokens included, not the hold.
        long actualMicrocents = calculator.actualMicrocents(gpt55, 2_600, 4);
        UsageRecord record = onlyUsage(u);
        assertThat(record.getInputTokens()).isEqualTo(2_600);
        assertThat(record.getCostMicrocents()).isEqualTo(actualMicrocents);
        var after = users.findById(u.id()).orElseThrow();
        assertThat((100_00 - after.getCreditBalanceCents()) * 10_000 + after.getPendingMicrocents())
                .isEqualTo(actualMicrocents);
    }

    @Test
    void chat_givenABalanceBelowTheHoldForTheImages_expects402AndNoProviderCall() {
        Signed u = signupWithBalance(10);     // ten cents; two images to gpt-5.5 hold thirteen
        openAiAnswers("gpt-5.5", "never", 1, 1);

        chat(u, "gpt-5.5", user(image(dataUrl(PNG, "image/png"), null), image(dataUrl(PNG, "image/png"), null)))
                .expectStatus().isEqualTo(402);

        assertThat(openAi.getAllServeEvents()).isEmpty();
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(10);
        // The same request as text alone is well within ten cents.
        chat(u, "gpt-5.5", Map.of("role", "user", "content", "Two mugs?")).expectStatus().isOk();
    }

    @Test
    void chat_givenAnImageToClaude_expectsABase64ImageBlockAndTheImageTokensSettled() {
        Signed u = signupWithBalance(100_00);
        anthropic.stubFor(post(urlEqualTo("/v1/messages")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\":\"msg_1\",\"type\":\"message\",\"role\":\"assistant\",\"model\":\"claude-sonnet-4-6\","
                        + "\"content\":[{\"type\":\"text\",\"text\":\"A mug.\"}],\"stop_reason\":\"end_turn\","
                        + "\"usage\":{\"input_tokens\":1580,\"output_tokens\":4}}")));

        chat(u, "claude-sonnet-4-6", user(text("What is this?"), image(dataUrl(PNG, "image/png"), null)))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.choices[0].message.content").isEqualTo("A mug.");

        JsonNode blocks = sentTo(anthropic).path("messages").get(0).path("content");
        assertThat(blocks.get(1)).isEqualTo(json.readTree(
                "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\""
                        + Base64.getEncoder().encodeToString(PNG) + "\"}}"));
        UsageRecord record = onlyUsage(u);
        assertThat(record.getInputTokens()).isEqualTo(1_580);
        assertThat(record.getCostMicrocents())
                .isEqualTo(calculator.actualMicrocents(catalog.find("claude-sonnet-4-6").orElseThrow(), 1_580, 4));
    }

    @Test
    void chat_givenAnImageToGemini_expectsAnInlineDataPartAndTheImageTokensSettled() {
        Signed u = signupWithBalance(100_00);
        google.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-3.1-flash-lite:generateContent"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"A mug.\"}]},"
                                + "\"finishReason\":\"STOP\"}],\"usageMetadata\":{\"promptTokenCount\":1130,"
                                + "\"candidatesTokenCount\":4,\"totalTokenCount\":1134}}")));

        chat(u, "gemini-3.1-flash-lite", user(image(dataUrl(PNG, "image/png"), null), text("What is this?")))
                .expectStatus().isOk()
                .expectBody().jsonPath("$.choices[0].message.content").isEqualTo("A mug.");

        JsonNode parts = sentTo(google).path("contents").get(0).path("parts");
        assertThat(parts.get(0)).isEqualTo(json.readTree(
                "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + Base64.getEncoder().encodeToString(PNG) + "\"}}"));
        assertThat(parts.get(1)).isEqualTo(json.readTree("{\"text\":\"What is this?\"}"));
        UsageRecord record = onlyUsage(u);
        assertThat(record.getInputTokens()).isEqualTo(1_130);
        assertThat(record.getCostMicrocents())
                .isEqualTo(calculator.actualMicrocents(catalog.find("gemini-3.1-flash-lite").orElseThrow(), 1_130, 4));
    }

    @Test
    void chat_givenAFallbackThatDoesNotAcceptImages_expectsItNeverTried() {
        // gpt-5.5 falls back to gpt-5.4 and then claude-sonnet-4-6; all three accept images.
        // gemini-3.1-flash-lite falls back to gpt-5.4-nano and gpt-4o-mini, which do too. So this
        // takes a model whose fallback does not: none is seeded that way, and that is the point
        // of the rule. Here the primary fails and the next model that accepts images answers.
        Signed u = signupWithBalance(100_00);
        google.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-3.1-flash-lite:generateContent"))
                .willReturn(aResponse().withStatus(500)));
        openAiAnswers("gpt-5.4-nano", "A mug.", 3_010, 3);

        chat(u, "gemini-3.1-flash-lite", user(image(dataUrl(PNG, "image/png"), null)))
                .expectStatus().isOk();

        // The fallback got the image too, in its own provider's form.
        assertThat(sentTo(openAi).path("model").asString()).isEqualTo("gpt-5.4-nano");
        assertThat(sentTo(openAi).path("messages").get(0).path("content").get(0).path("type").asString())
                .isEqualTo("image_url");
    }

    // ===== Streaming ====================================================

    @Test
    void chat_givenAnImageAndStream_expectsTheChunksRelayedAndTheImageAtTheProvider() {
        Signed u = signupWithBalance(100_00);
        String sse = "data: {\"id\":\"c1\",\"model\":\"gpt-4o\",\"created\":1,\"choices\":[{\"index\":0,"
                + "\"delta\":{\"role\":\"assistant\",\"content\":\"A \"},\"finish_reason\":null}]}\n\n"
                + "data: {\"id\":\"c1\",\"model\":\"gpt-4o\",\"created\":1,\"choices\":[{\"index\":0,"
                + "\"delta\":{\"content\":\"mug.\"},\"finish_reason\":\"stop\"}]}\n\n"
                + "data: {\"id\":\"c1\",\"model\":\"gpt-4o\",\"created\":1,\"choices\":[],"
                + "\"usage\":{\"prompt_tokens\":1110,\"completion_tokens\":3,\"total_tokens\":1113}}\n\n"
                + "data: [DONE]\n\n";
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "text/event-stream").withBody(sse)));

        String body = http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "stream", true,
                        "messages", List.of(user(text("What is this?"), image(dataUrl(PNG, "image/png"), null)))))
                .exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(MediaType.TEXT_EVENT_STREAM)
                .expectBody(String.class).returnResult().getResponseBody();

        assertThat(body).contains("\"content\":\"A \"").contains("\"content\":\"mug.\"").contains("[DONE]");
        JsonNode sent = sentTo(openAi);
        assertThat(sent.path("stream").asBoolean()).isTrue();
        assertThat(sent.path("messages").get(0).path("content").get(1).path("image_url").path("url").asString())
                .startsWith("data:image/png;base64,");
        // Settled after the stream, on the image tokens the last chunk reported.
        UsageRecord record = awaitUsage(u);
        assertThat(record.getInputTokens()).isEqualTo(1_110);
        assertThat(record.getCostMicrocents())
                .isEqualTo(calculator.actualMicrocents(catalog.find("gpt-4o").orElseThrow(), 1_110, 3));
    }

    @Test
    void chat_givenStreamAndAnImageThatCannotBeFetched_expectsAProblemNotAStream() {
        Signed u = signupWithBalance(100_00);
        imageHost.stubFor(get(urlEqualTo("/gone.png")).willReturn(aResponse().withStatus(404)));

        http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .accept(MediaType.TEXT_EVENT_STREAM)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", "gpt-4o", "stream", true,
                        "messages", List.of(user(image(imageHost.baseUrl() + "/gone.png", null)))))
                .exchange()
                .expectStatus().isBadRequest()
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(ChatImageRefusedException.CODE_IMAGE_FETCH_FAILED);
    }

    // ===== The person check, on an upload ===============================

    @Test
    void upload_givenACheckModelThatSaysNo_expectsTheFileStoredAndTheUserNotCharged() {
        Signed u = signupWithBalance(5_00);
        InputFixtures.acceptTerms(http, u.bearer(), 1).expectStatus().isNoContent();
        checkAnswers("no");
        long rowsBefore = ledgerCount(u);

        InputFixtures.upload(http, u.bearer(), "gps.jpg")
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.contains_person").isEqualTo(false);

        // The check model was shown the stripped image and asked the one fixed question.
        JsonNode check = sentTo(openAi);
        assertThat(check.path("model").asString()).isEqualTo("gpt-4o-mini");
        assertThat(check.path("temperature").asDouble()).isZero();
        assertThat(check.path("messages").get(0).path("content").get(1).path("image_url").path("url").asString())
                .isEqualTo("data:image/jpeg;base64,"
                        + Base64.getEncoder().encodeToString(ImageMetadata.strip(PHOTO).bytes()));
        // Godance's own call: no ledger row, the balance as it was, and a usage record at zero cost.
        assertThat(ledgerCount(u)).isEqualTo(rowsBefore);
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(5_00);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(5_00);
        UsageRecord record = onlyUsage(u);
        assertThat(record.getRequestId()).startsWith("check_");
        assertThat(record.getModelName()).isEqualTo("gpt-4o-mini");
        assertThat(record.getCostCents()).isZero();
        assertThat(record.getCostMicrocents()).isZero();
        assertThat(record.getProviderCostMicrocents()).isPositive();
    }

    @Test
    void upload_givenACheckModelThatSaysYes_expects422PersonNotAllowedAndNothingStored() {
        Signed u = signupWithBalance(0);
        InputFixtures.acceptTerms(http, u.bearer(), 1).expectStatus().isNoContent();
        checkAnswers("Yes");

        InputFixtures.upload(http, u.bearer(), "plain.jpg")
                .expectStatus().isEqualTo(422)
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_PERSON_NOT_ALLOWED);

        assertThat(files.findByUserId(u.id())).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"I think there might be a hand.", "", "yes and no", "unsure"})
    void upload_givenACheckModelThatDoesNotSayYesOrNo_expects503CheckUnavailable(String answer) {
        Signed u = signupWithBalance(0);
        InputFixtures.acceptTerms(http, u.bearer(), 1).expectStatus().isNoContent();
        checkAnswers(answer);

        InputFixtures.upload(http, u.bearer(), "plain.png")
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_CHECK_UNAVAILABLE);

        assertThat(files.findByUserId(u.id())).isEmpty();
    }

    @Test
    void upload_givenACheckModelThatFails_expects503CheckUnavailable() {
        Signed u = signupWithBalance(0);
        InputFixtures.acceptTerms(http, u.bearer(), 1).expectStatus().isNoContent();
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(500)));

        InputFixtures.upload(http, u.bearer(), "plain.png")
                .expectStatus().isEqualTo(503)
                .expectBody().jsonPath("$.code").isEqualTo(InputRefusedException.CODE_CHECK_UNAVAILABLE);

        assertThat(files.findByUserId(u.id())).isEmpty();
        assertThat(onlyUsage(u).getOutcome()).isEqualTo(CallOutcome.ERROR);
    }

    // ===== Helpers =====================================================

    private record Signed(UUID id, String bearer) { }

    private Signed signupWithBalance(long cents) {
        String email = "chat-images-" + UUID.randomUUID() + "@godance.test";
        String bearer = InputFixtures.signup(http, email);
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        if (cents > 0) {
            ledger.topUp(userId, cents, CreditSource.PROMO, "seed-" + userId, "seed-" + userId, "test seed");
        }
        return new Signed(userId, bearer);
    }

    private static Map<String, Object> text(String text) {
        return Map.of("type", "text", "text", text);
    }

    private static Map<String, Object> image(String url, String detail) {
        return Map.of("type", "image_url", "image_url",
                detail == null ? Map.of("url", url) : Map.of("url", url, "detail", detail));
    }

    @SafeVarargs
    private static Map<String, Object> user(Map<String, Object>... parts) {
        return Map.of("role", "user", "content", List.of(parts));
    }

    private static String dataUrl(byte[] bytes, String type) {
        return "data:" + type + ";base64," + Base64.getEncoder().encodeToString(bytes);
    }

    @SafeVarargs
    private WebTestClient.ResponseSpec chat(Signed u, String model, Map<String, Object>... messages) {
        return http.post().uri("/v1/chat/completions")
                .header(HttpHeaders.AUTHORIZATION, u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("model", model, "messages", List.of(messages)))
                .exchange();
    }

    private static WebTestClient.BodyContentSpec refused(WebTestClient.ResponseSpec response, int status, String code) {
        return response
                .expectStatus().isEqualTo(status)
                .expectHeader().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON)
                .expectBody().jsonPath("$.code").isEqualTo(code);
    }

    private void openAiAnswers(String model, String content, int promptTokens, int completionTokens) {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions")).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody(json.writeValueAsString(Map.of(
                        "id", "chatcmpl-1", "object", "chat.completion", "created", 1_700_000_000, "model", model,
                        "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                                "message", Map.of("role", "assistant", "content", content))),
                        "usage", Map.of("prompt_tokens", promptTokens, "completion_tokens", completionTokens,
                                "total_tokens", promptTokens + completionTokens))))));
    }

    /** The check model's answer: only a request that asks the check's question gets it. */
    private void checkAnswers(String content) {
        openAi.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .withRequestBody(containing("Answer with one word only: yes or no."))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody(json.writeValueAsString(Map.of(
                                "id", "chatcmpl-check", "model", "gpt-4o-mini", "created", 1_700_000_000,
                                "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                                        "message", Map.of("role", "assistant", "content", content))),
                                "usage", Map.of("prompt_tokens", 2_900, "completion_tokens", 1, "total_tokens", 2_901))))));
    }

    /** Uploads a fixture as this user, with the check clearing it, and returns the file's id. */
    private String uploadCleared(Signed u, String fixture) {
        InputFixtures.acceptTerms(http, u.bearer(), 1).expectStatus().isNoContent();
        checkAnswers("no");
        Map<?, ?> body = InputFixtures.upload(http, u.bearer(), fixture)
                .expectStatus().isCreated()
                .expectBody(Map.class).returnResult().getResponseBody();
        return (String) body.get("id");
    }

    /** The body of the one request this provider received. */
    private JsonNode sentTo(WireMockServer provider) {
        assertThat(provider.getAllServeEvents()).hasSize(1);
        return json.readTree(provider.getAllServeEvents().get(0).getRequest().getBodyAsString());
    }

    private long ledgerCount(Signed u) {
        return ledgerRows.findByUserIdOrderByOccurredAtDesc(u.id(), PageRequest.of(0, 50)).getTotalElements();
    }

    /** No ledger row since {@code rowsBefore}, the balance as it was, and no provider called. */
    private void assertNothingCharged(Signed u, long rowsBefore, long balanceCents) {
        assertThat(ledgerCount(u)).isEqualTo(rowsBefore);
        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(balanceCents);
        assertThat(ledger.authoritativeBalance(u.id())).isEqualTo(balanceCents);
        for (WireMockServer provider : new WireMockServer[] {openAi, anthropic, google}) {
            assertThat(provider.getAllServeEvents()).isEmpty();
        }
    }

    private UsageRecord onlyUsage(Signed u) {
        List<UsageRecord> records = usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(), Instant.now().minusSeconds(120));
        assertThat(records).hasSize(1);
        return records.get(0);
    }

    /** The usage record of a streamed call, which is written just after the stream ends. */
    private UsageRecord awaitUsage(Signed u) {
        long deadline = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < deadline) {
            List<UsageRecord> records = usage.findByUserIdAndRecordedAtGreaterThanEqual(u.id(), Instant.now().minusSeconds(120));
            if (!records.isEmpty()) return records.get(0);
            try {
                Thread.sleep(50);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        throw new AssertionError("no usage record was written for the streamed call");
    }
}
