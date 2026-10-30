package ai.godance.web;

import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.domain.UserRole;
import ai.godance.security.AdminAuthorizationFilter;
import ai.godance.security.AdminRoles;
import ai.godance.security.JwtService;
import ai.godance.service.AccountClosureService;
import ai.godance.service.admin.AdminConsoleService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * What only an admin does to an account: closing it on the owner's
 * request, and granting or revoking its role. Under {@code /v1/admin/**},
 * so {@link AdminAuthorizationFilter} admits only a signed-in session of
 * an active, verified account holding a role the route declares; API keys
 * and everyone else get 403.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminAccountController {

    private final AccountClosureService closure;
    private final AdminConsoleService console;

    public AdminAccountController(AccountClosureService closure, AdminConsoleService console) {
        this.closure = closure;
        this.console = console;
    }

    /** Closes the account. Safe to repeat; a repeat retries any media that could not be deleted. */
    @PostMapping("/users/{id}/close")
    @AdminRoles(UserRole.ADMIN)
    public Mono<AccountClosureService.Result> close(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID actorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> closure.close(id, actorId, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Grants the role, or revokes the account's role with {@code USER}. Ends the account's sessions. */
    @PutMapping("/users/{id}/role")
    @AdminRoles(UserRole.ADMIN)
    public Mono<AdminConsoleService.UserView> changeRole(
            @PathVariable("id") UUID id,
            @RequestBody RoleChangeRequest body,
            @AuthenticationPrincipal Jwt jwt,
            @RequestAttribute(AdminAuthorizationFilter.ADMIN_EMAIL_ATTRIBUTE) String adminEmail,
            ServerWebExchange exchange) {
        UUID actorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> console.changeRole(id, body.role(), actorId, adminEmail, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy the remote address can be unresolved; fall back to the host string.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }

    public record RoleChangeRequest(UserRole role) { }
}
