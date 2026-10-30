package ai.godance.web;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.AccountClosureService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Account closure on the owner's request, run by an operator. Under
 * {@code /v1/admin/**}, so {@link ai.godance.security.AdminAuthorizationFilter}
 * admits only a signed-in session of an active, verified, allowlisted
 * account; API keys and everyone else get 403.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminAccountController {

    private final AccountClosureService closure;

    public AdminAccountController(AccountClosureService closure) {
        this.closure = closure;
    }

    /** Closes the account. Safe to repeat; a repeat retries any media that could not be deleted. */
    @PostMapping("/users/{id}/close")
    public Mono<AccountClosureService.Result> close(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID actorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> closure.close(id, actorId, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy the remote address can be unresolved; fall back to the host string.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
