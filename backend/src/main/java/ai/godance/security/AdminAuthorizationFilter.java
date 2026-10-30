package ai.godance.security;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.reactive.HandlerMapping;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Role gate on {@code /v1/admin/**}, deny by default. A request passes
 * only when the route it resolves to declares {@link AdminRoles} and the
 * caller's account is active, has a verified email, and holds one of the
 * declared roles. A path with no route, or a route with no declaration,
 * is refused for everyone, including admins.
 *
 * <p>The role comes from the account row on every request, never from a
 * JWT claim: the {@code scope} claim is a snapshot taken at issue time.
 * Reading the row costs one primary-key lookup; admin traffic is a
 * handful of staff.
 *
 * <p>API-key principals (an {@code api_key_id} claim, installed by
 * {@link ApiKeyAuthFilter}) are always refused: admin work needs an
 * interactive session, not a long-lived integration credential.
 *
 * <p>On success the account's current email and role are stored on the
 * exchange under {@link #ADMIN_EMAIL_ATTRIBUTE} and
 * {@link #ADMIN_ROLE_ATTRIBUTE}, so admin controllers attribute their
 * audit rows to the account rather than to a claim.
 *
 * <p>Wired into the security chain in {@link ai.godance.config.SecurityConfig}
 * via {@code addFilterAfter(adminAuthorizationFilter::filter,
 * SecurityWebFiltersOrder.AUTHORIZATION)} so the JWT principal is
 * already populated when we look at it. Unauthenticated requests are
 * stopped earlier by Spring's authorization filter with 401, so this
 * filter only needs to tell the roles apart among already-authenticated
 * callers.
 */
@Component
public class AdminAuthorizationFilter {

    /** Exchange attribute holding the caller's current email. */
    public static final String ADMIN_EMAIL_ATTRIBUTE = "ai.godance.security.AdminAuthorizationFilter.adminEmail";
    /** Exchange attribute holding the caller's current {@link UserRole}. */
    public static final String ADMIN_ROLE_ATTRIBUTE = "ai.godance.security.AdminAuthorizationFilter.adminRole";

    private static final String ADMIN_PATH_PREFIX = "/v1/admin";

    private final HandlerMapping routes;
    private final UserRepository users;

    public AdminAuthorizationFilter(@Qualifier("requestMappingHandlerMapping") HandlerMapping routes,
                                    UserRepository users) {
        this.routes = routes;
        this.users = users;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getURI().getPath().startsWith(ADMIN_PATH_PREFIX)) {
            return chain.filter(exchange);
        }
        // Notes on the reactive shape:
        //   * No switchIfEmpty after the chain — chain.filter(exchange)
        //     returns Mono<Void> (always empty), so a switchIfEmpty
        //     downstream would fire AFTER the controller wrote a 200
        //     response and try to mutate the already-committed headers
        //     (ReadOnlyHttpHeaders UOE). declaredRoles(...) and the caller
        //     lookup therefore always emit, and the allow/deny branch is
        //     taken on the Optional.
        //   * The defaultIfEmpty is ahead of the chain, so it is safe. It
        //     covers a request with no security context (an OPTIONS that
        //     isn't a preflight), which would otherwise end here as an
        //     empty 200.
        return declaredRoles(exchange)
                .flatMap(allowed -> ReactiveSecurityContextHolder.getContext()
                        .map(SecurityContext::getAuthentication)
                        .flatMap(auth -> caller(auth, allowed))
                        .defaultIfEmpty(Optional.empty()))
                .flatMap(caller -> caller.isPresent()
                        ? allow(exchange, chain, caller.get())
                        : deny(exchange));
    }

    /**
     * The roles the matched route declares. Empty, so nobody passes, when
     * nothing matches, when the path matches but the method doesn't, or
     * when the route has no {@link AdminRoles}.
     */
    private Mono<List<UserRole>> declaredRoles(ServerWebExchange exchange) {
        return routes.getHandler(exchange)
                .ofType(HandlerMethod.class)
                .mapNotNull(route -> route.getMethodAnnotation(AdminRoles.class))
                .map(declared -> List.of(declared.value()))
                .onErrorResume(ResponseStatusException.class, noRoute -> Mono.empty())
                .defaultIfEmpty(List.of());
    }

    /**
     * The caller's account when it may use a route open to {@code allowed},
     * else empty. The user lookup is blocking JPA, so it hops to
     * {@code boundedElastic}.
     */
    private Mono<Optional<User>> caller(Authentication auth, List<UserRole> allowed) {
        if (allowed.isEmpty()) return Mono.just(Optional.empty());
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return Mono.just(Optional.empty());
        if (jwt.hasClaim("api_key_id")) return Mono.just(Optional.empty());
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException notAUuid) {
            return Mono.just(Optional.empty());
        }
        return Mono.fromCallable(() -> users.findById(userId)
                        .filter(User::isActive)
                        .filter(User::isEmailVerified)
                        .filter(u -> allowed.contains(u.getRole())))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<Void> allow(ServerWebExchange exchange, WebFilterChain chain, User caller) {
        exchange.getAttributes().put(ADMIN_EMAIL_ATTRIBUTE, caller.getEmail());
        exchange.getAttributes().put(ADMIN_ROLE_ATTRIBUTE, caller.getRole());
        return chain.filter(exchange);
    }

    private static Mono<Void> deny(ServerWebExchange exchange) {
        return ProblemResponse.write(exchange, HttpStatus.FORBIDDEN, "admin access required");
    }
}
