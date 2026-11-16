package ai.godance.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.http.server.reactive.ServerHttpResponseDecorator;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.oauth2.server.resource.web.server.BearerTokenServerAuthenticationEntryPoint;
import org.springframework.security.web.server.ServerAuthenticationEntryPoint;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * The answer to a request that is not authenticated, on every route of the
 * API chain: no credential at all, or a bearer token that is neither a
 * Godance API key nor a current session (an OpenAI key sent by mistake, a
 * malformed or expired JWT). A {@code gd_live_} key never comes here:
 * {@link ApiKeyAuthFilter} answers for it.
 *
 * <p>Spring's resource-server entry point sets the status and the
 * {@code WWW-Authenticate} header (RFC 6750) and ends the response with no
 * body, which OpenAI's SDK turns into an exception that reads only
 * "Error code: 401". This one lets Spring set both, then writes a problem
 * document, as every other refusal is written ({@link ProblemResponse}): on
 * the routes that follow OpenAI's API it carries OpenAI's {@code error}
 * object too ({@link OpenAiError}).
 *
 * <p>The {@code detail} is a sentence of ours, chosen by what Spring found.
 * The token decoder's own words stay in the header's
 * {@code error_description}, where Spring puts them, and out of the body.
 */
public final class ProblemAuthenticationEntryPoint implements ServerAuthenticationEntryPoint {

    /** No bearer token, or a session cookie that no longer authenticates (it has been cleared). */
    static final String NO_CREDENTIAL = "This request has no API key or session. "
            + "Send a Godance API key as Authorization: Bearer gd_live_…, or sign in.";
    /** A bearer token that is not a {@code gd_live_} key and not a session Godance can decode. */
    static final String NOT_A_CREDENTIAL = "The bearer token is not a Godance API key or a current session. "
            + "Godance API keys start with gd_live_.";
    /** RFC 6750's {@code invalid_request}: the bearer token is empty, or there is more than one. */
    static final String MALFORMED_REQUEST = "The bearer token is empty or sent more than once. "
            + "Send one, in the Authorization header.";
    /** Any other refusal of the bearer token. */
    static final String REFUSED = "The bearer token was refused.";

    private final ServerAuthenticationEntryPoint spring = new BearerTokenServerAuthenticationEntryPoint();

    @Override
    public Mono<Void> commence(ServerWebExchange exchange, AuthenticationException failure) {
        // Spring's entry point completes the response once it has set the status and the
        // header; on this decorator, completing it is left to the problem written below.
        ServerHttpResponse headersOnly = new ServerHttpResponseDecorator(exchange.getResponse()) {
            @Override
            public Mono<Void> setComplete() {
                return Mono.empty();
            }
        };
        return spring.commence(exchange.mutate().response(headersOnly).build(), failure)
                .then(Mono.defer(() -> ProblemResponse.write(exchange, status(exchange), detail(failure))));
    }

    /** The status Spring chose: 401, or 400 for a malformed request. */
    private static HttpStatus status(ServerWebExchange exchange) {
        HttpStatusCode chosen = exchange.getResponse().getStatusCode();
        HttpStatus status = chosen == null ? null : HttpStatus.resolve(chosen.value());
        return status != null ? status : HttpStatus.UNAUTHORIZED;
    }

    static String detail(AuthenticationException failure) {
        if (!(failure instanceof OAuth2AuthenticationException bearer)) {
            return NO_CREDENTIAL;
        }
        return switch (bearer.getError().getErrorCode()) {
            case OAuth2ErrorCodes.INVALID_TOKEN -> NOT_A_CREDENTIAL;
            case OAuth2ErrorCodes.INVALID_REQUEST -> MALFORMED_REQUEST;
            default -> REFUSED;
        };
    }
}
