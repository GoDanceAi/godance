package ai.godance.security;

import java.net.InetAddress;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilterChain;

import ai.godance.domain.ApiKey;
import ai.godance.repository.ApiKeyRepository;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Authenticates requests that present a Godance API key
 * ({@code Authorization: Bearer gd_live_...}) instead of a JWT.
 *
 * <p>The key is looked up by SHA-256 hash, checked for active/expiry
 * state, and — if the key carries an {@code ip_allowlist} — the client
 * IP is matched against the CIDR list (off-list ⇒ 403). On success the
 * filter installs a {@link JwtAuthenticationToken} whose principal is a
 * {@link Jwt} carrying the user id as {@code sub}, so every existing
 * controller ({@code @AuthenticationPrincipal Jwt}) keeps working
 * unchanged for both auth modes.
 *
 * <p>Not a {@code WebFilter} bean by design — it is added explicitly to
 * the security chain in {@code SecurityConfig}; auto-registering it as a
 * global {@code WebFilter} would run it twice.
 */
@Component
public class ApiKeyAuthFilter {

    private static final Logger log = LoggerFactory.getLogger(ApiKeyAuthFilter.class);

    /** Claim on the installed principal naming the key; absent on a signed-in session's JWT. */
    public static final String API_KEY_ID_CLAIM = "api_key_id";

    /**
     * The only authority an API-key principal holds. A signed-in session
     * carries {@code SCOPE_USER} or {@code SCOPE_ADMIN} instead, and
     * {@code SecurityConfig} admits this one on the model API alone.
     */
    public static final String API_KEY_AUTHORITY = "SCOPE_API_KEY";

    private static final String API_KEY_PREFIX = "gd_live_";
    private static final String BEARER = "Bearer ";

    private final ApiKeyRepository apiKeys;
    private final ApiKeyMinter minter;

    public ApiKeyAuthFilter(ApiKeyRepository apiKeys, ApiKeyMinter minter) {
        this.apiKeys = apiKeys;
        this.minter = minter;
    }

    /** True when the bearer token is a Godance API key (vs. a JWT). */
    public static boolean isApiKeyToken(String authorizationHeader) {
        String raw = bearerToken(authorizationHeader);
        return raw != null && raw.startsWith(API_KEY_PREFIX);
    }

