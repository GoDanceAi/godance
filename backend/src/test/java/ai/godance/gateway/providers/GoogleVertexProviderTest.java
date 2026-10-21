package ai.godance.gateway.providers;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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

import ai.godance.config.GoogleProperties;
import ai.godance.gateway.ChatDtos.ChatChunk;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;

class GoogleVertexProviderTest {

    private WireMockServer wm;
    private GoogleVertexProvider provider;
    private final ObjectMapper json = JsonMapper.builderWithJackson2Defaults().build();

    @BeforeEach
    void start() {
        wm = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        wm.start();
        GoogleProperties props = new GoogleProperties("test-key", wm.baseUrl(), Duration.ofSeconds(5));
        provider = new GoogleVertexProvider(props, json);
    }

    @AfterEach
    void stop() {
        wm.stop();
    }

    private static ChatRequest userMessage(String text) {
        return new ChatRequest("gemini-1.5-flash",
                List.of(new ChatMessage("user", text)),
                null, null, false);
    }

    @Test
    void chat_nonStreaming_mapsCandidatesAndUsageMetadata() {
        wm.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-1.5-flash:generateContent"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {
                                  "candidates": [{
                                    "content": {"role": "model",
                                                "parts": [{"text": "Hi from Gemini"}]},
                                    "finishReason": "STOP"
                                  }],
                                  "usageMetadata": {
                                    "promptTokenCount": 7,
                                    "candidatesTokenCount": 4,
                                    "totalTokenCount": 11
                                  }
                                }
                                """)));

        ChatResponse r = provider.chat(userMessage("Hello Gemini")).block(Duration.ofSeconds(5));

        assertThat(r).isNotNull();
        assertThat(r.choices()).hasSize(1);
        assertThat(r.choices().get(0).message().role()).isEqualTo("assistant");
        assertThat(r.choices().get(0).message().content()).isEqualTo("Hi from Gemini");
        assertThat(r.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(r.usage().promptTokens()).isEqualTo(7);
        assertThat(r.usage().completionTokens()).isEqualTo(4);
        assertThat(r.usage().totalTokens()).isEqualTo(11);
    }

    @Test
    void chat_systemMessage_isLiftedToSystemInstructionField() {
        wm.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-1.5-flash:generateContent"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"candidates":[{"content":{"role":"model","parts":[{"text":"k"}]},
                                "finishReason":"STOP"}],
                                "usageMetadata":{"promptTokenCount":1,"candidatesTokenCount":1,
                                "totalTokenCount":2}}
                                """)));

        ChatRequest req = new ChatRequest("gemini-1.5-flash",
                List.of(new ChatMessage("system", "Reply briefly"),
                        new ChatMessage("user", "Say hi")),
                null, null, false);
        provider.chat(req).block(Duration.ofSeconds(5));

        wm.verify(postRequestedFor(urlPathEqualTo("/v1beta/models/gemini-1.5-flash:generateContent"))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.systemInstruction.parts[0].text",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo("Reply briefly")))
                .withRequestBody(com.github.tomakehurst.wiremock.client.WireMock
                        .matchingJsonPath("$.contents[0].role",
                                com.github.tomakehurst.wiremock.client.WireMock.equalTo("user"))));
    }

    @Test
    void chatStream_emitsDeltasAndCarriesUsageOnFinalChunk() {
        String sseBody = String.join("",
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"He\"}]}}]}\n\n",
                "data: {\"candidates\":[{\"content\":{\"role\":\"model\",\"parts\":[{\"text\":\"llo\"}]}}]}\n\n",
                "data: {\"candidates\":[{\"finishReason\":\"STOP\",\"content\":{\"role\":\"model\",\"parts\":[]}}],",
                "\"usageMetadata\":{\"promptTokenCount\":3,\"candidatesTokenCount\":2,\"totalTokenCount\":5}}\n\n");

        wm.stubFor(post(urlPathEqualTo("/v1beta/models/gemini-1.5-flash:streamGenerateContent"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "text/event-stream")
                        .withBody(sseBody)));

        List<ChatChunk> chunks = provider.chatStream(userMessage("Hi"))
                .collectList()
                .block(Duration.ofSeconds(5));

        assertThat(chunks).isNotNull().hasSize(3);
        assertThat(chunks.get(0).choices().get(0).delta().content()).isEqualTo("He");
        assertThat(chunks.get(1).choices().get(0).delta().content()).isEqualTo("llo");
        ChatChunk last = chunks.get(2);
        assertThat(last.choices().get(0).finishReason()).isEqualTo("stop");
        assertThat(last.usage()).isNotNull();
        assertThat(last.usage().promptTokens()).isEqualTo(3);
        assertThat(last.usage().completionTokens()).isEqualTo(2);
    }
}
