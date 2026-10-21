package ai.godance.security;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

import org.springframework.http.HttpStatus;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken;
import org.springframework.security.oauth2.core.user.OAuth2User;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationSuccessHandler;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.config.OAuthProperties;
import ai.godance.domain.OAuthProvider;
import ai.godance.service.OAuthLoginService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Spring Security {@code oauth2Login()} success handler. Once the
 * provider redirect dance has authenticated the user, this translates
 * the provider identity into a Godance JWT (via {@link OAuthLoginService})
 * and bounces the browser back to the SPA with the token in the URL
 * fragment — the same shape a native SPA OAuth callback expects. A
 * refused sign-in comes back the same way with {@code #error=<reason>}.
 * Either carries {@code &login_nonce=} when the SPA started the sign-in
 * with one (see {@link OAuthLoginNonce}).
 */
@Component
public class OAuthLoginSuccessHandler implements ServerAuthenticationSuccessHandler {

    private final OAuthLoginService oauthLogin;
    private final OAuthProperties props;

    public OAuthLoginSuccessHandler(OAuthLoginService oauthLogin, OAuthProperties props) {
        this.oauthLogin = oauthLogin;
        this.props = props;
    }

    @Override
    public Mono<Void> onAuthenticationSuccess(WebFilterExchange webFilterExchange,
                                              Authentication authentication) {
        OAuth2AuthenticationToken token = (OAuth2AuthenticationToken) authentication;
        OAuthProvider provider = OAuthProvider.fromRegistrationId(
                token.getAuthorizedClientRegistrationId());
        OAuth2User principal = token.getPrincipal();

        String subject = principal.getName();          // id token `sub` / provider user id
        String email = principal.getAttribute("email");
        boolean emailVerified = isEmailVerified(principal);
        String fullName = resolveName(principal);

        ServerWebExchange exchange = webFilterExchange.getExchange();
        return Mono.fromCallable(() -> oauthLogin.completeLogin(
                        provider, subject, email, emailVerified, fullName))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(auth -> redirectWithToken(exchange, auth))
                // A refused sign-in (unverified email, disabled account) goes
                // back to the SPA, which shows the reason on the login page.
                .onErrorResume(ResponseStatusException.class,
                        e -> e.getStatusCode().is4xxClientError()
                                ? redirectWithError(exchange, e.getReason())
                                : Mono.error(e));
    }

    private Mono<Void> redirectWithToken(ServerWebExchange exchange, AuthResponse auth) {
        return redirect(exchange, props.postLoginRedirectUri()
                + "#access_token=" + enc(auth.accessToken())
                + "&token_type=" + enc(auth.tokenType())
                + "&expires_at=" + enc(auth.expiresAt().toString())
                + nonceParam(exchange));
    }

    private Mono<Void> redirectWithError(ServerWebExchange exchange, String reason) {
        return redirect(exchange, props.postLoginRedirectUri()
                + "#error=" + enc(reason != null ? reason : "Sign-in failed")
                + nonceParam(exchange));
    }

    /** Echo of the SPA's login nonce, or nothing when it sent none. */
    private static String nonceParam(ServerWebExchange exchange) {
        String nonce = OAuthLoginNonce.from(exchange);
        return nonce == null ? "" : "&" + OAuthLoginNonce.PARAM + "=" + enc(nonce);
    }

    private static Mono<Void> redirect(ServerWebExchange exchange, String target) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(target));
        return response.setComplete();
    }

    /**
     * The OIDC {@code email_verified} claim. Google sends a boolean; Apple
     * may send a boolean or the string {@code "true"}. Anything else,
     * including a missing claim (X returns no email at all), counts as
     * unverified.
     */
    static boolean isEmailVerified(OAuth2User principal) {
        Object claim = principal.getAttribute("email_verified");
        if (claim instanceof Boolean verified) return verified;
        return claim instanceof String s && "true".equalsIgnoreCase(s.strip());
    }

    private static String resolveName(OAuth2User principal) {
        String name = principal.getAttribute("name");
        if (name != null && !name.isBlank()) return name;
        String given = principal.getAttribute("given_name");
        String family = principal.getAttribute("family_name");
        String combined = ((given == null ? "" : given) + " "
                + (family == null ? "" : family)).trim();
        return combined.isEmpty() ? null : combined;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
