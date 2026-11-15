package ai.godance.web;

import java.nio.charset.StandardCharsets;
import java.time.Duration;

import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;

/**
 * {@code GET /v1/openapi.yaml}: the API's own description (OpenAPI 3.1),
 * for tools and for AI assistants writing code against it. Public, like
 * the documentation it restates.
 *
 * <p>The document is {@code static/openapi.yaml}, written by hand: the
 * multipart routes read their parts from the exchange, most handlers
 * answer {@code ResponseEntity<?>}, and the error codes live in services,
 * so nothing a generator could reflect on would describe this API.
 * {@code OpenApiDriftTest} keeps it true: it fails when a route in
 * {@code web/} is missing from the document, when the document has a path
 * the code lacks, and when a schema no longer has its record's fields.
 */
@RestController
public class OpenApiController {

    static final String LOCATION = "static/openapi.yaml";
    static final MediaType YAML = new MediaType("application", "yaml", StandardCharsets.UTF_8);

    private final Resource document = new ClassPathResource(LOCATION);

    @GetMapping("/v1/openapi.yaml")
    public Mono<ResponseEntity<Resource>> document() {
        return Mono.just(ResponseEntity.ok()
                .contentType(YAML)
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(document));
    }
}
