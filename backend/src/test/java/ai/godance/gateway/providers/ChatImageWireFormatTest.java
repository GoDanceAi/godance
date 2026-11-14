package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.AlibabaProperties;
import ai.godance.config.AnthropicProperties;
import ai.godance.config.BaiduProperties;
import ai.godance.config.DeepSeekProperties;
import ai.godance.config.GoogleProperties;
import ai.godance.config.MiniMaxProperties;
import ai.godance.config.MoonshotProperties;
import ai.godance.config.OpenAIProperties;
import ai.godance.config.VolcengineProperties;
import ai.godance.config.XaiProperties;
import ai.godance.config.ZhipuProperties;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ChatDtos.ImagePart;
import ai.godance.gateway.ChatDtos.TextPart;
import ai.godance.gateway.ProviderAdapter;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A chat message that carries an image reaches each provider in the form
 * that provider documents (round 11 brief 02): OpenAI's {@code image_url}
 * part with a data URL, Anthropic's base64 {@code image} block, Gemini's
 * {@code inlineData} part. Messages without an image in the same request
 * stay plain strings.
 */
class ChatImageWireFormatTest {

    private static final Duration T = Duration.ofSeconds(5);
    private static final byte[] IMAGE = {(byte) 0x89, 'P', 'N', 'G', 1, 2, 3, 4, 5};
    private static final String IMAGE_BASE64 = Base64.getEncoder().encodeToString(IMAGE);

    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        wm.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    /** System text, then a user message of text, an image, more text and a second image with a detail. */
    private static ChatRequest request(boolean stream) {
        return new ChatRequest("the-model",
                List.of(new ChatMessage("system", "Be brief."),
                        new ChatMessage("user", List.of(
                                new TextPart("What is this?"),
                                new ImagePart(IMAGE, "image/png", null),
                                new TextPart("And this?"),
                                new ImagePart(IMAGE, "image/webp", "low"))),
                        new ChatMessage("assistant", "A mug."),
                        new ChatMessage("user", "Thanks")),
                64, 0.2, stream);
    }

    record Case(String label, BiFunction<String, ObjectMapper, ProviderAdapter> factory) {
        @Override public String toString() { return label; }
    }

