package ai.godance.security;

import java.util.regex.Pattern;

import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.server.DefaultServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.client.web.server.ServerOAuth2AuthorizationRequestResolver;
import org.springframework.security.oauth2.client.web.server.WebSessionOAuth2ServerAuthorizationRequestRepository;
import org.springframework.security.oauth2.core.endpoint.OAuth2AuthorizationRequest;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Carries the SPA's {@code login_nonce} through the OAuth2 round-trip so
 * {@link OAuthLoginSuccessHandler} can echo it back in the callback
 * fragment. The SPA keeps the nonce in sessionStorage and accepts a token
 * or error only when the echo matches, so a link to
 * {@code /oauth/callback#access_token=…} or {@code #error=…} that someone
 * else wrote is refused.
 *
 * <p>The nonce rides on the {@link OAuth2AuthorizationRequest} itself: the
 * {@link #resolver resolver} copies it from
 * {@code /oauth2/authorization/{id}?login_nonce=} into the request's
 * attributes, Spring stores the request in the session bound to
 * {@code state} as usual, and when the code callback removes it the
 * {@link #repository repository} moves the nonce onto the exchange for the
 * success handler. The nonce never goes to the provider.
 *
 * <p>The backend only echoes; the SPA enforces. A sign-in started without
 * a nonce still completes and simply comes back without one.
 */
public final class OAuthLoginNonce {

    /** Query parameter on the authorization request and key in the callback fragment. */
    public static final String PARAM = "login_nonce";

    /** Key on the callback exchange; package-private so handler tests can stand in for the repository. */
    static final String EXCHANGE_ATTRIBUTE = OAuthLoginNonce.class.getName();

    /** The SPA sends 32 hex chars; anything outside this shape is dropped, not echoed. */
    private static final Pattern FORMAT = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    private OAuthLoginNonce() { }

    /** The default resolver, plus the nonce copied into the request's attributes. */
    public static ServerOAuth2AuthorizationRequestResolver resolver(
            ReactiveClientRegistrationRepository clientRegistrations) {
        DefaultServerOAuth2AuthorizationRequestResolver delegate =
                new DefaultServerOAuth2AuthorizationRequestResolver(clientRegistrations);
        return new ServerOAuth2AuthorizationRequestResolver() {
            @Override
            public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange) {
                return delegate.resolve(exchange).map(request -> withNonce(request, exchange));
            }

            @Override
            public Mono<OAuth2AuthorizationRequest> resolve(ServerWebExchange exchange,
                                                            String clientRegistrationId) {
                return delegate.resolve(exchange, clientRegistrationId)
                        .map(request -> withNonce(request, exchange));
            }
        };
    }

    /** The default session-backed repository, plus the nonce handed to the exchange on removal. */
    public static ServerAuthorizationRequestRepository<OAuth2AuthorizationRequest> repository() {
        WebSessionOAuth2ServerAuthorizationRequestRepository delegate =
                new WebSessionOAuth2ServerAuthorizationRequestRepository();
        return new ServerAuthorizationRequestRepository<>() {
            @Override
            public Mono<OAuth2AuthorizationRequest> loadAuthorizationRequest(ServerWebExchange exchange) {
                return delegate.loadAuthorizationRequest(exchange);
            }

            @Override
            public Mono<Void> saveAuthorizationRequest(OAuth2AuthorizationRequest request,
                                                       ServerWebExchange exchange) {
                return delegate.saveAuthorizationRequest(request, exchange);
            }

            @Override
            public Mono<OAuth2AuthorizationRequest> removeAuthorizationRequest(ServerWebExchange exchange) {
                return delegate.removeAuthorizationRequest(exchange).doOnNext(request -> {
                    String nonce = request.getAttribute(PARAM);
                    if (nonce != null) {
                        exchange.getAttributes().put(EXCHANGE_ATTRIBUTE, nonce);
                    }
                });
            }
        };
    }

    /** The nonce the SPA started this sign-in with, or {@code null} if it sent none. */
    public static String from(ServerWebExchange exchange) {
        return exchange.getAttribute(EXCHANGE_ATTRIBUTE);
    }

    private static OAuth2AuthorizationRequest withNonce(OAuth2AuthorizationRequest request,
                                                        ServerWebExchange exchange) {
        String nonce = exchange.getRequest().getQueryParams().getFirst(PARAM);
        if (nonce == null || !FORMAT.matcher(nonce).matches()) {
            return request;
        }
        return OAuth2AuthorizationRequest.from(request)
                // from() leaves the URI out, and rebuilding it could differ from
                // what the default resolver produced; keep it byte-for-byte.
                .authorizationRequestUri(request.getAuthorizationRequestUri())
                .attributes(attributes -> attributes.put(PARAM, nonce))
                .build();
    }
}
