package ai.godance.web;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ContentPart;
import ai.godance.gateway.ChatDtos.TextPart;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.security.JwtService;
import ai.godance.service.ChatCompletionService;
import ai.godance.service.ChatImageRefusedException;
import ai.godance.service.ChatImages;
import ai.godance.service.InputService;
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
    private final ChatImages images;
    private final InputService inputs;
    private final ObjectMapper json;

    public ChatCompletionController(ChatCompletionService chat, ChatImages images, InputService inputs,
                                    ObjectMapper json) {
        this.chat = chat;
        this.images = images;
        this.inputs = inputs;
        this.json = json;
    }

    @PostMapping("/chat/completions")
    public Mono<ResponseEntity<?>> chatCompletions(
            @Valid @RequestBody ChatCompletionRequest req,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID apiKeyId = apiKeyId(jwt);

        // Everything about the request's shape and its images is settled here,
        // before the service prices or charges anything.
        return toDomain(req, userId, exchange.getRequest().getURI()).flatMap(domain -> {
            if (Boolean.TRUE.equals(req.stream())) {
                Flux<ServerSentEvent<String>> stream = chat.executeStreaming(userId, apiKeyId, domain)
                        .map(ChatCompletionChunk::from)
                        .map(this::asSse)
                        .concatWith(Mono.just(ServerSentEvent.<String>builder()
                                .data(DONE_SENTINEL).build()));
                return Mono.just(ResponseEntity.ok()
                        .contentType(MediaType.TEXT_EVENT_STREAM)
                        .<Object>body(stream));
            }
            return Mono.fromCallable(() -> chat.execute(userId, apiKeyId, domain))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(r -> ResponseEntity.ok().<Object>body(ChatCompletionResponse.from(r)));
        });
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

    /** One part of a message as the request gave it: text, or an image still to be fetched or decoded. */
    private record Part(String text, String imageUrl, String detail) {
        boolean isImage() {
            return imageUrl != null;
        }
    }

    /**
     * The request as the service takes it. A request of plain strings is
     * turned over at once and is exactly what it always was. One that
     * carries images is checked first, in this order and before anything is
     * fetched or charged: inputs switched on, no more than
     * {@link ChatImages#MAX_IMAGES} images, images only in user messages, a
     * model that accepts them. Then each image is fetched or decoded, in
     * order.
     */
    private Mono<ChatRequest> toDomain(ChatCompletionRequest req, UUID userId, URI requestUri) {
        List<List<Part>> parsed = new ArrayList<>(req.messages().size());
        int imageCount = 0;
        for (int i = 0; i < req.messages().size(); i++) {
            Message m = req.messages().get(i);
            List<Part> parts = parse(m.content(), i);
            for (Part part : parts) {
                if (!part.isImage()) continue;
                imageCount++;
                if (!"user".equalsIgnoreCase(m.role())) {
                    throw ChatImageRefusedException.invalidUrl("images are accepted in user messages only");
                }
            }
            parsed.add(parts);
        }
        if (imageCount == 0) {
            List<ChatMessage> messages = new ArrayList<>(parsed.size());
            for (int i = 0; i < parsed.size(); i++) {
                messages.add(textMessage(req.messages().get(i).role(), parsed.get(i)));
            }
            return Mono.just(request(req, messages));
        }

        // An image in chat is an input: while inputs are switched off, none is accepted.
        inputs.requireEnabled();
        if (imageCount > ChatImages.MAX_IMAGES) throw ChatImageRefusedException.tooMany(ChatImages.MAX_IMAGES);
        chat.requireAcceptsImages(req.model(), imageCount);

        URI apiBase = requestUri;
        return Flux.range(0, parsed.size())
                .concatMap(i -> Flux.fromIterable(parsed.get(i))
                        .concatMap(part -> part.isImage()
                                ? images.resolve(userId, part.imageUrl(), part.detail(), apiBase).cast(ContentPart.class)
                                : Mono.<ContentPart>just(new TextPart(part.text())))
                        .collectList()
                        .map(parts -> new ChatMessage(req.messages().get(i).role(), parts)))
                .collectList()
                .map(messages -> request(req, messages));
    }

    private static ChatRequest request(ChatCompletionRequest req, List<ChatMessage> messages) {
        return new ChatRequest(req.model(), messages, req.maxTokens(), req.temperature(), req.stream());
    }

    /** A message with no image: one string, as before, or its text parts kept apart. */
    private static ChatMessage textMessage(String role, List<Part> parts) {
        if (parts.size() == 1) return new ChatMessage(role, parts.get(0).text());
        List<ContentPart> text = new ArrayList<>(parts.size());
        for (Part part : parts) text.add(new TextPart(part.text()));
        return new ChatMessage(role, text);
    }

    /**
     * A message's {@code content} as parts. A string is one text part and
     * must not be blank, as it never could be. An array must hold at least
     * one part, each {@code text} or {@code image_url}.
     */
    private static List<Part> parse(JsonNode content, int index) {
        String where = "messages[" + index + "].content";
        if (content == null || content.isNull() || content.isMissingNode()) {
            throw badRequest(where + " must not be blank");
        }
        if (content.isString()) {
            String text = content.asString();
            if (text.isBlank()) throw badRequest(where + " must not be blank");
            return List.of(new Part(text, null, null));
        }
        if (!content.isArray()) {
            throw badRequest(where + " must be a string or an array of parts");
        }
        if (content.isEmpty()) throw badRequest(where + " must hold at least one part");
        List<Part> parts = new ArrayList<>(content.size());
        for (JsonNode part : content) {
            String type = part.isObject() && part.path("type").isString() ? part.path("type").asString() : null;
            if ("text".equals(type)) {
                if (!part.path("text").isString()) throw badRequest("a text part of " + where + " needs a text string");
                parts.add(new Part(part.path("text").asString(), null, null));
            } else if ("image_url".equals(type)) {
                JsonNode image = part.path("image_url");
                // OpenAI's form is an object with url; a bare string is accepted too, as some clients send it.
                String url = image.isString() ? image.asString()
                        : image.path("url").isString() ? image.path("url").asString() : null;
                if (url == null || url.isBlank()) {
                    throw ChatImageRefusedException.invalidUrl("an image part of " + where + " needs image_url.url");
                }
                JsonNode detail = image.path("detail");
                if (!detail.isMissingNode() && !detail.isNull() && !detail.isString()) {
                    throw ChatImageRefusedException.invalidUrl("image_url.detail must be auto, low, high or original");
                }
                parts.add(new Part(null, url, detail.isString() ? detail.asString() : null));
            } else {
                throw ChatImageRefusedException.unsupportedPart(
                        "a part of " + where + " must be of type text or image_url"
                                + (type == null ? "" : "; got " + abbreviate(type)));
            }
        }
        return parts;
    }

    private static String abbreviate(String s) {
        return s.length() <= 40 ? s : s.substring(0, 40) + "…";
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
