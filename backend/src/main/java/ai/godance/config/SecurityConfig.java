package ai.godance.config;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.stream.Stream;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.SecurityWebFiltersOrder;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.authentication.ServerAuthenticationConverter;
import org.springframework.security.web.server.util.matcher.ServerWebExchangeMatchers;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.security.ApiKeyAuthFilter;
import ai.godance.security.JwtService;
import ai.godance.security.OAuthLoginFailureHandler;
import ai.godance.security.OAuthLoginNonce;
import ai.godance.security.OAuthLoginSuccessHandler;
import ai.godance.security.SessionCookieFilter;
import ai.godance.security.SessionCookies;
import ai.godance.security.SessionVersionJwtDecoder;
import ai.godance.security.SpendingLimitFilter;
import reactor.core.publisher.Mono;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({
        JwtProperties.class,
        StripeProperties.class,
        BillingProperties.class,
        NowPaymentsProperties.class,
        OpenAIProperties.class,
        AnthropicProperties.class,
        GoogleProperties.class,
        GatewayProperties.class,
        ObjectStorageProperties.class,
        BflProperties.class,
        VolcengineProperties.class,
        SecurityProperties.class,
        PricingProperties.class,
        AdminProperties.class,
        RateLimitProperties.class,
        KeyVaultProperties.class})
public class SecurityConfig {

    /**
     * Authorities a signed-in session's JWT carries ({@code scope} = the
     * user's role). Staff are customers too, so every role is here: what
     * tells the roles apart under {@code /v1/admin/**} is
     * {@link ai.godance.security.AdminAuthorizationFilter}.
     */
    private static final String[] SESSION = Arrays.stream(UserRole.values())
            .map(role -> "SCOPE_" + role.name())
            .toArray(String[]::new);
    /**
     * A child's account waiting for a parent's consent carries this instead
     * of its role ({@link JwtService#PENDING_CONSENT_SCOPE}). Admitted on
     * the two routes the waiting screen uses and nowhere else.
     */
    private static final String SESSION_PENDING_CONSENT = JwtService.PENDING_CONSENT_AUTHORITY;
    /**
     * A sign-in that still owes its second factor carries this instead of
     * its role ({@link JwtService#TWO_FACTOR_PENDING_SCOPE}). Admitted on
     * the route that takes the code and nowhere else.
     */
    private static final String SESSION_TWO_FACTOR_PENDING = JwtService.TWO_FACTOR_PENDING_AUTHORITY;

    private static String[] sessionOr(String authority) {
        return Stream.concat(Arrays.stream(SESSION), Stream.of(authority)).toArray(String[]::new);
    }

    @Bean
    PasswordEncoder passwordEncoder() {
        // Cost 12 — ~250ms per hash on commodity hardware. Tune up if/when we move to dedicated auth boxes.
        return new BCryptPasswordEncoder(12);
    }

    @Bean
    JwtEncoder jwtEncoder(JwtProperties props) {
        return new NimbusJwtEncoder(new ImmutableSecret<>(secretKey(props)));
    }

