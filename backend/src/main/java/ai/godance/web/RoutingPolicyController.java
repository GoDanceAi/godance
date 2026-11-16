package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.routing.RoutingPolicyDocuments.PolicyBody;
import ai.godance.routing.RoutingPolicyDocuments.PolicyList;
import ai.godance.routing.RoutingPolicyDocuments.PolicyView;
import ai.godance.routing.UserRoutingPolicyService;
import ai.godance.security.JwtService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A user's own routing policies (round 4's Phase 3, design §6), with a
 * session or an API key. A policy is called by its id as a chat request's
 * {@code model}; the built-in presets are listed with them and cannot be
 * changed. Every route answers 404 {@code routing_policies_disabled} while
 * {@code godance.routing.user-policies.enabled} is off.
 */
@RestController
@RequestMapping("/v1/routing/policies")
public class RoutingPolicyController {

    private final UserRoutingPolicyService policies;

    public RoutingPolicyController(UserRoutingPolicyService policies) {
        this.policies = policies;
    }

    @GetMapping
    public Mono<PolicyList> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> policies.list(userId)).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<PolicyView> get(@PathVariable("id") String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> policies.get(userId, id)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Creates the policy (201) or replaces it (200), once it is valid. */
    @PutMapping("/{id}")
    public Mono<ResponseEntity<PolicyView>> put(@PathVariable("id") String id, @RequestBody PolicyBody body,
                                                @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> policies.put(userId, id, body))
                .subscribeOn(Schedulers.boundedElastic())
                .map(saved -> ResponseEntity.status(saved.created() ? HttpStatus.CREATED : HttpStatus.OK)
                        .body(saved.policy()));
    }

    @DeleteMapping("/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable("id") String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> policies.delete(userId, id))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
