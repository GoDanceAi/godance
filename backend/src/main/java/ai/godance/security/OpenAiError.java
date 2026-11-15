package ai.godance.security;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.http.ProblemDetail;
import org.springframework.web.server.ServerWebExchange;

/**
 * OpenAI's {@code error} object, added next to the problem+json members on
 * the routes that follow OpenAI's Videos API ({@code /v1/videos}, not
 * {@code /v1/videos/generations}).
 *
 * <p>OpenAI's SDKs build an exception's text from {@code error.message}
 * alone, and its {@code code}, {@code param} and {@code type} from the same
 * object. A problem+json body has no such member, so without it the SDK's
 * error reads only "Error code: 400". The body stays problem+json: every
 * member it had is still there.
 */
public final class OpenAiError {

    /** The member's name, as OpenAI's API has it. */
    public static final String MEMBER = "error";

    private static final String VIDEOS = "/v1/videos";
    private static final String GENERATIONS = "/v1/videos/generations";

    private OpenAiError() { }

    /** Whether this request is to one of the routes that follow OpenAI's Videos API. */
    public static boolean appliesTo(ServerWebExchange exchange) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals(GENERATIONS) || path.startsWith(GENERATIONS + "/")) return false;
        return path.equals(VIDEOS) || path.startsWith(VIDEOS + "/");
    }

    /** Adds the member to a problem on its way out, built from what the problem already says. */
    public static void addTo(ProblemDetail problem) {
        Map<String, Object> properties = problem.getProperties();
        if (properties != null && properties.containsKey(MEMBER)) return;
        Object code = properties == null ? null : properties.get("code");
        Object param = properties == null ? null : properties.get("param");
        String message = problem.getDetail() != null ? problem.getDetail() : problem.getTitle();
        problem.setProperty(MEMBER, of(problem.getStatus(), message,
                code instanceof String c ? c : null, param instanceof String p ? p : null));
    }

    /** The object itself: {@code message}, {@code type}, {@code param} and {@code code}, null where there is none. */
    public static Map<String, Object> of(int status, String message, String code, String param) {
        Map<String, Object> error = new LinkedHashMap<>();
        error.put("message", message);
        error.put("type", type(status));
        error.put("param", param);
        error.put("code", code);
        return error;
    }

    /** OpenAI's error types, by status. */
    static String type(int status) {
        if (status == 401) return "authentication_error";
        if (status == 403) return "permission_error";
        if (status == 404) return "not_found_error";
        if (status == 429) return "rate_limit_error";
        if (status >= 500) return "server_error";
        return "invalid_request_error";
    }
}
