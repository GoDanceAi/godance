package ai.godance.web;

import org.springframework.core.annotation.Order;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ControllerAdvice;
import org.springframework.web.reactive.result.method.annotation.ResponseEntityExceptionHandler;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.OpenAiError;
import reactor.core.publisher.Mono;

/**
 * Renders every controller error as {@code application/problem+json},
 * whatever the request's {@code Accept} header says.
 *
 * <p>Spring picks an error's media type by content negotiation, and the SSE
 * writer can encode any object. So a request that accepts
 * {@code text/event-stream} (every streaming chat call from the SPA, and
 * OpenAI SDKs with {@code stream: true}) got its 402 / 404 / 400 back as a
 * {@code data:} frame, which clients reading problem JSON cannot parse.
 * Fixing the type on the response skips that negotiation.
 *
 * <p>On the routes that follow OpenAI's API (its image routes and its Videos
 * API, as {@link OpenAiError} names them) the problem also carries OpenAI's
 * {@code error} object; every other route's body is unchanged.
 *
 * <p>Replaces Boot's own handler ({@code spring.webflux.problemdetails.enabled}),
 * which backs off when a {@link ResponseEntityExceptionHandler} bean exists.
 */
@ControllerAdvice
@Order(0)
public class ProblemJsonExceptionHandler extends ResponseEntityExceptionHandler {

    @Override
    protected Mono<ResponseEntity<Object>> createResponseEntity(
            Object body, HttpHeaders headers, HttpStatusCode status, ServerWebExchange exchange) {
        if (body instanceof ProblemDetail problem) {
            // On OpenAI's image and video routes the problem also carries OpenAI's error
            // object, which is where its SDKs read an error's message from.
            if (OpenAiError.appliesTo(exchange)) OpenAiError.addTo(problem);
            // The incoming headers may be read-only (an exception's own), so copy them.
            HttpHeaders withType = HttpHeaders.copyOf(headers);
            withType.setContentType(MediaType.APPLICATION_PROBLEM_JSON);
            return super.createResponseEntity(body, withType, status, exchange);
        }
        return super.createResponseEntity(body, headers, status, exchange);
    }
}
