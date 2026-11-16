package ai.godance.gateway.providers;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ai.godance.gateway.CallerKey;
import ai.godance.config.AnthropicProperties;
import ai.godance.domain.Provider;
import ai.godance.gateway.ChatDtos.ChatChoice;
import ai.godance.gateway.ChatDtos.ChatChunk;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ContentPart;
import ai.godance.gateway.ChatDtos.ImagePart;
import ai.godance.gateway.ChatDtos.TextPart;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ChatDtos.ChunkChoice;
import ai.godance.gateway.ChatDtos.ChunkDelta;
import ai.godance.gateway.ChatDtos.Usage;
import ai.godance.gateway.ProviderAdapter;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Anthropic Messages API ({@code POST /v1/messages}).
 *
 * <p>Key shape differences from OpenAI:
 * <ul>
 *   <li>System prompt is a top-level {@code system} field, not a role.</li>
 *   <li>{@code max_tokens} is required by Anthropic; we default if absent.</li>
 *   <li>Response content is a list of typed blocks; we flatten {@code text} blocks.</li>
 *   <li>Streaming uses named SSE events (e.g. {@code message_delta}); the
 *       adapter accumulates {@code content_block_delta.text} into our
 *       ChunkDelta shape and pulls usage from {@code message_delta.usage}.</li>
 * </ul>
 */
@Component
public class AnthropicProvider implements ProviderAdapter {

    private static final int DEFAULT_MAX_TOKENS = 4096;

    private final WebClient client;
    private final AnthropicProperties props;
    private final ObjectMapper json;

    public AnthropicProvider(AnthropicProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        // The key goes on each request (round 14): the caller's own, when the
        // call carries one, else the platform's.
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader("anthropic-version", props.apiVersion())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Provider type() {
        return Provider.ANTHROPIC;
    }

    @Override
    public boolean takesCallerKeys() {
        return true;
    }

    private String apiKey(reactor.util.context.ContextView context) {
        return CallerKey.or(context, Provider.ANTHROPIC, props.apiKey());
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        AnthropicRequest body = buildBody(request, false);
        return Mono.deferContextual(context -> client.post()
                        .uri("/v1/messages")
                        .header("x-api-key", apiKey(context))
                        .bodyValue(body)
                        .retrieve()
                        .bodyToMono(AnthropicResponse.class))
                .map(r -> toDomain(r, request.model()))
                .timeout(props.timeout());
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        AnthropicRequest body = buildBody(request, true);
        AtomicReference<String> messageId = new AtomicReference<>("msg_unknown");
        AtomicReference<Integer> inputTokens = new AtomicReference<>(0);
        AtomicInteger outputTokens = new AtomicInteger(0);
        AtomicReference<String> stopReason = new AtomicReference<>();

        return Flux.deferContextual(context -> client.post()
                        .uri("/v1/messages")
                        .header("x-api-key", apiKey(context))
                        .accept(MediaType.TEXT_EVENT_STREAM)
                        .bodyValue(body)
                        .retrieve()
                        .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {}))
                .flatMap(event -> parseStreamEvent(event, request.model(),
                        messageId, inputTokens, outputTokens, stopReason))
                .timeout(props.timeout());
    }

    private Flux<ChatChunk> parseStreamEvent(ServerSentEvent<String> sse, String model,
                                             AtomicReference<String> messageId,
                                             AtomicReference<Integer> inputTokens,
                                             AtomicInteger outputTokens,
                                             AtomicReference<String> stopReason) {
        if (sse.data() == null || sse.data().isBlank()) return Flux.empty();
        try {
            JsonNode root = json.readTree(sse.data());
            // asString(""): like Jackson 2's asText(), a non-string type falls to default.
            String type = root.path("type").asString("");
            long now = System.currentTimeMillis() / 1000;
            return switch (type) {
                case "message_start" -> {
                    String id = root.path("message").path("id").asString("msg_unknown");
                    messageId.set(id);
                    int input = root.path("message").path("usage").path("input_tokens").asInt(0);
                    inputTokens.set(input);
                    // Emit a role-only opening chunk (matches OpenAI's first-chunk convention).
                    yield Flux.just(new ChatChunk(id, model, now,
                            List.of(new ChunkChoice(0, new ChunkDelta("assistant", null), null)),
                            null));
                }
                case "content_block_delta" -> {
                    String text = root.path("delta").path("text").asString("");
                    if (text.isEmpty()) yield Flux.empty();
                    yield Flux.just(new ChatChunk(messageId.get(), model, now,
                            List.of(new ChunkChoice(0, new ChunkDelta(null, text), null)),
                            null));
                }
                case "message_delta" -> {
                    int output = root.path("usage").path("output_tokens").asInt(0);
                    if (output > 0) outputTokens.set(output);
                    String stop = root.path("delta").path("stop_reason").asString(null);
                    if (stop != null) stopReason.set(stop);
                    yield Flux.empty();
                }
                case "message_stop" -> {
                    // Terminal: emit finish_reason + usage in one final chunk.
                    Usage usage = new Usage(
                            inputTokens.get(),
                            outputTokens.get(),
                            inputTokens.get() + outputTokens.get());
                    yield Flux.just(new ChatChunk(messageId.get(), model, now,
                            List.of(new ChunkChoice(0, new ChunkDelta(null, null),
                                    mapStopReason(stopReason.get()))),
                            usage));
                }
                default -> Flux.empty();
            };
        } catch (Exception e) {
            return Flux.error(new IllegalStateException(
                    "could not parse Anthropic SSE event: " + sse.data(), e));
        }
    }

