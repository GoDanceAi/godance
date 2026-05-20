package ai.godance.config;

import java.time.Duration;
import java.util.List;

import javax.crypto.SecretKey;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.jose.jws.MacAlgorithm;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.oauth2.server.resource.web.server.authentication.ServerBearerTokenAuthenticationConverter;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.reactive.CorsConfigurationSource;
import org.springframework.web.cors.reactive.CorsWebFilter;
import org.springframework.web.cors.reactive.UrlBasedCorsConfigurationSource;

import com.nimbusds.jose.jwk.source.ImmutableSecret;

@Configuration
@EnableWebFluxSecurity
@EnableConfigurationProperties({
        JwtProperties.class,
        StripeProperties.class,
        BillingProperties.class,
        CoinbaseProperties.class,
        OpenAIProperties.class,
        AnthropicProperties.class,
        GoogleProperties.class,
        GatewayProperties.class,
        ObjectStorageProperties.class,
        BflProperties.class,
        VolcengineProperties.class,
        SecurityProperties.class})
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
    ReactiveJwtDecoder reactiveJwtDecoder(JwtProperties props) {
        return NimbusReactiveJwtDecoder.withSecretKey(secretKey(props))
                .macAlgorithm(MacAlgorithm.HS256)
                .build();
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
                "X-Godance-Signature", "Stripe-Signature", "X-CC-Webhook-Signature")));
        cfg.setExposedHeaders(new java.util.ArrayList<>(List.of("X-Godance-Signature")));
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

    @Bean
    SecurityWebFilterChain securityFilterChain(ServerHttpSecurity http) {
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
                        // Pre-auth surface.
                        .pathMatchers(HttpMethod.POST, "/v1/auth/signup", "/v1/auth/login").permitAll()
                        .pathMatchers(HttpMethod.GET, "/v1/auth/verify").permitAll()
                        // Public read-only: model catalog (model picker) and
                        // provider status board (marketing /status page).
                        .pathMatchers(HttpMethod.GET, "/v1/models", "/v1/status").permitAll()
                        // Stripe (and any future) provider webhooks — authenticated by
                        // signature, not by JWT. Handler MUST reject bad signatures.
                        .pathMatchers(HttpMethod.POST, "/v1/billing/webhooks/**").permitAll()
                        // Anything else needs a valid bearer token.
                        .anyExchange().authenticated())
                .oauth2ResourceServer(oauth -> oauth
                        .bearerTokenConverter(bearerWithQueryParam())
                        .jwt(Customizer.withDefaults()))
                .build();
    }

    /**
     * Accept the JWT either in the {@code Authorization} header (default) OR
     * in the {@code ?access_token=...} query param (per RFC 6750 §2.3).
     * The query-param path exists so browser SSE clients can authenticate —
     * the {@code EventSource} API can't set custom headers.
     */
    private static ServerBearerTokenAuthenticationConverter bearerWithQueryParam() {
        ServerBearerTokenAuthenticationConverter conv = new ServerBearerTokenAuthenticationConverter();
        conv.setAllowUriQueryParameter(true);
        return conv;
    }
}