    static Stream<Case> openAiShaped() {
        return Stream.of(
                new Case("OpenAI", (base, j) -> new OpenAIProvider(new OpenAIProperties("test-key", base, T), j)),
                new Case("DeepSeek", (base, j) -> new DeepSeekProvider(new DeepSeekProperties("test-key", base, T), j)),
                new Case("Moonshot", (base, j) -> new MoonshotProvider(new MoonshotProperties("test-key", base, T), j)),
                new Case("AlibabaDashScope",
                        (base, j) -> new AlibabaDashScopeProvider(new AlibabaProperties("test-key", base, T), j)),
                new Case("Zhipu", (base, j) -> new ZhipuProvider(new ZhipuProperties("test-key", base, T), j)),
                new Case("MiniMax", (base, j) -> new MiniMaxProvider(new MiniMaxProperties("test-key", base, T), j)),
                new Case("VolcengineArkDoubao", (base, j) -> new VolcengineArkChatProvider(
                        new VolcengineProperties("test-key", base, Duration.ofSeconds(1), T), j)),
                new Case("Xai", (base, j) -> new XaiProvider(new XaiProperties("test-key", base, T), j)),
                new Case("Baidu", (base, j) -> new BaiduProvider(new BaiduProperties("test-key", base, T), j)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("openAiShaped")
    void chat_givenImages_expectsOpenAisImageUrlPartsWithDataUrls(Case c) {
        ProviderAdapter provider = c.factory().apply(wm.baseUrl(), json);

        provider.chat(request(false)).onErrorComplete().block(T);

        assertOpenAiShape(sentBody(), false);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("openAiShaped")
    void chatStream_givenImages_expectsTheSamePartsAndTheStreamFlags(Case c) {
        ProviderAdapter provider = c.factory().apply(wm.baseUrl(), json);

        provider.chatStream(request(true)).onErrorComplete().blockLast(T);

        assertOpenAiShape(sentBody(), true);
    }

    private void assertOpenAiShape(JsonNode body, boolean stream) {
        assertThat(body.path("stream").asBoolean()).isEqualTo(stream);
        assertThat(body.path("stream_options").path("include_usage").asBoolean(false)).isEqualTo(stream);
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(4);
        // Messages without an image are strings, as they always were.
        assertThat(messages.get(0).path("content").isString()).isTrue();
        assertThat(messages.get(2).path("content").asString()).isEqualTo("A mug.");
        assertThat(messages.get(3).path("content").asString()).isEqualTo("Thanks");
        // The one with images is the array of parts, in order.
        JsonNode parts = messages.get(1).path("content");
        assertThat(parts.isArray()).isTrue();
        assertThat(parts).hasSize(4);
        assertThat(parts.get(0)).isEqualTo(json.readTree("{\"type\":\"text\",\"text\":\"What is this?\"}"));
        assertThat(parts.get(1)).isEqualTo(json.readTree(
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/png;base64," + IMAGE_BASE64 + "\"}}"));
        assertThat(parts.get(2)).isEqualTo(json.readTree("{\"type\":\"text\",\"text\":\"And this?\"}"));
        assertThat(parts.get(3)).isEqualTo(json.readTree(
                "{\"type\":\"image_url\",\"image_url\":{\"url\":\"data:image/webp;base64," + IMAGE_BASE64
                        + "\",\"detail\":\"low\"}}"));
    }

    @Test
    void anthropic_givenImages_expectsBase64ImageBlocks() {
        ProviderAdapter provider = new AnthropicProvider(new AnthropicProperties("test-key", wm.baseUrl(), "2023-06-01", T), json);

        provider.chat(request(false)).onErrorComplete().block(T);

        JsonNode body = sentBody();
        assertThat(body.path("system").asString()).isEqualTo("Be brief.");
        JsonNode messages = body.path("messages");
        assertThat(messages).hasSize(3);
        assertThat(messages.get(1).path("content").asString()).isEqualTo("A mug.");
        assertThat(messages.get(2).path("content").asString()).isEqualTo("Thanks");
        JsonNode blocks = messages.get(0).path("content");
        assertThat(blocks).hasSize(4);
        assertThat(blocks.get(0)).isEqualTo(json.readTree("{\"type\":\"text\",\"text\":\"What is this?\"}"));
        assertThat(blocks.get(1)).isEqualTo(json.readTree(
                "{\"type\":\"image\",\"source\":{\"type\":\"base64\",\"media_type\":\"image/png\",\"data\":\""
                        + IMAGE_BASE64 + "\"}}"));
        assertThat(blocks.get(3).path("source").path("media_type").asString()).isEqualTo("image/webp");
        // Streaming sends the same blocks.
        wm.resetRequests();
        provider.chatStream(request(true)).onErrorComplete().blockLast(T);
        assertThat(sentBody().path("messages").get(0).path("content")).isEqualTo(blocks);
        assertThat(sentBody().path("stream").asBoolean()).isTrue();
    }

    @Test
    void gemini_givenImages_expectsInlineDataParts() {
        ProviderAdapter provider = new GoogleVertexProvider(new GoogleProperties("test-key", wm.baseUrl(), T), json);

        provider.chat(request(false)).onErrorComplete().block(T);

        JsonNode body = sentBody();
        assertThat(body.path("systemInstruction").path("parts").get(0).path("text").asString()).isEqualTo("Be brief.");
        JsonNode contents = body.path("contents");
        assertThat(contents).hasSize(3);
        assertThat(contents.get(1)).isEqualTo(json.readTree("{\"role\":\"model\",\"parts\":[{\"text\":\"A mug.\"}]}"));
        JsonNode parts = contents.get(0).path("parts");
        assertThat(parts).hasSize(4);
        assertThat(parts.get(0)).isEqualTo(json.readTree("{\"text\":\"What is this?\"}"));
        assertThat(parts.get(1)).isEqualTo(json.readTree(
                "{\"inlineData\":{\"mimeType\":\"image/png\",\"data\":\"" + IMAGE_BASE64 + "\"}}"));
        assertThat(parts.get(3)).isEqualTo(json.readTree(
                "{\"inlineData\":{\"mimeType\":\"image/webp\",\"data\":\"" + IMAGE_BASE64 + "\"}}"));
        wm.resetRequests();
        provider.chatStream(request(true)).onErrorComplete().blockLast(T);
        assertThat(sentBody().path("contents").get(0).path("parts")).isEqualTo(parts);
    }

    @Test
    void chat_givenAnImageRequest_expectsTheProvidersUsageWithImageTokensPassedThrough() {
        // The provider counts the image into its prompt tokens; the adapter reports what it was told.
        wm.resetAll();
        wm.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody("{\"id\":\"c1\",\"model\":\"the-model\",\"created\":1,\"choices\":[{\"index\":0,"
                        + "\"message\":{\"role\":\"assistant\",\"content\":\"A mug.\"},\"finish_reason\":\"stop\"}],"
                        + "\"usage\":{\"prompt_tokens\":1125,\"completion_tokens\":3,\"total_tokens\":1128}}")));
        ProviderAdapter provider = new OpenAIProvider(new OpenAIProperties("test-key", wm.baseUrl(), T), json);

        ChatResponse response = provider.chat(request(false)).block(T);

        assertThat(response.usage().promptTokens()).isEqualTo(1125);
        assertThat(response.choices().get(0).message().content()).isEqualTo("A mug.");
        assertThat(response.choices().get(0).message().hasImages()).isFalse();
    }

    private JsonNode sentBody() {
        assertThat(wm.getAllServeEvents()).hasSize(1);
        return json.readTree(wm.getAllServeEvents().get(0).getRequest().getBodyAsString());
    }
}
