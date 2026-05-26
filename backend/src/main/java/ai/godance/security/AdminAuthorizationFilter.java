package ai.godance.security;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import ai.godance.config.AdminProperties;
import reactor.core.publisher.Mono;

/**
 * Operator-only gate on {@code /v1/admin/**}. Reads the {@code email}
 * claim from the authenticated JWT principal and matches it
 * case-insensitively against {@link AdminProperties#isAdmin}.
 *
 * <p>Fail-closed by default — an empty allowlist (the default in dev /
 * an unconfigured prod) means every admin call returns
 * {@code 403 application/problem+json}, never a silent allow.
 *
 * <p>Wired into the security chain in {@link ai.godance.config.SecurityConfig}
 * via {@code addFilterAfter(adminAuthorizationFilter::filter,
 * SecurityWebFiltersOrder.AUTHORIZATION)} so the JWT principal is
 * already populated when we look at it. Unauthenticated requests are
 * stopped earlier by Spring's authorization filter with 401, so this
 * filter only needs to distinguish admin vs. non-admin among
 * already-authenticated callers.
 */
@Component
public class AdminAuthorizationFilter {

    private static final String ADMIN_PATH_PREFIX = "/v1/admin";

    private final AdminProperties admin;

    public AdminAuthorizationFilter(AdminProperties admin) {
        this.admin = admin;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!exchange.getRequest().getURI().getPath().startsWith(ADMIN_PATH_PREFIX)) {
            return chain.filter(exchange);
        }
        // Notes on the reactive shape:
        //   * No switchIfEmpty — chain.filter(exchange) returns Mono<Void>
        //     (always empty), so any switchIfEmpty downstream would fire
        //     AFTER the controller wrote a 200 response and try to mutate
        //     the already-committed headers (ReadOnlyHttpHeaders UOE).
        //   * Mono.defer around the deny path is unnecessary here because
        //     it's invoked from inside the flatMap lambda, not eagerly.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .flatMap(auth -> isAdmin(auth) ? chain.filter(exchange) : deny(exchange));
    }

    private boolean isAdmin(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) return false;
        return admin.isAdmin(jwt.getClaimAsString("email"));
    }

    private static Mono<Void> deny(ServerWebExchange exchange) {
        return ProblemResponse.write(exchange, HttpStatus.FORBIDDEN, "admin access required");
    }
}
