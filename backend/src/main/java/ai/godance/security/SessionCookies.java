package ai.godance.security;

import java.time.Duration;
import java.time.Instant;

import org.springframework.http.HttpCookie;
import org.springframework.http.ResponseCookie;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import ai.godance.config.SecurityProperties;

/**
 * The web app's session: the Godance JWT in an {@code HttpOnly} cookie, so
 * page script (and so any XSS in the app) can't read it. API keys stay
 * bearer tokens and never touch this.
 *
 * <p>The cookie has no {@code Domain}, so it belongs to the API's host
 * alone, and {@code SameSite=Strict}: the app and the API are different
 * origins on one site, so the app's {@code fetch} calls still carry it and
 * no other site's do. {@link SessionCookieFilter} reads it on the way in.
 *
 * <p>{@link #CLIENT_HEADER} is how the app says it uses the cookie. A client
 * that doesn't say so still gets the token in the response, for the app
 * bundles open in tabs when this shipped and for scripts.
 */
@Component
public class SessionCookies {

    /** Request header naming the client's session transport; see {@link #COOKIE_CLIENT}. */
    public static final String CLIENT_HEADER = "X-Godance-Session";
    /** Its value for a client that keeps its session in the cookie alone. */
    public static final String COOKIE_CLIENT = "cookie";

    /** With {@code __Host-}, browsers refuse the cookie unless it is Secure, on Path=/ and host-only. */
    private static final String SECURE_NAME = "__Host-godance_session";
    /** Local dev runs over HTTP, where that prefix can't be met. */
    private static final String LOCAL_NAME = "godance_session";

    private final boolean secure;
    private final String name;

    public SessionCookies(SecurityProperties props) {
        this.secure = props.sessionCookieSecure();
        this.name = secure ? SECURE_NAME : LOCAL_NAME;
    }

    public String name() {
        return name;
    }

    /** The token in the request's cookie, or null when it has none. */
    public String read(ServerHttpRequest request) {
        HttpCookie cookie = request.getCookies().getFirst(name);
        return cookie == null || !StringUtils.hasText(cookie.getValue()) ? null : cookie.getValue();
    }

    /** Hand the browser this session. The cookie lives exactly as long as the token. */
    public void set(ServerHttpResponse response, String token, Instant expiresAt) {
        write(response, token, Duration.between(Instant.now(), expiresAt));
    }

    public void clear(ServerHttpResponse response) {
        write(response, "", Duration.ZERO);
    }

    /** True when the client asked for the cookie alone, with no token in the response. */
    public static boolean isCookieClient(ServerHttpRequest request) {
        return COOKIE_CLIENT.equals(request.getHeaders().getFirst(CLIENT_HEADER));
    }

    private void write(ServerHttpResponse response, String value, Duration maxAge) {
        // set, not add: the filter may already have cleared a stale cookie on
        // this response, and only one Set-Cookie for the name may go out.
        response.getCookies().set(name, ResponseCookie.from(name, value)
                .httpOnly(true)
                .secure(secure)
                .sameSite("Strict")
                .path("/")
                .maxAge(maxAge)
                .build());
    }
}
