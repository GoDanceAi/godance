package ai.godance.web;

import java.io.OutputStream;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.BatchRefusedException;
import ai.godance.service.RecipeBatchResults;
import ai.godance.service.RecipeBatchService;
import ai.godance.web.dto.BatchDtos.BatchEstimate;
import ai.godance.web.dto.BatchDtos.BatchRequest;
import ai.godance.web.dto.BatchDtos.BatchRowView;
import ai.godance.web.dto.BatchDtos.BatchView;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Recipe batches: {@code /v1/recipe-batches} (round 12 brief 01). One
 * version of one of the caller's recipes, run over many rows; each row
 * becomes an ordinary job. A signed-in session or an API key, like the job
 * and recipe routes. Every batch is private to its owner: one that is not
 * the caller's answers 404 {@code batch_not_found}, exactly as one that
 * does not exist.
 *
 * <p>Creating a batch charges nothing and starts nothing. Its rows are
 * started a few at a time by the server, each job taking its own pre-debit;
 * when credit or a spending cap refuses one, the batch is PAUSED until
 * {@code POST …/resume}.
 *
 * <p>{@code GET /v1/recipe-batches} is a bare array, newest first, with
 * optional {@code limit} (1–100, default 20), {@code before} (a batch id:
 * only older ones) and {@code recipe_id}. {@code GET …/{id}/rows} is a bare
 * array in row order, with {@code limit} (1–100, default 50) and
 * {@code after} (the index of the last row already seen).
 */
@RestController
@RequestMapping("/v1/recipe-batches")
public class BatchController {

    private static final MediaType TEXT_CSV = new MediaType("text", "csv", java.nio.charset.StandardCharsets.UTF_8);
    private static final MediaType ZIP = new MediaType("application", "zip");

    private final RecipeBatchService batches;
    private final RecipeBatchResults results;

    public BatchController(RecipeBatchService batches, RecipeBatchResults results) {
        this.batches = batches;
        this.results = results;
    }

    /** What the batch would cost. The same checks as creating it; nothing is written or charged. */
    @PostMapping("/estimate")
    public Mono<BatchEstimate> estimate(@RequestBody BatchRequest req, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> batches.estimate(userId, req))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * 201 with the batch, QUEUED. A row that a single run would refuse
     * refuses the whole request: 400, with that refusal's {@code code}, the
     * row's index in {@code row}, and nothing written.
     */
    @PostMapping
    public Mono<ResponseEntity<BatchView>> create(@RequestBody BatchRequest req, @AuthenticationPrincipal Jwt jwt,
                                                  ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID apiKeyId = apiKeyId(jwt);
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> batches.create(userId, apiKeyId, req, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .map(batch -> ResponseEntity.status(HttpStatus.CREATED).body(batch));
    }

    @GetMapping
    public Mono<List<BatchView>> list(@AuthenticationPrincipal Jwt jwt,
                                      @RequestParam(required = false) String limit,
                                      @RequestParam(required = false) String before,
                                      @RequestParam(name = "recipe_id", required = false) String recipeId) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Integer pageSize = parseLimit(limit);
        UUID cursor = before == null ? null : parseId(before, "before must be a batch id");
        UUID recipe = recipeId == null ? null : parseId(recipeId, "recipe_id must be a recipe id");
        return Mono.fromCallable(() -> batches.list(userId, pageSize, cursor, recipe))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}")
    public Mono<BatchView> get(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID batchId = batchId(id);
        return Mono.fromCallable(() -> batches.get(userId, batchId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/{id}/rows")
    public Mono<List<BatchRowView>> rows(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                         @RequestParam(required = false) String limit,
                                         @RequestParam(required = false) String after) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID batchId = batchId(id);
        Integer pageSize = parseLimit(limit);
        Integer afterIndex = parseAfter(after);
        return Mono.fromCallable(() -> batches.rows(userId, batchId, pageSize, afterIndex))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Skips the rows that have not started; the rows that are running finish. Answers the batch. */
    @PostMapping("/{id}/cancel")
    public Mono<BatchView> cancel(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                  ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID batchId = batchId(id);
        String ip = clientIp(exchange);
        return Mono.fromCallable(() -> batches.cancel(userId, batchId, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** Continues a paused batch from where it stopped; 409 {@code batch_not_resumable} for any other. */
    @PostMapping("/{id}/resume")
    public Mono<BatchView> resume(@PathVariable String id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID batchId = batchId(id);
        return Mono.fromCallable(() -> batches.resume(userId, batchId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** One line per row, with a signed link to each output. Written as it is read; never held in memory. */
    @GetMapping("/{id}/results.csv")
    public Mono<ResponseEntity<Flux<DataBuffer>>> resultsCsv(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID batchId = batchId(id);
        // The checks run first, so a refusal is a problem+json answer and never half a file.
        return Mono.fromCallable(() -> results.csv(userId, batchId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(writer -> download(exchange, writer, TEXT_CSV, "batch-" + batchId + "-results.csv"));
    }

    /** Every stored output; 413 {@code results_too_large} past the configured size, pointing to the CSV. */
    @GetMapping("/{id}/results.zip")
    public Mono<ResponseEntity<Flux<DataBuffer>>> resultsZip(@PathVariable String id, @AuthenticationPrincipal Jwt jwt,
                                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID batchId = batchId(id);
        return Mono.fromCallable(() -> results.zip(userId, batchId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(writer -> download(exchange, writer, ZIP, "batch-" + batchId + "-results.zip"));
    }

    /** The file as a response body that is produced while it is sent, on a worker thread. */
    private static ResponseEntity<Flux<DataBuffer>> download(ServerWebExchange exchange, Consumer<OutputStream> writer,
                                                             MediaType type, String fileName) {
        Flux<DataBuffer> body = Flux.from(DataBufferUtils.outputStreamPublisher(writer,
                exchange.getResponse().bufferFactory(), command -> Schedulers.boundedElastic().schedule(command)));
        return ResponseEntity.ok()
                .contentType(type)
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(fileName).build().toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(body);
    }

    /** A batch id from the path. Anything that is not an id names no batch: the same 404. */
    private static UUID batchId(String raw) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw BatchRefusedException.notFound();
        }
    }

    private static UUID parseId(String raw, String detail) {
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
        }
    }

    private static Integer parseLimit(String raw) {
        if (raw == null) return null;
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= RecipeBatchService.MAX_LIMIT) return limit;
        } catch (NumberFormatException ignored) {
            // falls through to the 400
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "limit must be an integer between 1 and " + RecipeBatchService.MAX_LIMIT);
    }

    private static Integer parseAfter(String raw) {
        if (raw == null) return null;
        try {
            int after = Integer.parseInt(raw.trim());
            if (after >= -1) return after;
        } catch (NumberFormatException ignored) {
            // falls through to the 400
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "after must be the index of the last row you have: a whole number of 0 or more");
    }

    /** The API key id when the caller authenticated with an API key; null for a JWT session. */
    private static UUID apiKeyId(Jwt jwt) {
        String claim = jwt.getClaimAsString("api_key_id");
        return claim == null ? null : UUID.fromString(claim);
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy (forward-headers-strategy=framework) the
        // remote address can be UNRESOLVED — getAddress() returns null.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
