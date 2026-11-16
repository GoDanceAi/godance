package ai.godance.web;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.multipart.FormFieldPart;
import org.springframework.http.codec.multipart.Part;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.ApiKeyAuthFilter;
import ai.godance.security.JwtService;
import ai.godance.service.InputRefusedException;
import ai.godance.service.InputService;
import ai.godance.web.dto.InputDtos.AcceptTermsRequest;
import ai.godance.web.dto.InputDtos.InputFileView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Input files (round 11 brief 01): images a caller uploads to use in a
 * generation.
 *
 * <pre>
 * POST   /v1/files                multipart: file=&lt;image&gt;, purpose=generation_input → 201
 * GET    /v1/files?limit=&amp;before=  the caller's files, newest first
 * GET    /v1/files/{id}
 * GET    /v1/files/{id}/content   302 to a short-lived signed link
 * DELETE /v1/files/{id}           204
 * POST   /v1/inputs/terms         {"version": 1} → 204, a signed-in session only
 * </pre>
 *
 * Every file is private to its owner: another user's answers 404
 * {@code file_not_found}, exactly as one that does not exist. Uploading
 * answers 403 {@code inputs_disabled} while inputs are switched off, which
 * they are by default. Reading and deleting what is already stored keep
 * working then, so nobody is locked out of their own files.
 */
@RestController
@RequestMapping("/v1")
public class FileController {

    private final InputService inputs;
    private final InputUploadIntake intake;

    public FileController(InputService inputs, InputUploadIntake intake) {
        this.inputs = inputs;
        this.intake = intake;
    }

    /**
     * Stores one image. The checks run in the order the contract gives, and
     * the first one that fails answers: switched on, an adult's account, the
     * terms accepted, the size in bytes, the type (from the bytes), the size
     * in pixels, the person check. Nothing of the body is read until the
     * first three have passed.
     */
    @PostMapping("/files")
    public Mono<ResponseEntity<InputFileView>> upload(@AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = InputUploadIntake.clientIp(exchange);
        inputs.requireEnabled();
        MediaType contentType = exchange.getRequest().getHeaders().getContentType();
        if (contentType == null || !MediaType.MULTIPART_FORM_DATA.isCompatibleWith(contentType)) {
            throw new ResponseStatusException(HttpStatus.UNSUPPORTED_MEDIA_TYPE,
                    "send the image as multipart/form-data, in a part named file");
        }
        // The limits, the account and the upload itself are taken in by the same code
        // as an image sent with an edit, so neither route is a way around the other.
        return intake.admit(userId, ip)
                .then(Mono.defer(() -> InputUploads.form(exchange, FileController::unreadableForm)))
                .flatMap(form -> {
                    requirePurpose(form.getFirst("purpose"));
                    Part file = form.getFirst("file");
                    if (file == null) {
                        return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                "the form needs a part named file"));
                    }
                    return intake.store(userId, ip, file);
                })
                .onErrorMap(InputUploads::isTooLarge, e -> intake.tooLarge(userId, ip))
                .map(file -> ResponseEntity.status(HttpStatus.CREATED).body(InputFileView.from(file)));
    }

    @GetMapping("/files")
    public Mono<List<InputFileView>> list(@AuthenticationPrincipal Jwt jwt,
                                          @RequestParam(required = false) String limit,
                                          @RequestParam(required = false) String before) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Integer pageSize = parseLimit(limit);
        UUID cursor = parseBefore(before);
        return Mono.fromCallable(() -> inputs.list(userId, pageSize, cursor).stream()
                        .map(InputFileView::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/files/{id}")
    public Mono<InputFileView> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID fileId = fileId(id);
        return Mono.fromCallable(() -> InputFileView.from(inputs.get(userId, fileId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Redirects to a link that works for a few minutes. The link is the owner's alone; it is never cached. */
    @GetMapping("/files/{id}/content")
    public Mono<ResponseEntity<Void>> content(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID fileId = fileId(id);
        return Mono.fromCallable(() -> inputs.contentUrl(userId, fileId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(url -> ResponseEntity.status(HttpStatus.FOUND)
                        .location(URI.create(url))
                        .cacheControl(CacheControl.noStore())
                        .header("Referrer-Policy", "no-referrer")
                        .<Void>build());
    }

    @DeleteMapping("/files/{id}")
    public Mono<ResponseEntity<Void>> delete(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID fileId = fileId(id);
        String ip = InputUploadIntake.clientIp(exchange);
        return Mono.fromRunnable(() -> inputs.delete(userId, fileId, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /**
     * Accepts the input terms. A signed-in session only: the terms are for a
     * person to read, so an API key cannot accept them
     * ({@code SecurityConfig} refuses it before this runs, and this checks
     * again).
     */
    @PostMapping("/inputs/terms")
    public Mono<ResponseEntity<Void>> acceptTerms(@AuthenticationPrincipal Jwt jwt,
                                                  @RequestBody(required = false) AcceptTermsRequest req,
                                                  ServerWebExchange exchange) {
        if (jwt.hasClaim(ApiKeyAuthFilter.API_KEY_ID_CLAIM)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Sign in to accept the input terms; API keys can't.");
        }
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = InputUploadIntake.clientIp(exchange);
        Integer version = req == null ? null : req.version();
        return Mono.fromRunnable(() -> inputs.acceptTerms(userId, version, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /**
     * A form that cannot be read at all, and why: such as one of more parts
     * than a form may have ({@code spring.webflux.multipart.max-parts}).
     * 400 {@code invalid_parameter}, as on the other routes that take a form.
     */
    private static ResponseStatusException unreadableForm(String why) {
        ResponseStatusException refused = new ResponseStatusException(HttpStatus.BAD_REQUEST,
                why + " Send the image in one part named file, and purpose beside it if you like.");
        refused.getBody().setProperty("code", "invalid_parameter");
        return refused;
    }

    /** {@code purpose} may be left out; when it is given it must be the one purpose there is. */
    private static void requirePurpose(Part purpose) {
        if (purpose == null) return;
        String value = purpose instanceof FormFieldPart field ? field.value().trim() : null;
        if (!InputFileView.PURPOSE.equals(value)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "purpose must be " + InputFileView.PURPOSE);
        }
    }

    /** A malformed id cannot be anyone's file: the same 404 as an unknown one. */
    private static UUID fileId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw InputRefusedException.fileNotFound();
        }
    }

    private static Integer parseLimit(String raw) {
        if (raw == null) return null;
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= InputService.MAX_LIMIT) return limit;
        } catch (NumberFormatException ignored) {
            // falls through to the 400
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "limit must be an integer between 1 and " + InputService.MAX_LIMIT);
    }

    private static UUID parseBefore(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "before must be a file id");
        }
    }
}
