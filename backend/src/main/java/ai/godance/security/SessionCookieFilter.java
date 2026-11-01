package ai.godance.security;

import java.util.Optional;
import java.util.Set;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.authentication.ReactiveAuthenticationManager;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.InvalidBearerTokenException;
import org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken;
import org.springframework.security.oauth2.server.resource.authentication.JwtReactiveAuthenticationManager;
import org.springframework.stereotype.Component;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import reactor.core.publisher.Mono;

/**
 * Authenticates a request by its session cookie ({@link SessionCookies})
 * and refuses cross-site forgeries of one.
 *
 * <p>A browser attaches a cookie on its own, so a request carrying this one
 * proves nothing about who built it. {@code SameSite=Strict} keeps other
 * sites out; a sibling subdomain counts as the same site, so a state-changing
 * request must also come with an {@code Origin} on the CORS allow-list. That
 * is the same list, and the same check, as {@code CorsWebFilter}, which
 * refuses a foreign {@code Origin} earlier on {@code /v1/**}. What it adds
 * here is refusing a request with no {@code Origin} at all, which every
 * browser sends on these methods. An {@code Authorization} header is never
 * attached on its own, so it skips this filter: bearer JWTs and API keys
 * work as they always have.
 *
 * <p>A cookie that no longer authenticates (expired, revoked by a session
 * version bump, tampered with) is cleared and the request goes on anonymous.
 * Protected routes then answer 401 as usual. It can't be treated as a bad
 * bearer token, which fails the request outright: the browser would keep
 * sending it, and its owner could never reach {@code /v1/auth/login} again.
 *
 * <p>Like {@link ApiKeyAuthFilter}, not a {@code WebFilter} bean: it is added
 * explicitly to the security chain in {@code SecurityConfig}.
 */
@Component
public class SessionCookieFilter {

    private static final Set<HttpMethod> SAFE_METHODS =
            Set.of(HttpMethod.GET, HttpMethod.HEAD, HttpMethod.OPTIONS, HttpMethod.TRACE);

    private final SessionCookies cookies;
    private final ReactiveAuthenticationManager jwtSessions;
    private final CorsConfigurationSource cors;

    public SessionCookieFilter(SessionCookies cookies, ReactiveJwtDecoder jwtDecoder,
                               CorsConfigurationSource cors) {
        this.cookies = cookies;
        this.jwtSessions = new JwtReactiveAuthenticationManager(jwtDecoder);
        this.cors = cors;
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        ServerHttpRequest request = exchange.getRequest();
        String token = cookies.read(request);
        if (token == null || request.getHeaders().containsHeader(HttpHeaders.AUTHORIZATION)) {
            return chain.filter(exchange);
        }
        if (!SAFE_METHODS.contains(request.getMethod()) && !fromAllowedOrigin(exchange)) {
            return ProblemResponse.write(exchange, HttpStatus.FORBIDDEN,
                    "This request didn't come from the Godance app.");
        }
        return jwtSessions.authenticate(new BearerTokenAuthenticationToken(token))
                .map(Optional::of)
                .onErrorResume(InvalidBearerTokenException.class, e -> {
                    cookies.clear(exchange.getResponse());
                    return Mono.just(Optional.<Authentication>empty());
                })
                .flatMap(session -> session
                        .map(auth -> chain.filter(exchange)
                                .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                        .orElseGet(() -> chain.filter(exchange)));
    }

    private boolean fromAllowedOrigin(ServerWebExchange exchange) {
        String origin = exchange.getRequest().getHeaders().getOrigin();
        CorsConfiguration allowed = cors.getCorsConfiguration(exchange);
        return origin != null && allowed != null && allowed.checkOrigin(origin) != null;
    }
}