    @Bean
    ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties props, UserRepository users) {
        ReactiveJwtDecoder signed = NimbusReactiveJwtDecoder.withSecretKey(secretKey(props))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
        return new SessionVersionJwtDecoder(signed, users);
    }

    private static SecretKey secretKey(JwtProperties props) {
        return new SecretKeySpec(props.secret().getBytes(), "HmacSHA256");
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties props) {
        CorsConfiguration cfg = new CorsConfiguration();
        cfg.setAllowedOrigins(new java.util.ArrayList<>(props.corsAllowedOrigins()));
        cfg.setAllowedMethods(new java.util.ArrayList<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS")));
        cfg.setAllowedHeaders(new java.util.ArrayList<>(List.of(
                "Authorization", "Content-Type", "Accept", SessionCookies.CLIENT_HEADER,
                "X-Godance-Signature", "Stripe-Signature", "x-nowpayments-sig")));
        // Retry-After accompanies the auth endpoints' 429s; it is not CORS-safelisted.
        cfg.setExposedHeaders(new java.util.ArrayList<>(List.of("X-Godance-Signature", "Retry-After")));
        cfg.setAllowCredentials(true);
        cfg.setMaxAge(Duration.ofHours(1).getSeconds());
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        // Apply to API surface only — health and webhook receiver endpoints
        // don't need CORS (the latter are server-to-server).
        source.registerCorsConfiguration("/v1/**", cfg);
        return source;
    }

    /**
     * Standalone CORS filter. Explicit over implicit — Spring Security's
     * {@code .cors(...)} DSL has been finicky across point releases at
     * auto-discovering a {@code CorsConfigurationSource} bean.
     *
     * <p>Ordered ahead of {@link org.springframework.security.web.server.WebFilterChainProxy}
     * (which is at order ~{@code -100}) so the CORS check + preflight short-circuit
     * happen before authentication. Without this ordering, security can reject
     * cross-origin requests with 403 before CORS gets a chance to respond.
     */
    @Bean
    @org.springframework.core.annotation.Order(org.springframework.core.Ordered.HIGHEST_PRECEDENCE)
    CorsWebFilter corsWebFilter(CorsConfigurationSource source) {
        return new CorsWebFilter(source);
    }

    /**
     * Primary API chain — the web app's session cookie, JWT bearer tokens,
     * Godance API keys, and spending-cap enforcement on the billable
     * endpoints.
     */
    @Bean
    @Order(2)
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http,
            ApiKeyAuthFilter apiKeyAuthFilter, SessionCookieFilter sessionCookieFilter,
            SpendingLimitFilter spendingLimitFilter,
            ai.godance.security.AdminAuthorizationFilter adminAuthorizationFilter) {
        return http
                // CORS is handled by the standalone CorsWebFilter bean above.
                // Spring's CSRF tokens are off: SessionCookieFilter guards the
                // one credential a browser attaches on its own.
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable)
                .authorizeExchange(ex -> ex
                        // CORS preflight: let the CORS filter handle OPTIONS — the
                        // authorize chain has no /v1/** OPTIONS rule and would
                        // otherwise fall through to anyExchange().authenticated().
                        .pathMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        // Liveness / build info — public for Cloudflare DNS health checks.
                        .pathMatchers("/healthz",
                                "/actuator/health/**", "/actuator/info").permitAll()
                        // Pre-auth surface. /v1/auth/verify is deliberately not
                        // part of it: verifying needs the account's own session
                        // as well as the emailed token.
                        .pathMatchers(HttpMethod.POST, "/v1/auth/signup", "/v1/auth/login").permitAll()
                        // Password reset proves itself with the emailed token.
                        .pathMatchers(HttpMethod.POST,
                                "/v1/auth/password/forgot", "/v1/auth/password/reset").permitAll()
                        // Releasing an address from an account that never verified
                        // it: the emailed token is the proof, and the address's
                        // owner has no session on that account.
                        .pathMatchers(HttpMethod.POST,
                                "/v1/auth/email/release", "/v1/auth/email/release-link").permitAll()
                        // Parental consent: the token emailed to the parent is the
                        // proof, and the parent has no account.
                        .pathMatchers(HttpMethod.GET, "/v1/auth/parental-consent").permitAll()
                        .pathMatchers(HttpMethod.POST, "/v1/auth/parental-consent/checkout").permitAll()
                        // A child's account waiting for that consent may read its
                        // own profile (to show the waiting screen) and re-send the
                        // email to the parent. Nothing else: the rules below admit
                        // SESSION / API keys only.
                        .pathMatchers(HttpMethod.GET, "/v1/auth/me")
                                .hasAnyAuthority(sessionOr(SESSION_PENDING_CONSENT))
                        .pathMatchers(HttpMethod.POST, "/v1/auth/parental-consent/resend")
                                .hasAuthority(SESSION_PENDING_CONSENT)
                        // Any signed-in session may end itself or move into the
                        // cookie. Not an API key: it must never mint a session.
                        // Not a challenge either: it isn't a session yet.
                        .pathMatchers(HttpMethod.POST, "/v1/auth/logout", "/v1/auth/session")
                                .hasAnyAuthority(sessionOr(SESSION_PENDING_CONSENT))
                        .pathMatchers(HttpMethod.POST, "/v1/auth/2fa/verify")
                                .hasAuthority(SESSION_TWO_FACTOR_PENDING)
                        // Public read-only: model catalog (model picker) and
                        // provider status board (marketing /status page).
                        .pathMatchers(HttpMethod.GET, "/v1/models", "/v1/status").permitAll()
                        // Stripe (and any future) provider webhooks — authenticated by
                        // signature, not by JWT. Handler MUST reject bad signatures.
                        .pathMatchers(HttpMethod.POST, "/v1/billing/webhooks/**").permitAll()
                        // The model API: a signed-in session or an API key.
                        .pathMatchers(HttpMethod.POST, "/v1/chat/completions",
                                "/v1/images/generations", "/v1/videos/generations")
                                .hasAnyAuthority(sessionOr(ApiKeyAuthFilter.API_KEY_AUTHORITY))
                        .pathMatchers(HttpMethod.GET, "/v1/jobs", "/v1/jobs/*", "/v1/usage")
                                .hasAnyAuthority(sessionOr(ApiKeyAuthFilter.API_KEY_AUTHORITY))
                        // Everything else (keys, billing, webhook secret, conversations,
                        // account) needs a signed-in session. Deny by default, so a
                        // leaked key can't mint a key that outlives it, read the webhook
                        // secret or turn on auto-recharge (spec risk R-01).
                        .anyExchange().hasAnyAuthority(SESSION))
                .exceptionHandling(e -> e.accessDeniedHandler(ApiKeyAuthFilter::accessDenied))
                // API-key auth runs before the JWT bearer filter; on success it
                // installs a Jwt-principal so downstream controllers stay agnostic
                // to whether the caller used a JWT or an API key.
                .addFilterBefore(apiKeyAuthFilter::filter, SecurityWebFiltersOrder.AUTHENTICATION)
                // The session cookie the same way, and only without an
                // Authorization header, so the two filters never both act.
                .addFilterBefore(sessionCookieFilter::filter, SecurityWebFiltersOrder.AUTHENTICATION)
                // Role gate on /v1/admin/** — deny by default. Checks the caller's
                // account against the roles the matched route declares; refuses
                // API-key principals and routes that declare none.
                .addFilterAfter(adminAuthorizationFilter::filter, SecurityWebFiltersOrder.AUTHORIZATION)
                // Spending caps are checked after authorization, before the controller.
                .addFilterAfter(spendingLimitFilter::filter, SecurityWebFiltersOrder.AUTHORIZATION)
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenConverter(resourceServerBearerConverter())
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * OAuth2 social-login chain — isolated from the API chain by a path
     * matcher so it cannot affect {@code /v1/**} authentication. Handles
     * the provider redirect dance; {@link OAuthLoginSuccessHandler} mints
     * the Godance JWT once a provider has authenticated the user.
     * {@link OAuthLoginNonce} carries the SPA's login nonce from the start of
     * the round-trip to that handler. {@link OAuthLoginFailureHandler} sends
     * a sign-in that fails before then back to the SPA as well.
     */
    @Bean
    @Order(1)
    SecurityWebFilterChain oauthLoginSecurityChain(ServerHttpSecurity http,
            ReactiveClientRegistrationRepository clientRegistrations,
            OAuthLoginSuccessHandler oauthLoginSuccessHandler,
            OAuthLoginFailureHandler oauthLoginFailureHandler) {
        return http
                .securityMatcher(ServerWebExchangeMatchers.pathMatchers(
                        "/oauth2/authorization/**", "/login/oauth2/code/**"))
                .csrf(ServerHttpSecurity.CsrfSpec::disable)
                .authorizeExchange(ex -> ex.anyExchange().permitAll())
                .oauth2Login(oauth -> oauth
                        .authorizationRequestResolver(OAuthLoginNonce.resolver(clientRegistrations))
                        .authorizationRequestRepository(OAuthLoginNonce.repository())
                        .authenticationSuccessHandler(oauthLoginSuccessHandler)
                        .authenticationFailureHandler(oauthLoginFailureHandler))
                .build();
    }

    /**
     * Bearer-token converter for the JWT resource server. Accepts the JWT
     * in the {@code Authorization} header or the {@code ?access_token=}
     * query param (per RFC 6750 §2.3, for browser SSE clients that can't
     * set custom headers). Godance API-key tokens ({@code gd_live_...})
     * are skipped here — {@link ApiKeyAuthFilter} owns them — so the JWT
     * decoder never tries (and fails) to parse an API key as a JWT.
     */
    private static ServerAuthenticationConverter resourceServerBearerConverter() {
        ServerBearerTokenAuthenticationConverter delegate = new ServerBearerTokenAuthenticationConverter();
        delegate.setAllowUriQueryParameter(true);
        return exchange -> {
            String header = exchange.getRequest().getHeaders().getFirst(HttpHeaders.AUTHORIZATION);
            if (ApiKeyAuthFilter.isApiKeyToken(header)) {
                return Mono.empty();
            }
            return delegate.convert(exchange);
        };
    }
}
