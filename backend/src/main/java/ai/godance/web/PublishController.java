package ai.godance.web;

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
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.PublishService;
import ai.godance.service.RecipeRefusedException;
import ai.godance.web.dto.CommunityDtos.PublishRequest;
import ai.godance.web.dto.CommunityDtos.PublishView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Publishing one of the caller's recipes (round 14 brief 01):
 * {@code /v1/recipes/{id}/publish}. A signed-in session only. Publishing
 * answers 202 with a {@code PENDING} submission: nothing is public until a
 * moderator approves it.
 */
@RestController
@RequestMapping("/v1/recipes/{id}/publish")
public class PublishController {

    private final PublishService publishing;

    public PublishController(PublishService publishing) {
        this.publishing = publishing;
    }

    /** The recipe's latest submission, whatever became of it. */
    @GetMapping
    public Mono<PublishView> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        return Mono.fromCallable(() -> publishing.view(userId, recipeId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping
    public Mono<ResponseEntity<PublishView>> publish(@PathVariable String id, @RequestBody PublishRequest req,
                                                     @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        String ip = Requests.clientIp(exchange);
        return Mono.fromCallable(() -> publishing.publish(userId, recipeId, req, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .map(view -> ResponseEntity.status(HttpStatus.ACCEPTED).body(view));
    }

    /** Unpublishes at once: 204. */
    @DeleteMapping
    public Mono<ResponseEntity<Void>> unpublish(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                                ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID recipeId = recipeId(id);
        String ip = Requests.clientIp(exchange);
        return Mono.fromRunnable(() -> publishing.unpublish(userId, recipeId, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /** A recipe id from the path. Anything that is not an id names no recipe: the same 404. */
    private static UUID recipeId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw RecipeRefusedException.notFound();
        }
    }
}
