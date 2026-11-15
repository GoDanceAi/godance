package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.any;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
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
import ai.godance.gateway.ProviderAdapter;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/**
 * A chat request whose messages are plain strings goes to every provider as
 * exactly the bytes it went as before images existed (round 11 brief 02).
 *
 * <p>The expected bodies here are written out in full and compared as
 * bytes, not as JSON. This class uses nothing that brief 02 added, so it
 * compiles and passes on the commit before it too: that is the proof that
 * the bytes did not change.
 */
class ChatStringContentWireFormatTest {

    private static final Duration T = Duration.ofSeconds(5);
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        // Whatever the provider answers is not the point here; the request is.
        wm.stubFor(any(anyUrl()).willReturn(aResponse().withStatus(500)));
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private static final String OPENAI_BODY = "{\"model\":\"the-model\",\"messages\":["
            + "{\"role\":\"system\",\"content\":\"Be brief.\"},"
            + "{\"role\":\"user\",\"content\":\"Hello \\\"there\\\"\\nsecond line — ünïcode\"},"
            + "{\"role\":\"assistant\",\"content\":\"Hi.\"},"
            + "{\"role\":\"user\",\"content\":\"And again\"}],"
            + "\"max_tokens\":64,\"temperature\":0.2,\"stream\":false}";

    private static final String OPENAI_STREAM_BODY = OPENAI_BODY.substring(0, OPENAI_BODY.length() - "false}".length())
            + "true,\"stream_options\":{\"include_usage\":true}}";

    private static final String ANTHROPIC_BODY = "{\"model\":\"the-model\",\"max_tokens\":64,\"system\":\"Be brief.\","
            + "\"messages\":["
            + "{\"role\":\"user\",\"content\":\"Hello \\\"there\\\"\\nsecond line — ünïcode\"},"
            + "{\"role\":\"assistant\",\"content\":\"Hi.\"},"
            + "{\"role\":\"user\",\"content\":\"And again\"}],"
            + "\"temperature\":0.2,\"stream\":false}";

    private static final String GEMINI_BODY = "{\"contents\":["
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"Hello \\\"there\\\"\\nsecond line — ünïcode\"}]},"
            + "{\"role\":\"model\",\"parts\":[{\"text\":\"Hi.\"}]},"
            + "{\"role\":\"user\",\"parts\":[{\"text\":\"And again\"}]}],"
            + "\"systemInstruction\":{\"role\":null,\"parts\":[{\"text\":\"Be brief.\"}]},"
            + "\"generationConfig\":{\"temperature\":0.2,\"maxOutputTokens\":64}}";

    /** A provider under test: its label, its adapter, and the exact body it sends for {@link #request}. */
    record Case(String label, BiFunction<String, ObjectMapper, ProviderAdapter> factory,
                String body, String streamBody) {
        @Override public String toString() { return label; }
    }

    static Stream<Case> providers() {
        return Stream.of(
                new Case("OpenAI", (base, j) -> new OpenAIProvider(new OpenAIProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("DeepSeek", (base, j) -> new DeepSeekProvider(new DeepSeekProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("Moonshot", (base, j) -> new MoonshotProvider(new MoonshotProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("AlibabaDashScope",
                        (base, j) -> new AlibabaDashScopeProvider(new AlibabaProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("Zhipu", (base, j) -> new ZhipuProvider(new ZhipuProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("MiniMax", (base, j) -> new MiniMaxProvider(new MiniMaxProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("VolcengineArkDoubao", (base, j) -> new VolcengineArkChatProvider(
                        new VolcengineProperties("test-key", base, Duration.ofSeconds(1), T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("Xai", (base, j) -> new XaiProvider(new XaiProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("Baidu", (base, j) -> new BaiduProvider(new BaiduProperties("test-key", base, T), j),
                        OPENAI_BODY, OPENAI_STREAM_BODY),
                new Case("Anthropic",
                        (base, j) -> new AnthropicProvider(new AnthropicProperties("test-key", base, "2023-06-01", T), j),
                        ANTHROPIC_BODY, ANTHROPIC_BODY.replace("\"stream\":false", "\"stream\":true")),
                new Case("Google", (base, j) -> new GoogleVertexProvider(new GoogleProperties("test-key", base, T), j),
                        GEMINI_BODY, GEMINI_BODY));
    }

    private static ChatRequest request(boolean stream) {
        return new ChatRequest("the-model",
                List.of(new ChatMessage("system", "Be brief."),
                        new ChatMessage("user", "Hello \"there\"\nsecond line — ünïcode"),
                        new ChatMessage("assistant", "Hi."),
                        new ChatMessage("user", "And again")),
                64, 0.2, stream);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void chat_givenStringContent_expectsExactlyTheBytesItAlwaysSent(Case c) {
        ProviderAdapter provider = c.factory().apply(wm.baseUrl(), json);

        provider.chat(request(false)).onErrorComplete().block(T);

        assertThat(wm.getAllServeEvents()).hasSize(1);
        assertThat(wm.getAllServeEvents().get(0).getRequest().getBodyAsString()).isEqualTo(c.body());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void chatStream_givenStringContent_expectsExactlyTheBytesItAlwaysSent(Case c) {
        ProviderAdapter provider = c.factory().apply(wm.baseUrl(), json);

        provider.chatStream(request(true)).onErrorComplete().blockLast(T);

        assertThat(wm.getAllServeEvents()).hasSize(1);
        assertThat(wm.getAllServeEvents().get(0).getRequest().getBodyAsString()).isEqualTo(c.streamBody());
    }
}
