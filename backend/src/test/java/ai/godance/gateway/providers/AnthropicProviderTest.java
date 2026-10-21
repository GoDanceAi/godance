package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.config.AnthropicProperties;
import ai.godance.gateway.ChatDtos.ChatChunk;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;

class AnthropicProviderTest {

    private WireMockServer wm;
    private AnthropicProvider provider;
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        AnthropicProperties props = new AnthropicProperties(
                "test-api-key", wm.baseUrl(), "2023-06-01", Duration.ofSeconds(5));
        provider = new AnthropicProvider(props, json);
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private static ChatRequest userMessage(String text) {
        return new ChatRequest("claude-3-5-sonnet-20241022",
                List.of(new ChatMessage("user", text)),
                null, null, false);
    }

    @Test
    void chat_nonStreaming_mapsAnthropicMessagesResponseToDomain() {
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .withHeader("x-api-key", equalTo("test-api-key"))
                .withHeader("anthropic-version", equalTo("2023-06-01"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "id": "msg_abc",
                                  "type": "message",
                                  "role": "assistant",
                                  "model": "claude-3-5-sonnet-20241022",
                                  "content": [{"type": "text", "text": "Hi from Claude"}],
                                  "stop_reason": "end_turn",
                                  "usage": {"input_tokens": 11, "output_tokens": 5}
                                }
                                """)));

        ChatResponse r = provider.chat(userMessage("Hello Claude")).block(Duration.ofSeconds(5));

        assertThat(r).isNotNull();
        assertThat(r.id()).isEqualTo("msg_abc");
        assertThat(r.choices()).hasSize(1);
        assertThat(r.choices().get(0).message().role()).isEqualTo("assistant");
        assertThat(r.choices().get(0).message().content()).isEqualTo("Hi from Claude");
        assertThat(r.choices().get(0).finishReason()).isEqualTo("stop");   // end_turn → stop
        assertThat(r.usage().promptTokens()).isEqualTo(11);
        assertThat(r.usage().completionTokens()).isEqualTo(5);
        assertThat(r.usage().totalTokens()).isEqualTo(16);

        wm.verify(postRequestedFor(urlEqualTo("/v1/messages")));
    }

    @Test
    void chat_systemMessage_isLiftedToTopLevelSystemField() {
        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "id":"m","type":"message","role":"assistant",
                                  "model":"claude-3-5-sonnet-20241022",
                                  "content":[{"type":"text","text":"k"}],
                                  "stop_reason":"end_turn",
                                  "usage":{"input_tokens":1,"output_tokens":1}
                                }
                                """)));

        ChatRequest req = new ChatRequest("claude-3-5-sonnet-20241022",
                List.of(new ChatMessage("system", "Be concise"),
                        new ChatMessage("user", "Say hi")),
                null, null, false);
        provider.chat(req).block(Duration.ofSeconds(5));

        wm.verify(postRequestedFor(urlEqualTo("/v1/messages"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.system", equalTo("Be concise")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.messages[0].role", equalTo("user"))));
    }

    @Test
    void chatStream_emitsContentDeltas_andFinalChunkCarriesUsage() {
        String sseBody = String.join("",
                "data: {\"type\":\"message_start\",\"message\":{\"id\":\"msg_str\",",
                "\"usage\":{\"input_tokens\":3}}}\n\n",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Hel\"}}\n\n",
                "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"lo\"}}\n\n",
                "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"},",
                "\"usage\":{\"output_tokens\":2}}\n\n",
                "data: {\"type\":\"message_stop\"}\n\n");

        wm.stubFor(post(urlEqualTo("/v1/messages"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "text/event-stream")
                        .withBody(sseBody)));

        List<ChatChunk> chunks = provider.chatStream(userMessage("Hi"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull();
        // Expect: message_start (role chunk) + 2 content_block_delta + final message_stop chunk
        assertThat(chunks).hasSize(4);
        assertThat(chunks.get(0).choices().get(0).delta().role()).isEqualTo("assistant");
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo("Hel");
        assertThat(chunks.get(2).choices().get(0).delta().content()).isEqualTo("lo");
        ChatChunk last = chunks.get(3);
        assertThat(last.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(last.usage()).isNotNull();
        assertThat(last.usage().promptTokens()).isEqualTo(3);
        assertThat(last.usage().completionTokens()).isEqualTo(2);
    }
}
