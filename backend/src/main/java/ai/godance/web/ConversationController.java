package ai.godance.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.security.JwtService;
import ai.godance.service.ConversationService;
import ai.godance.service.ConversationService.AppendInput;
import ai.godance.web.dto.ConversationDtos.AppendMessageRequest;
import ai.godance.web.dto.ConversationDtos.ConversationView;
import ai.godance.web.dto.ConversationDtos.CreateConversationRequest;
import ai.godance.web.dto.ConversationDtos.MessageView;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Godance-extension chat-history surface. {@code /v1/chat/completions}
 * remains stateless + OpenAI-compatible; persistence lives here so
 * pure OpenAI clients can ignore it entirely.
 */
@RestController
@RequestMapping("/v1/conversations")
public class ConversationController {

    private final ConversationService service;

    public ConversationController(ConversationService service) {
        this.service = service;
    }

    @PostMapping
    public Mono<ResponseEntity<ConversationView>> create(
            @Valid @RequestBody CreateConversationRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> service.create(userId, req.title()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(c -> ResponseEntity.status(HttpStatus.CREATED).body(ConversationView.from(c)));
    }

    @GetMapping
    public Mono<List<ConversationView>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> service.listActive(userId).stream()
                        .map(ConversationView::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<ConversationView> get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> ConversationView.from(service.requireOwned(userId, id)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> archive(@PathVariable UUID id,
                                              @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> service.archive(userId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @GetMapping("/{id}/messages")
    public Mono<List<MessageView>> messages(@PathVariable UUID id,
                                            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> service.listMessages(userId, id).stream()
                        .map(MessageView::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/{id}/messages")
    public Mono<ResponseEntity<MessageView>> appendMessage(
            @PathVariable UUID id,
            @Valid @RequestBody AppendMessageRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        AppendInput input = new AppendInput(req.role(), req.content(), req.model(),
                req.inputTokens(), req.outputTokens(), req.costCents());
        return Mono.fromCallable(() -> service.appendMessage(userId, id, input))
                .subscribeOn(Schedulers.boundedElastic())
                .map(m -> ResponseEntity.status(HttpStatus.CREATED).body(MessageView.from(m)));
    }
}
