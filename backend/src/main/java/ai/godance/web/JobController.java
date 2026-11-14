package ai.godance.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.security.JwtService;
import ai.godance.service.JobReadService;
import ai.godance.service.JobSaveService;
import ai.godance.service.JobSaveService.SavedCount;
import ai.godance.web.dto.ImageDtos.JobView;
import ai.godance.web.dto.ImageDtos.SavedJobsView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Poll and history endpoints for async generation jobs. Caller must own the job.
 *
 * <p>{@code GET /v1/jobs} is a bare array, newest first. Optional query
 * parameters: {@code limit} (1–100; without it every job is returned, as
 * before), {@code before} (a job id: only older jobs), {@code modality}
 * ({@code IMAGE} or {@code VIDEO}), {@code status}, {@code saved}
 * ({@code true}: only saved jobs; {@code false}: only the others) and
 * {@code recipe} (a recipe id: only the jobs that ran one of its versions).
 *
 * <p>Saving a job keeps it past the retention period:
 * {@code PUT /v1/jobs/{id}/saved} and {@code DELETE /v1/jobs/{id}/saved},
 * both 204 and idempotent. {@code GET /v1/jobs/saved} is how many the caller
 * has saved against the account's limit.
 */
@RestController
@RequestMapping("/v1")
public class JobController {

    private final JobReadService reads;
    private final JobSaveService saves;

    public JobController(JobReadService reads, JobSaveService saves) {
        this.reads = reads;
        this.saves = saves;
    }

    @GetMapping("/jobs")
    public Mono<List<JobView>> list(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String before,
                                    @RequestParam(required = false) String modality,
                                    @RequestParam(required = false) String status,
                                    @RequestParam(required = false) String saved,
                                    @RequestParam(required = false) String recipe) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Integer pageSize = parseLimit(limit);
        UUID cursor = parseBefore(before);
        Modality kind = parseModality(modality);
        JobStatus state = parseStatus(status);
        Boolean onlySaved = parseSaved(saved);
        UUID recipeId = parseRecipe(recipe);
        return Mono.fromCallable(() -> reads.list(userId, pageSize, cursor, kind, state, onlySaved, recipeId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /** How many generations the caller has saved, and the most the account can. */
    @GetMapping("/jobs/saved")
    public Mono<SavedJobsView> savedCount(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> {
                    SavedCount saved = saves.count(userId);
                    return new SavedJobsView(saved.count(), saved.limit());
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/jobs/{id}")
    public Mono<JobView> getJob(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> reads.get(userId, id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Saves the caller's job. 404 for another user's; a 409 problem+json with
     * {@code code} {@code job_not_saveable} (not DONE, or already purged) or
     * {@code saved_limit_reached}.
     */
    @PutMapping("/jobs/{id}/saved")
    public Mono<ResponseEntity<Void>> save(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                           ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromRunnable(() -> saves.save(userId, id, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /** Un-saves the caller's job. 404 for another user's. */
    @DeleteMapping("/jobs/{id}/saved")
    public Mono<ResponseEntity<Void>> unsave(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt,
                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return Mono.fromRunnable(() -> saves.unsave(userId, id, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    private static Integer parseLimit(String raw) {
        if (raw == null) return null;
        try {
            int limit = Integer.parseInt(raw.trim());
            if (limit >= 1 && limit <= JobReadService.MAX_LIMIT) return limit;
        } catch (NumberFormatException ignored) {
            // falls through to the 400
        }
        throw badRequest("limit must be an integer between 1 and " + JobReadService.MAX_LIMIT);
    }

    private static UUID parseBefore(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw badRequest("before must be a job id");
        }
    }

    private static UUID parseRecipe(String raw) {
        if (raw == null) return null;
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException e) {
            throw badRequest("recipe must be a recipe id");
        }
    }

    private static Modality parseModality(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toUpperCase(Locale.ROOT)) {
            case "IMAGE" -> Modality.IMAGE;
            case "VIDEO" -> Modality.VIDEO;
            default -> throw badRequest("modality must be IMAGE or VIDEO");
        };
    }

    private static JobStatus parseStatus(String raw) {
        if (raw == null) return null;
        try {
            return JobStatus.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw badRequest("status must be one of " + java.util.Arrays.toString(JobStatus.values()));
        }
    }

    private static Boolean parseSaved(String raw) {
        if (raw == null) return null;
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true" -> Boolean.TRUE;
            case "false" -> Boolean.FALSE;
            default -> throw badRequest("saved must be true or false");
        };
    }

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
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
