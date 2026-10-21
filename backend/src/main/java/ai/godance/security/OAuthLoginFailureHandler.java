package ai.godance.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.core.OAuth2ErrorCodes;
import org.springframework.security.web.server.WebFilterExchange;
import org.springframework.security.web.server.authentication.ServerAuthenticationFailureHandler;
import org.springframework.stereotype.Component;

import ai.godance.config.OAuthProperties;
import reactor.core.publisher.Mono;

/**
 * Spring Security {@code oauth2Login()} failure handler. Without one,
 * Spring sends a failed sign-in to {@code /login?error} on the API host,
 * which is not a page: the user cancelling at the provider, a callback
 * whose {@code state} matches no saved request, or a failed token or
 * user-info call. This sends the browser back to the SPA callback with
 * {@code #error=<reason>} instead, the same way
 * {@link OAuthLoginSuccessHandler} reports a refused sign-in, with the
 * login nonce echoed when the saved request carried one.
 *
 * <p>The reason is fixed text, never the provider's {@code error_description}.
 */
@Component
public class OAuthLoginFailureHandler implements ServerAuthenticationFailureHandler {

    private static final Logger log = LoggerFactory.getLogger(OAuthLoginFailureHandler.class);

    static final String CANCELLED = "Sign-in was cancelled.";
    static final String FAILED = "Sign-in failed. Please try again.";

    private final OAuthProperties props;

    public OAuthLoginFailureHandler(OAuthProperties props) {
        this.props = props;
    }

    @Override
    public Mono<Void> onAuthenticationFailure(WebFilterExchange webFilterExchange,
                                              AuthenticationException exception) {
        String errorCode = exception instanceof OAuth2AuthenticationException oauth
                ? oauth.getError().getErrorCode() : null;
        String reason;
        if (OAuth2ErrorCodes.ACCESS_DENIED.equals(errorCode)) {
            log.info("OAuth sign-in cancelled at the provider");
            reason = CANCELLED;
        } else {
            log.warn("OAuth sign-in failed: {}", exception.getMessage());
            reason = FAILED;
        }
        return OAuthLoginSuccessHandler.redirectWithError(
                webFilterExchange.getExchange(), props.postLoginRedirectUri(), reason);
    }
}
