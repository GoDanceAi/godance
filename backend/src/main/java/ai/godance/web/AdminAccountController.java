package ai.godance.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
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
import ai.godance.service.TwoFactorService;
import ai.godance.service.admin.AdminConsoleService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * What only an admin does to an account: closing it on the owner's
 * request, granting or revoking its role, and resetting its two-factor
 * sign-in. Under {@code /v1/admin/**}, so {@link AdminAuthorizationFilter}
 * admits only a signed-in session of an active, verified account holding a
 * role the route declares; API keys and everyone else get 403.
 */
@RestController
@RequestMapping("/v1/admin")
public class AdminAccountController {

    private final AccountClosureService closure;
    private final AdminConsoleService console;
    private final TwoFactorService twoFactor;

    public AdminAccountController(AccountClosureService closure, AdminConsoleService console,
                                  TwoFactorService twoFactor) {
        this.closure = closure;
        this.console = console;
        this.twoFactor = twoFactor;
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

    /** For an owner who can't turn 2FA off themselves; see {@link TwoFactorService#resetByOperator}. */
    @PostMapping("/users/{id}/2fa/reset")
    @AdminRoles(UserRole.ADMIN)
    public Mono<ResponseEntity<Void>> resetTwoFactor(
            @PathVariable("id") UUID id,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID actorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromRunnable(() -> twoFactor.resetByOperator(id, actorId, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
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