    /**
     * 403 for a caller the authorization rules refuse, as problem+json. An
     * API key reaches the model API only, so a key on any other route is
     * told to sign in instead.
     */
    public static Mono<Void> accessDenied(ServerWebExchange exchange, AccessDeniedException denied) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String detail = isApiKeyToken(header)
                ? "API keys can call the model API only; sign in to manage the account."
                : "Access denied.";
        return ProblemResponse.write(exchange, HttpStatus.FORBIDDEN, detail);
    }

    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
        String raw = bearerToken(header);
        if (raw == null || !raw.startsWith(API_KEY_PREFIX)) {
            return chain.filter(exchange);   // not an API key — JWT resource server handles it
        }
        String clientIp = clientIp(exchange.getRequest());
        return Mono.fromCallable(() -> authenticate(raw, clientIp))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(auth -> chain.filter(exchange)
                        .contextWrite(ReactiveSecurityContextHolder.withAuthentication(auth)))
                .onErrorResume(ApiKeyAuthException.class,
                        ex -> ProblemResponse.write(exchange, ex.status, ex.getMessage()));
    }

    private JwtAuthenticationToken authenticate(String rawKey, String clientIp) {
        ApiKey key = apiKeys.findByKeyHash(minter.hash(rawKey))
                .orElseThrow(() -> new ApiKeyAuthException(HttpStatus.UNAUTHORIZED, "invalid API key"));
        if (!key.isActive()) {
            throw new ApiKeyAuthException(HttpStatus.UNAUTHORIZED, "API key is inactive");
        }
        if (key.getExpiresAt() != null && key.getExpiresAt().isBefore(Instant.now())) {
            throw new ApiKeyAuthException(HttpStatus.UNAUTHORIZED, "API key has expired");
        }
        enforceIpAllowlist(key, clientIp);
        key.setLastUsedAt(Instant.now());
        apiKeys.save(key);
        return toAuthentication(key);
    }

    private static void enforceIpAllowlist(ApiKey key, String clientIp) {
        List<String> allowlist = key.getIpAllowlist();
        if (allowlist == null || allowlist.isEmpty()) {
            return;   // no allowlist configured — any client IP is permitted
        }
        if (clientIp == null || allowlist.stream().noneMatch(cidr -> cidrMatches(cidr, clientIp))) {
            throw new ApiKeyAuthException(HttpStatus.FORBIDDEN,
                    "client IP not permitted for this API key");
        }
    }

    /**
     * True when {@code ip} falls inside {@code cidr}. {@code cidr} may be a
     * CIDR block ({@code 203.0.113.0/24}) or a bare address (treated as a
     * /32 or /128). Handles IPv4 and IPv6; a malformed entry never matches.
     */
    static boolean cidrMatches(String cidr, String ip) {
        try {
            String[] parts = cidr.trim().split("/", 2);
            byte[] network = InetAddress.getByName(parts[0]).getAddress();
            byte[] candidate = InetAddress.getByName(ip.trim()).getAddress();
            if (network.length != candidate.length) {
                return false;   // IPv4 vs IPv6 — never match across families
            }
            int prefixBits = parts.length == 2
                    ? Integer.parseInt(parts[1].trim())
                    : network.length * 8;
            if (prefixBits < 0 || prefixBits > network.length * 8) {
                return false;
            }
            int wholeBytes = prefixBits / 8;
            for (int i = 0; i < wholeBytes; i++) {
                if (network[i] != candidate[i]) return false;
            }
            int remainderBits = prefixBits % 8;
            if (remainderBits > 0) {
                int mask = (0xFF << (8 - remainderBits)) & 0xFF;
                return (network[wholeBytes] & mask) == (candidate[wholeBytes] & mask);
            }
            return true;
        } catch (Exception malformed) {
            log.warn("Ignoring malformed CIDR '{}' in API-key allowlist", cidr);
            return false;
        }
    }

    private static JwtAuthenticationToken toAuthentication(ApiKey key) {
        Instant now = Instant.now();
        Jwt principal = Jwt.withTokenValue("api-key")
                .header("alg", "none")
                .issuedAt(now)
                .expiresAt(now.plusSeconds(60))
                .subject(key.getUserId().toString())
                .claim("scope", "API_KEY")
                .claim(API_KEY_ID_CLAIM, key.getId().toString())
                .build();
        return new JwtAuthenticationToken(principal,
                List.of(new SimpleGrantedAuthority(API_KEY_AUTHORITY)),
                key.getUserId().toString());
    }

    private static String bearerToken(String header) {
        if (header == null || !header.regionMatches(true, 0, BEARER, 0, BEARER.length())) {
            return null;
        }
        return header.substring(BEARER.length()).trim();
    }

    /**
     * Null-safe client-IP resolution. Prefers the first hop of
     * {@code X-Forwarded-For} (set by Caddy in front of the app); falls
     * back to the socket address, guarding the null {@code getAddress()}
     * case behind a reverse proxy.
     */
    static String clientIp(ServerHttpRequest request) {
        String forwarded = request.getHeaders().getFirst("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            return forwarded.split(",")[0].trim();
        }
        var remote = request.getRemoteAddress();
        if (remote == null) return null;
        var address = remote.getAddress();
        return address != null ? address.getHostAddress() : remote.getHostString();
    }

    /** Internal control-flow exception carrying the HTTP status to surface. */
    private static final class ApiKeyAuthException extends RuntimeException {
        private final HttpStatus status;

        ApiKeyAuthException(HttpStatus status, String message) {
            super(message);
            this.status = status;
        }
    }
}
