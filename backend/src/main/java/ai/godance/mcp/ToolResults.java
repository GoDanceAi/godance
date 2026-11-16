package ai.godance.mcp;

import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.ErrorResponse;

import io.modelcontextprotocol.spec.McpSchema.CallToolResult;

import ai.godance.security.RateLimitExceededException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * What a tool answers.
 *
 * <ul>
 *   <li><b>Success:</b> the result as structured content (the same JSON the
 *       REST route returns, or a small object around it), and as text: a
 *       one-line summary for the model, then the same JSON, for clients that
 *       read text only.</li>
 *   <li><b>Error:</b> an MCP tool error ({@code isError}) whose text is the
 *       REST problem: its HTTP {@code status}, its {@code code} when it has
 *       one, its {@code detail}, and its other members (a terms version, an
 *       estimate). An agent can act on the code as a REST client would. An
 *       error carries no structured content: clients check structured
 *       content against the tool's output schema even on errors.</li>
 * </ul>
 *
 * <p>An exception that is not a REST error is logged and answered as a
 * plain 500, with nothing of the exception in it.
 */
final class ToolResults {

    private static final Logger log = LoggerFactory.getLogger(ToolResults.class);
    private static final TypeReference<Map<String, Object>> MAP = new TypeReference<>() { };

    private final ObjectMapper json;

    ToolResults(ObjectMapper json) {
        this.json = json;
    }

    /** {@code value} as structured content, with {@code summary} and the JSON as its text. */
    CallToolResult success(String summary, Object value) {
        Map<String, Object> structured = toMap(value);
        return CallToolResult.builder()
                .addTextContent(summary + "\n\n" + json.writeValueAsString(structured))
                .structuredContent(structured)
                .isError(false)
                .build();
    }

    /** The tool error for {@code error}: the REST problem, as text. */
    CallToolResult error(String tool, Throwable error) {
        Map<String, Object> problem = problem(tool, error);
        Object code = problem.get("code");
        String headline = "Error " + problem.get("status") + (code == null ? "" : " " + code) + ": " + problem.get("detail");
        return CallToolResult.builder()
                .addTextContent(headline + "\n\n" + json.writeValueAsString(problem))
                .isError(true)
                .build();
    }

    /** A value as a JSON object, written as the HTTP API writes it. */
    Map<String, Object> toMap(Object value) {
        return json.convertValue(value, MAP);
    }

    /**
     * The REST problem for an error: {@code status}, {@code title},
     * {@code detail} and every extension member ({@code code} among them),
     * exactly as {@code application/problem+json} carries them.
     */
    static Map<String, Object> problem(String tool, Throwable error) {
        Map<String, Object> problem = new LinkedHashMap<>();
        if (error instanceof ErrorResponse response) {
            ProblemDetail body = response.getBody();
            int status = response.getStatusCode().value();
            problem.put("status", status);
            HttpStatus known = HttpStatus.resolve(status);
            String title = body.getTitle() != null ? body.getTitle() : known != null ? known.getReasonPhrase() : null;
            if (title != null) problem.put("title", title);
            String detail = body.getDetail();
            problem.put("detail", detail != null ? detail : title);
            if (body.getProperties() != null) problem.putAll(body.getProperties());
            if (error instanceof RateLimitExceededException limited) {
                problem.put("retry_after_seconds", limited.getRetryAfterSeconds());
            }
            return problem;
        }
        log.error("MCP tool {} failed", tool, error);
        problem.put("status", 500);
        problem.put("title", "Internal Server Error");
        problem.put("detail", "The tool could not finish because of an error on the server. Try again later.");
        return problem;
    }
}
