package ai.godance.gateway.providers;

import java.util.List;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClient;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.config.OpenAIProperties;
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

@Component
public class OpenAIProvider implements ProviderAdapter {

    private static final String DONE_SENTINEL = "[DONE]";

    private final WebClient client;
    private final OpenAIProperties props;
    private final ObjectMapper json;

    public OpenAIProvider(OpenAIProperties props, ObjectMapper json) {
        this.props = props;
        this.json = json;
        this.client = WebClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + props.apiKey())
                .defaultHeader(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public Provider type() {
        return Provider.OPENAI;
    }

    @Override
    public Mono<ChatResponse> chat(ChatRequest request) {
        OpenAIChatRequest body = buildBody(request, false);
        return client.post()
                .uri("/v1/chat/completions")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(OpenAIChatResponse.class)
                .map(OpenAIProvider::toDomain)
                .timeout(props.timeout());
    }

    @Override
    public Flux<ChatChunk> chatStream(ChatRequest request) {
        OpenAIChatRequest body = buildBody(request, true);
        return client.post()
                .uri("/v1/chat/completions")
                .accept(MediaType.TEXT_EVENT_STREAM)
                .bodyValue(body)
                .retrieve()
                .bodyToFlux(new ParameterizedTypeReference<ServerSentEvent<String>>() {})
                .mapNotNull(ServerSentEvent::data)
                .takeWhile(s -> !DONE_SENTINEL.equals(s.trim()))
                .flatMap(this::parseChunk)
                .timeout(props.timeout());
    }

    private Mono<ChatChunk> parseChunk(String data) {
        try {
            OpenAIChunk c = json.readValue(data, OpenAIChunk.class);
            List<ChunkChoice> choices = c.choices() == null ? List.of() : c.choices().stream()
                    .map(ch -> new ChunkChoice(
                            ch.index(),
                            new ChunkDelta(
                                    ch.delta() == null ? null : ch.delta().role(),
                                    ch.delta() == null ? null : ch.delta().content()),
                            ch.finishReason()))
                    .toList();
            Usage usage = c.usage() == null ? null
                    : new Usage(c.usage().promptTokens(),
                                c.usage().completionTokens(),
                                c.usage().totalTokens());
            return Mono.just(new ChatChunk(c.id(), c.model(),
                    c.created() == null ? System.currentTimeMillis() / 1000 : c.created(),
                    choices, usage));
        } catch (Exception e) {
            return Mono.error(new IllegalStateException(
                    "could not parse OpenAI stream chunk: " + data, e));
        }
    }

    private static OpenAIChatRequest buildBody(ChatRequest request, boolean stream) {
        List<OpenAIMessage> messages = request.messages().stream()
                .map(m -> new OpenAIMessage(m.role(), m.content()))
                .toList();
        // stream_options.include_usage is required to get token counts in the final chunk
        OpenAIStreamOptions streamOpts = stream ? new OpenAIStreamOptions(true) : null;
        return new OpenAIChatRequest(
                request.model(),
                messages,
                request.maxTokens(),
                request.temperature(),
                stream,
                streamOpts);
    }

    private static ChatResponse toDomain(OpenAIChatResponse r) {
        List<ChatChoice> choices = r.choices() == null ? List.of() : r.choices().stream()
                .map(c -> new ChatChoice(
                        c.index(),
                        new ChatMessage(c.message().role(), c.message().content()),
                        c.finishReason()))
                .toList();
        Usage usage = r.usage() == null
                ? new Usage(0, 0, 0)
                : new Usage(r.usage().promptTokens(), r.usage().completionTokens(), r.usage().totalTokens());
        return new ChatResponse(r.id(), r.model(), choices, usage,
                r.created() == null ? System.currentTimeMillis() / 1000 : r.created());
    }

    // ----- OpenAI wire shapes -----------------------------------------

    @JsonInclude(JsonInclude.Include.NON_NULL)
    record OpenAIChatRequest(
            String model,
            List<OpenAIMessage> messages,
            @JsonProperty("max_tokens") Integer maxTokens,
            Double temperature,
            boolean stream,
            @JsonProperty("stream_options") OpenAIStreamOptions streamOptions
    ) { }

    record OpenAIStreamOptions(@JsonProperty("include_usage") boolean includeUsage) { }

    record OpenAIMessage(String role, String content) { }

    record OpenAIChatResponse(
            String id,
            String model,
            Long created,
            List<OpenAIChoice> choices,
            OpenAIUsage usage
    ) { }

    record OpenAIChoice(
            int index,
            OpenAIMessage message,
            @JsonProperty("finish_reason") String finishReason
    ) { }

    record OpenAIChunk(
            String id,
            String model,
            Long created,
            List<OpenAIChunkChoice> choices,
            OpenAIUsage usage
    ) { }

    record OpenAIChunkChoice(
            int index,
            OpenAIChunkDelta delta,
            @JsonProperty("finish_reason") String finishReason
    ) { }

    record OpenAIChunkDelta(String role, String content) { }

    record OpenAIUsage(
            @JsonProperty("prompt_tokens") int promptTokens,
            @JsonProperty("completion_tokens") int completionTokens,
            @JsonProperty("total_tokens") int totalTokens
    ) { }
}
