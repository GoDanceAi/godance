package ai.godance.security;

import java.util.Optional;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import ai.godance.config.AdminProperties;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Operator-only gate on {@code /v1/admin/**}. Loads the caller's account
 * by the JWT {@code sub} and lets the request through only when that
 * account is active, its email is verified, and its <em>current</em>
 * email matches {@link AdminProperties#isAdmin} (case-insensitively).
 *
 * <p>The JWT {@code email} claim is deliberately not consulted. It is a
 * snapshot taken at issue time, and password signup accepts any address
 * unverified, so the claim alone would let anyone who signs up with an
 * allowlisted address before its owner does pass this gate, and would
 * keep a token valid here after its account's email changed. Reading the
 * row on every admin request costs one primary-key lookup; admin traffic
 * is a handful of operators.
 *
 * <p>API-key principals (an {@code api_key_id} claim, installed by
 * {@link ApiKeyAuthFilter}) are always refused: admin work needs an
 * interactive session, not a long-lived integration credential.
 *
 * <p>On success the account's current email is stored on the exchange
 * under {@link #ADMIN_EMAIL_ATTRIBUTE} so admin controllers attribute
 * their audit rows to it rather than to the claim.
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

    /** Exchange attribute holding the verified admin's current email. */
    public static final String ADMIN_EMAIL_ATTRIBUTE = "ai.godance.security.AdminAuthorizationFilter.adminEmail";

    private static final String ADMIN_PATH_PREFIX = "/v1/admin";

    private final AdminProperties admin;
    private final UserRepository users;

    public AdminAuthorizationFilter(AdminProperties admin, UserRepository users) {
        this.admin = admin;
        this.users = users;
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
        //     adminEmail(...) therefore always emits an Optional, and the
        //     allow/deny branch is taken on its contents.
        //   * Mono.defer around the deny path is unnecessary here because
        //     it's invoked from inside the flatMap lambda, not eagerly.
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .flatMap(this::adminEmail)
                .flatMap(email -> email.isPresent()
                        ? allow(exchange, chain, email.get())
                        : deny(exchange));
    }

    /**
     * The caller's current email when the caller is an admin, else empty.
     * The user lookup is blocking JPA, so it hops to {@code boundedElastic}.
     */
    private Mono<Optional<String>> adminEmail(Authentication auth) {
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
                        .map(User::getEmail)
                        .filter(admin::isAdmin))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static Mono<Void> allow(ServerWebExchange exchange, WebFilterChain chain, String adminEmail) {
        exchange.getAttributes().put(ADMIN_EMAIL_ATTRIBUTE, adminEmail);
        return chain.filter(exchange);
    }

    private static Mono<Void> deny(ServerWebExchange exchange) {
        return ProblemResponse.write(exchange, HttpStatus.FORBIDDEN, "admin access required");
    }
}