    private static AnthropicRequest buildBody(ChatRequest request, boolean stream) {
        List<AnthropicMessage> messages = new ArrayList<>();
        String system = null;
        for (ChatMessage m : request.messages()) {
            if ("system".equalsIgnoreCase(m.role())) {
                system = system == null ? m.content() : system + "\n\n" + m.content();
                continue;
            }
            messages.add(new AnthropicMessage(m.role(), contentOf(m)));
        }
        int maxTokens = request.maxTokens() == null ? DEFAULT_MAX_TOKENS : request.maxTokens();
        return new AnthropicRequest(request.model(), maxTokens, system, messages,
                request.temperature(), stream);
    }

    /**
     * A message's content for the Messages API. Text is a plain string, as
     * it always was. A message with an image is a list of content blocks,
     * each image inline as base64:
     * {@code {"type": "image", "source": {"type": "base64", "media_type": "image/png", "data": "…"}}}.
     */
    private static Object contentOf(ChatMessage m) {
        if (!m.hasImages()) return m.content();
        List<Map<String, Object>> blocks = new ArrayList<>(m.parts().size());
        for (ContentPart part : m.parts()) {
            Map<String, Object> block = new LinkedHashMap<>();
            if (part instanceof TextPart text) {
                block.put("type", "text");
                block.put("text", text.text());
            } else if (part instanceof ImagePart image) {
                Map<String, Object> source = new LinkedHashMap<>();
                source.put("type", "base64");
                source.put("media_type", image.mediaType());
                source.put("data", image.base64());
                block.put("type", "image");
                block.put("source", source);
            }
            blocks.add(block);
        }
        return blocks;
    }

    private static ChatResponse toDomain(AnthropicResponse r, String requestedModel) {
        String text = r.content() == null ? "" :
                r.content().stream()
                        .filter(b -> "text".equals(b.type()))
                        .map(AnthropicContent::text)
                        .reduce("", (a, b) -> a + b);
        ChatMessage assistantMsg = new ChatMessage("assistant", text);
        ChatChoice choice = new ChatChoice(0, assistantMsg, mapStopReason(r.stopReason()));
        int input = r.usage() == null ? 0 : r.usage().inputTokens();
        int output = r.usage() == null ? 0 : r.usage().outputTokens();
        return new ChatResponse(r.id(), requestedModel, List.of(choice),
                new Usage(input, output, input + output),
                System.currentTimeMillis() / 1000);
    }

    private static String mapStopReason(String anthropicReason) {
        if (anthropicReason == null) return null;
        return switch (anthropicReason) {
            case "end_turn" -> "stop";
            case "max_tokens" -> "length";
            case "stop_sequence" -> "stop";
            case "tool_use" -> "tool_calls";
            default -> anthropicReason;
        };
    }

    // ----- Anthropic wire shapes --------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record AnthropicRequest(
            String model,
            @JsonProperty("max_tokens") int maxTokens,
            String system,
            List<AnthropicMessage> messages,
            Double temperature,
            boolean stream
    ) { }

    /** {@code content} is a string for text, and a list of content blocks for a message with an image. */
    record AnthropicMessage(String role, Object content) { }

    record AnthropicResponse(
            String id,
            String model,
            List<AnthropicContent> content,
            @JsonProperty("stop_reason") String stopReason,
            AnthropicUsage usage
    ) { }

    record AnthropicContent(String type, String text) { }

    record AnthropicUsage(
            @JsonProperty("input_tokens") int inputTokens,
            @JsonProperty("output_tokens") int outputTokens
    ) { }
}
