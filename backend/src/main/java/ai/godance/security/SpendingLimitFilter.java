package ai.godance.security;

import java.util.Set;
import java.util.UUID;

import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.core.context.SecurityContext;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import ai.godance.service.SpendingLimitService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Enforces spending caps on the billable generation endpoints before the
 * request reaches its controller. Runs after authentication/authorization
 * so the principal — a {@link Jwt} for both JWT and API-key callers — is
 * resolved; a hard-cap breach short-circuits with 402.
 *
 * <p>Not a {@code WebFilter} bean by design — it is added explicitly to
 * the security chain in {@code SecurityConfig}.
 */
@Component
public class SpendingLimitFilter {

    /** Synchronous, credit-charging endpoints whose spend counts toward caps. */
    private static final Set<String> BILLABLE_PATHS = Set.of(
            "/v1/chat/completions",
            "/v1/images/generations",
            "/v1/videos/generations");

    private final SpendingLimitService spendingLimits;

    public SpendingLimitFilter(SpendingLimitService spendingLimits) {
        this.spendingLimits = spendingLimits;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        if (!isBillable(exchange)) {
            return chain.filter(exchange);
        }
        return ReactiveSecurityContextHolder.getContext()
                .map(SecurityContext::getAuthentication)
                .flatMap(this::enforce)
                .then(Mono.defer(() -> chain.filter(exchange)))
                .onErrorResume(SpendingLimitService.SpendingLimitExceededException.class,
                        ex -> ProblemResponse.write(exchange, HttpStatus.PAYMENT_REQUIRED,
                                ex.getReason()));
    }

    /**
     * Runs the cap check on a worker thread. Completes empty when there
     * is nothing to enforce (no principal, or an unrecognised one) so the
     * downstream {@code .then(...)} still proceeds; errors on a hard-cap
     * breach so {@code onErrorResume} can render the 402.
     */
    private Mono<Void> enforce(Authentication auth) {
        if (auth == null || !(auth.getPrincipal() instanceof Jwt jwt)) {
            return Mono.empty();
        }
        UUID userId;
        try {
            userId = UUID.fromString(jwt.getSubject());
        } catch (RuntimeException notAUuid) {
            return Mono.empty();
        }
        UUID apiKeyId = parseUuid(jwt.getClaimAsString("api_key_id"));
        return Mono.<Void>fromRunnable(() -> spendingLimits.enforce(userId, apiKeyId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static boolean isBillable(ServerWebExchange exchange) {
        return exchange.getRequest().getMethod() == HttpMethod.POST
                && BILLABLE_PATHS.contains(exchange.getRequest().getPath().value());
    }

    private static UUID parseUuid(String value) {
        if (value == null || value.isBlank()) return null;
        try {
            return UUID.fromString(value);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
