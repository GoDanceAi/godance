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
 * fragment — the same shape a native SPA OAuth callback expects.
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
        String fullName = resolveName(principal);

        ServerWebExchange exchange = webFilterExchange.getExchange();
        return Mono.fromCallable(() -> oauthLogin.completeLogin(provider, subject, email, fullName))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(auth -> redirectWithToken(exchange, auth));
    }

    private Mono<Void> redirectWithToken(ServerWebExchange exchange, AuthResponse auth) {
        String target = props.postLoginRedirectUri()
                + "#access_token=" + enc(auth.accessToken())
                + "&token_type=" + enc(auth.tokenType())
                + "&expires_at=" + enc(auth.expiresAt().toString());
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(HttpStatus.FOUND);
        response.getHeaders().setLocation(URI.create(target));
        return response.setComplete();
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
