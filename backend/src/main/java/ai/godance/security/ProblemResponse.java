package ai.godance.security;

import java.nio.charset.StandardCharsets;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.server.reactive.ServerHttpResponse;
import org.springframework.web.server.ServerWebExchange;

import reactor.core.publisher.Mono;

/**
 * Writes an RFC 7807 {@code application/problem+json} response directly
 * from a {@code WebFilter}. Filter-level rejections happen outside the
 * WebFlux {@code DispatcherHandler}, so the framework's {@code ProblemDetail}
 * rendering never runs for them — this keeps those responses consistent
 * with the problem-detail shape controllers produce.
 */
final class ProblemResponse {

    private ProblemResponse() { }

    static Mono<Void> write(ServerWebExchange exchange, HttpStatus status, String detail) {
        ServerHttpResponse response = exchange.getResponse();
        response.setStatusCode(status);
        response.getHeaders().setContentType(MediaType.APPLICATION_PROBLEM_JSON);
        String body = "{\"type\":\"about:blank\",\"title\":\"" + escape(status.getReasonPhrase())
                + "\",\"status\":" + status.value()
                + ",\"detail\":\"" + escape(detail) + "\"}";
        DataBuffer buffer = response.bufferFactory().wrap(body.getBytes(StandardCharsets.UTF_8));
        return response.writeWith(Mono.just(buffer));
    }

    private static String escape(String s) {
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        return sb.toString();
    }
}
