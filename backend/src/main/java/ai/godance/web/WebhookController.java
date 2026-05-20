package ai.godance.web;

import java.util.Map;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.security.JwtService;
import ai.godance.security.WebhookSigner;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Manage the per-user webhook signing secret. */
@RestController
@RequestMapping("/v1/webhooks")
public class WebhookController {

    private final WebhookSigner signer;

    public WebhookController(WebhookSigner signer) {
        this.signer = signer;
    }

    /** Lazy-fetch (or create) the user's signing secret. Idempotent. */
    @GetMapping("/secret")
    public Mono<Map<String, String>> getSecret(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> Map.of(
                        "secret", signer.secretFor(userId),
                        "header", WebhookSigner.HEADER))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Rotate — invalidates the previous secret. Returns the new one (once). */
    @PostMapping("/secret/rotate")
    public Mono<Map<String, String>> rotateSecret(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> Map.of(
                        "secret", signer.rotateSecret(userId),
                        "header", WebhookSigner.HEADER))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
