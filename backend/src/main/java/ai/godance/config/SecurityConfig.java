package ai.godance.config;

import java.time.Duration;
import java.util.List;

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

import ai.godance.repository.UserRepository;
import ai.godance.security.ApiKeyAuthFilter;
import ai.godance.security.OAuthLoginFailureHandler;
import ai.godance.security.OAuthLoginNonce;
import ai.godance.security.OAuthLoginSuccessHandler;
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
        RateLimitProperties.class})
public class SecurityConfig {

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
                "Authorization", "Content-Type", "Accept",
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
     * Primary API chain — JWT bearer tokens, Godance API keys, and
     * spending-cap enforcement on the billable endpoints.
     */
    @Bean
    @Order(2)
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http,
            ApiKeyAuthFilter apiKeyAuthFilter, SpendingLimitFilter spendingLimitFilter,
            ai.godance.security.AdminAuthorizationFilter adminAuthorizationFilter) {
        return http
                // CORS is handled by the standalone CorsWebFilter bean above.
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
                        // Not /v1/auth/verify: it must run in the account's own
                        // session (see AuthService.verifyEmail).
                        // Public read-only: model catalog (model picker) and
                        // provider status board (marketing /status page).
                        .pathMatchers(HttpMethod.GET, "/v1/models", "/v1/status").permitAll()
                        // Stripe (and any future) provider webhooks — authenticated by
                        // signature, not by JWT. Handler MUST reject bad signatures.
                        .pathMatchers(HttpMethod.POST, "/v1/billing/webhooks/**").permitAll()
                        // Anything else needs a valid bearer token.
                        .anyExchange().authenticated())
                // API-key auth runs before the JWT bearer filter; on success it
                // installs a Jwt-principal so downstream controllers stay agnostic
                // to whether the caller used a JWT or an API key.
                .addFilterBefore(apiKeyAuthFilter::filter, SecurityWebFiltersOrder.AUTHENTICATION)
                // Admin allowlist guard on /v1/admin/** — fails closed. Loads the
                // caller's account and checks its current, verified email; refuses
                // API-key principals.
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
