package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;
import java.util.function.BiFunction;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.AlibabaProperties;
import ai.godance.config.BaiduProperties;
import ai.godance.config.DeepSeekProperties;
import ai.godance.config.MiniMaxProperties;
import ai.godance.config.MoonshotProperties;
import ai.godance.config.VolcengineProperties;
import ai.godance.config.XaiProperties;
import ai.godance.config.ZhipuProperties;
import ai.godance.gateway.ChatDtos.ChatChunk;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ProviderAdapter;

/**
 * Cassette tests for the eight OpenAI-compatible text providers added in
 * the catalog refresh. Each parameterized case is its own cassette —
 * a recorded request/response shape replayed through the real adapter
 * (not a mock of {@link ProviderAdapter}). The parameterization proves
 * each adapter's upstream wiring (path + Bearer auth); the shared
 * request/response mapping is exercised once per provider.
 */
class OpenAICompatibleProviderTest {

    private static final Duration T = Duration.ofSeconds(5);
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();
    private WireMockServer wm;

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    /** A provider under test: display label, expected upstream path, adapter factory. */
    record Case(String label, String path,
                BiFunction<String, ObjectMapper, ProviderAdapter> factory) {
        @Override public String toString() { return label; }
    }

    static Stream<Case> providers() {
        return Stream.of(
                new Case("DeepSeek", "/v1/chat/completions",
                        (base, j) -> new DeepSeekProvider(new DeepSeekProperties("test-key", base, T), j)),
                new Case("Moonshot", "/v1/chat/completions",
                        (base, j) -> new MoonshotProvider(new MoonshotProperties("test-key", base, T), j)),
                new Case("AlibabaDashScope", "/v1/chat/completions",
                        (base, j) -> new AlibabaDashScopeProvider(new AlibabaProperties("test-key", base, T), j)),
                new Case("Zhipu", "/chat/completions",
                        (base, j) -> new ZhipuProvider(new ZhipuProperties("test-key", base, T), j)),
                new Case("MiniMax", "/v1/text/chatcompletion_v2",
                        (base, j) -> new MiniMaxProvider(new MiniMaxProperties("test-key", base, T), j)),
                new Case("VolcengineArkDoubao", "/api/v3/chat/completions",
                        (base, j) -> new VolcengineArkChatProvider(
                                new VolcengineProperties("test-key", base, Duration.ofSeconds(1), T), j)),
                new Case("Xai", "/v1/chat/completions",
                        (base, j) -> new XaiProvider(new XaiProperties("test-key", base, T), j)),
                new Case("Baidu", "/chat/completions",
                        (base, j) -> new BaiduProvider(new BaiduProperties("test-key", base, T), j)));
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("providers")
    void chat_givenOpenAiCompatibleResponse_mapsToDomain(Case c) {
        wm.stubFor(post(urlEqualTo(c.path()))
                .withHeader("Authorization", equalTo("Bearer test-key"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "id": "chatcmpl-1",
                                  "object": "chat.completion",
                                  "created": 1716200000,
                                  "model": "test-model",
                                  "choices": [{
                                    "index": 0,
                                    "message": {"role": "assistant", "content": "Hi there"},
                                    "finish_reason": "stop"
                                  }],
                                  "usage": {"prompt_tokens": 12, "completion_tokens": 7, "total_tokens": 19}
                                }
                                """)));

        ProviderAdapter provider = c.factory().apply(wm.baseUrl(), json);
        ChatResponse r = provider.chat(new ChatRequest("test-model",
                        List.of(new ChatMessage("user", "Hello")), null, null, false))
                .block(Duration.ofSeconds(5));

        assertThat(r).isNotNull();
        assertThat(r.id()).isEqualTo("chatcmpl-1");
        assertThat(r.model()).isEqualTo("test-model");
        assertThat(r.choices()).hasSize(1);
        assertThat(r.choices().get(0).message().role()).isEqualTo("assistant");
        assertThat(r.choices().get(0).message().content()).isEqualTo("Hi there");
        assertThat(r.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(r.usage().promptTokens()).isEqualTo(12);
        assertThat(r.usage().completionTokens()).isEqualTo(7);
        assertThat(r.usage().totalTokens()).isEqualTo(19);

        wm.verify(postRequestedFor(urlEqualTo(c.path())));
    }

    @Test
    void chatStream_givenSseStream_emitsContentDeltasAndFinalUsage() {
        String sse = String.join("",
                "data: {\"id\":\"c1\",\"model\":\"deepseek-v4-flash\",\"created\":1,",
                "\"choices\":[{\"index\":0,\"delta\":{\"role\":\"assistant\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"c1\",\"model\":\"deepseek-v4-flash\",\"created\":1,",
                "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"Hel\"},\"finish_reason\":null}]}\n\n",
                "data: {\"id\":\"c1\",\"model\":\"deepseek-v4-flash\",\"created\":1,",
                "\"choices\":[{\"index\":0,\"delta\":{\"content\":\"lo\"},\"finish_reason\":\"stop\"}]}\n\n",
                "data: {\"id\":\"c1\",\"model\":\"deepseek-v4-flash\",\"created\":1,\"choices\":[],",
                "\"usage\":{\"prompt_tokens\":4,\"completion_tokens\":2,\"total_tokens\":6}}\n\n",
                "data: [DONE]\n\n");

        wm.stubFor(post(urlEqualTo("/v1/chat/completions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "text/event-stream")
                        .withBody(sse)));

        DeepSeekProvider provider = new DeepSeekProvider(
                new DeepSeekProperties("test-key", wm.baseUrl(), Duration.ofSeconds(5)), json);
        List<ChatChunk> chunks = provider.chatStream(new ChatRequest("deepseek-v4-flash",
                        List.of(new ChatMessage("user", "Hi")), null, null, true))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull();
        assertThat(chunks).hasSize(4);   // 3 choice chunks + 1 trailing usage chunk; [DONE] stops the stream
        assertThat(chunks.get(0).choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo("Hel");
        assertThat(chunks.get(2).choices().get(0).delta().content()).isEqualTo("lo");
        assertThat(chunks.get(2).choices().get(0).finishReason()).isEqualTo("stop");
        ChatChunk last = chunks.get(3);
        assertThat(last.usage()).isNotNull();
        assertThat(last.usage().promptTokens()).isEqualTo(4);
        assertThat(last.usage().completionTokens()).isEqualTo(2);
    }
}
