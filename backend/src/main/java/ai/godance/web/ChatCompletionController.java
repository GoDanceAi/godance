package ai.godance.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.security.JwtService;
import ai.godance.service.ChatCompletionService;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionChunk;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionRequest;
import ai.godance.web.dto.ChatCompletionDtos.ChatCompletionResponse;
import ai.godance.web.dto.ChatCompletionDtos.Message;
import jakarta.validation.Valid;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI-compatible chat completions endpoint. Same URL handles both
 * non-streaming and SSE — discriminated by the {@code stream} body
 * field, as OpenAI clients expect.
 */
@RestController
@RequestMapping("/v1")
public class ChatCompletionController {

    private static final String DONE_SENTINEL = "[DONE]";

    private final ChatCompletionService chat;
    private final ObjectMapper json;

    public ChatCompletionController(ChatCompletionService chat, ObjectMapper json) {
        this.chat = chat;
        this.json = json;
    }

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(
            @Valid @RequestBody ChatCompletionRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID apiKeyId = apiKeyId(jwt);
        ChatRequest domain = toDomain(req);

        if (Boolean.TRUE.equals(req.stream())) {
            Flux<ServerSentEvent<String>> stream = chat.executeStreaming(userId, apiKeyId, domain)
                    .map(ChatCompletionChunk::from)
                    .map(this::asSse)
                    .concatWith(Mono.just(ServerSentEvent.<String>builder()
                            .data(DONE_SENTINEL).build()));
            return Mono.just(ResponseEntity.ok()
                    .contentType(MediaType.TEXT_EVENT_STREAM)
                    .body(stream));
        }

        return Mono.fromCallable(() -> chat.execute(userId, apiKeyId, domain))
                .subscribeOn(Schedulers.boundedElastic())
                .map(r -> ResponseEntity.ok().<Object>body(ChatCompletionResponse.from(r)));
    }

    private ServerSentEvent<String> asSse(ChatCompletionChunk chunk) {
        try {
            return ServerSentEvent.<String>builder().data(json.writeValueAsString(chunk)).build();
        } catch (JacksonException e) {
            throw new IllegalStateException("could not serialize stream chunk", e);
        }
    }

    /** The API key id when the caller authenticated with an API key; null for a JWT session. */
    private static UUID apiKeyId(Jwt jwt) {
        String claim = jwt.getClaimAsString("api_key_id");
        return claim == null ? null : UUID.fromString(claim);
    }

    private static ChatRequest toDomain(ChatCompletionRequest req) {
        return new ChatRequest(
                req.model(),
                req.messages().stream().map(ChatCompletionController::toDomain).toList(),
                req.maxTokens(),
                req.temperature(),
                req.stream());
    }

    private static ChatMessage toDomain(Message m) {
        return new ChatMessage(m.role(), m.content());
    }
}
