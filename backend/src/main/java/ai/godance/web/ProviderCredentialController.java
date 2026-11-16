package ai.godance.web;

import java.util.List;
import java.util.UUID;

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
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.ProviderCredentialService;
import ai.godance.web.dto.ByokDtos.CredentialRequest;
import ai.godance.web.dto.ByokDtos.CredentialView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Bring your own key (round 14 brief 03): {@code /v1/provider-credentials}.
 * A signed-in session only: an API key can neither read, set nor remove a
 * provider key (the default rule in {@code SecurityConfig}). The key is
 * never returned; {@code last4} is all of it that is shown.
 */
@RestController
@RequestMapping("/v1/provider-credentials")
public class ProviderCredentialController {

    private final ProviderCredentialService credentials;

    public ProviderCredentialController(ProviderCredentialService credentials) {
        this.credentials = credentials;
    }

    /** Every provider a key may be stored for, and every key the caller holds. Empty while none is offered. */
    @GetMapping
    public Mono<List<CredentialView>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> credentials.list(userId)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Stores a key once the provider accepts it, or changes only the fallback setting. */
    @PutMapping("/{provider}")
    public Mono<CredentialView> put(@PathVariable String provider, @RequestBody(required = false) CredentialRequest req,
                                    @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> credentials.put(userId, provider, req, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @DeleteMapping("/{provider}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String provider, @AuthenticationPrincipal Jwt jwt,
                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromRunnable(() -> credentials.delete(userId, provider, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy (forward-headers-strategy=framework) the
        // remote address can be UNRESOLVED — getAddress() returns null.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
