package ai.godance.gateway.providers;

import java.util.ArrayList;
import java.util.List;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.util.UriComponentsBuilder;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.GoogleProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ChatDtos.ChatChoice;
import ai.godance.gateway.ChatDtos.ChatChunk;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ChatDtos.ChunkChoice;
import ai.godance.gateway.ChatDtos.ChunkDelta;
import ai.godance.gateway.ChatDtos.Usage;
import ai.godance.gateway.ProviderAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Google "Gemini Developer API" — API-key-authenticated counterpart to
 * the OAuth-only Vertex AI surface. Same model line-up, simpler auth
 * for v1. Endpoints:
 *
 * <ul>
 *   <li>{@code POST /v1beta/models/{model}:generateContent?key=...}</li>
 *   <li>{@code POST /v1beta/models/{model}:streamGenerateContent?key=...&alt=sse}</li>
 * </ul>
 *
 * <p>Role translation: OpenAI's {@code assistant} maps to Gemini's
 * {@code model}; system prompts go into {@code systemInstruction} not
 * inline messages.
 */
@Component
public class GoogleVertexProvider implements ProviderAdapter {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final WebClient client;
    private final GoogleProperties props;
    private final ObjectMapper json;

    public GoogleVertexProvider(GoogleProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Provider type() {
        return Provider.GOOGLE_VERTEX;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        GeminiRequest body = buildBody(request);
        String uri = UriComponentsBuilder.fromPath("/v1beta/models/{model}:generateContent")
                .queryParam("key", props.apiKey())
                .buildAndExpand(request.model())
                .toUriString();
        return client.post()
                .uri(uri)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(GeminiResponse.class)
                .map(r -> toDomain(r, request.model()))
                .timeout(props.timeout());
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        GeminiRequest body = buildBody(request);
        String uri = UriComponentsBuilder.fromPath("/v1beta/models/{model}:streamGenerateContent")
                .queryParam("key", props.apiKey())
                .queryParam("alt", "sse")
                .buildAndExpand(request.model())
                .toUriString();
        long chunkBase = System.currentTimeMillis() / 1000;
        return client.post()
                .uri(uri)
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .flatMap(event -> parseStreamEvent(event, request.model(), chunkBase))
                .timeout(props.timeout());
    }

    private Flux<ChatChunk> parseStreamEvent(ServerSentEvent<String> sse, String model, long created) {
        if (sse.data() == null || sse.data().isBlank()) return Flux.empty();
        try {
            GeminiResponse chunk = json.readValue(sse.data(), GeminiResponse.class);
            String text = extractText(chunk);
            String finish = mapFinishReason(extractFinishReason(chunk));
            Usage usage = chunk.usageMetadata() == null ? null
                    : new Usage(chunk.usageMetadata().promptTokenCount(),
                                chunk.usageMetadata().candidatesTokenCount(),
                                chunk.usageMetadata().totalTokenCount());

            // Gemini's streaming response includes finishReason on the last
            // candidate chunk and usageMetadata on a separate trailing chunk
            // (or alongside the final candidate, depending on model). Emit a
            // chunk per non-empty event; usage may arrive on its own.
            if (text.isEmpty() && finish == null && usage == null) return Flux.empty();

            String streamId = "gemini-" + Long.toHexString(created);
            ChunkDelta delta = new ChunkDelta(
                    text.isEmpty() ? null : "assistant",
                    text.isEmpty() ? null : text);
            return Flux.just(new ChatChunk(streamId, model, created,
                    List.of(new ChunkChoice(0, delta, finish)),
                    usage));
        } catch (Exception e) {
            return Flux.error(new IllegalStateException(
                    "could not parse Gemini SSE event: " + sse.data(), e));
        }
    }

    private static GeminiRequest buildBody(ChatRequest request) {
        List<GeminiContent> contents = new ArrayList<>();
        GeminiContent systemInstruction = null;
        for (ChatMessage m : request.messages()) {
            if ("system".equalsIgnoreCase(m.role())) {
                // Gemini supports one systemInstruction; concat if multiple.
                if (systemInstruction == null) {
                    systemInstruction = new GeminiContent(null, List.of(new GeminiPart(m.content())));
                } else {
                    String merged = systemInstruction.parts().get(0).text() + "\n\n" + m.content();
                    systemInstruction = new GeminiContent(null, List.of(new GeminiPart(merged)));
                }
                continue;
            }
            String geminiRole = "assistant".equalsIgnoreCase(m.role()) ? "model" : "user";
            contents.add(new GeminiContent(geminiRole, List.of(new GeminiPart(m.content()))));
        }
        int maxTokens = request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens();
        GeminiGenerationConfig cfg = new GeminiGenerationConfig(request.temperature(), maxTokens);
        return new GeminiRequest(contents, systemInstruction, cfg);
    }

    private static ChatResponse toDomain(GeminiResponse r, String requestedModel) {
        String text = extractText(r);
        ChatMessage assistantMsg = new ChatMessage("assistant", text);
        ChatChoice choice = new ChatChoice(0, assistantMsg, mapFinishReason(extractFinishReason(r)));
        int input = r.usageMetadata() == null ? 0 : r.usageMetadata().promptTokenCount();
        int output = r.usageMetadata() == null ? 0 : r.usageMetadata().candidatesTokenCount();
        int total = r.usageMetadata() == null ? input + output : r.usageMetadata().totalTokenCount();
        return new ChatResponse(
                "gemini-" + Long.toHexString(System.currentTimeMillis()),
                requestedModel, List.of(choice),
                new Usage(input, output, total),
                System.currentTimeMillis() / 1000);
    }

    private static String extractText(GeminiResponse r) {
        if (r.candidates() == null || r.candidates().isEmpty()) return "";
        GeminiCandidate c = r.candidates().get(0);
        if (c.content() == null || c.content().parts() == null) return "";
        StringBuilder sb = new StringBuilder();
        for (GeminiPart p : c.content().parts()) {
            if (p.text() != null) sb.append(p.text());
        }
        return sb.toString();
    }

    private static String extractFinishReason(GeminiResponse r) {
        if (r.candidates() == null || r.candidates().isEmpty()) return null;
        return r.candidates().get(0).finishReason();
    }

    private static String mapFinishReason(String geminiReason) {
        if (geminiReason == null) return null;
        return switch (geminiReason) {
            case "STOP" -> "stop";
            case "MAX_TOKENS" -> "length";
            case "SAFETY", "RECITATION", "BLOCKLIST", "PROHIBITED_CONTENT" -> "content_filter";
            case "OTHER" -> "stop";
            default -> geminiReason.toLowerCase();
        };
    }

    // ----- Gemini wire shapes -----------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GeminiRequest(
            List<GeminiContent> contents,
            @JsonProperty("systemInstruction") GeminiContent systemInstruction,
            @JsonProperty("generationConfig") GeminiGenerationConfig generationConfig
    ) { }

    record GeminiContent(String role, List<GeminiPart> parts) { }

    record GeminiPart(String text) { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record GeminiGenerationConfig(
            Double temperature,
            @JsonProperty("maxOutputTokens") Integer maxOutputTokens
    ) { }

    record GeminiResponse(
            List<GeminiCandidate> candidates,
            @JsonProperty("usageMetadata") GeminiUsageMetadata usageMetadata
    ) { }

    record GeminiCandidate(
            GeminiContent content,
            @JsonProperty("finishReason") String finishReason
    ) { }

    record GeminiUsageMetadata(
            @JsonProperty("promptTokenCount") int promptTokenCount,
            @JsonProperty("candidatesTokenCount") int candidatesTokenCount,
            @JsonProperty("totalTokenCount") int totalTokenCount
    ) { }
}
