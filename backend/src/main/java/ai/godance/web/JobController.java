package ai.godance.web;

import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.security.JwtService;
import ai.godance.service.JobReadService;
import ai.godance.web.dto.ImageDtos.JobView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Poll and history endpoints for async generation jobs. Caller must own the job.
 *
 * <p>{@code GET /v1/jobs} is a bare array, newest first. Optional query
 * parameters: {@code limit} (1–100; without it every job is returned, as
 * before), {@code before} (a job id: only older jobs), {@code modality}
 * ({@code IMAGE} or {@code VIDEO}) and {@code status}.
 */
@RestController
@RequestMapping("/v1")
public class JobController {

    private final JobReadService reads;

    public JobController(JobReadService reads) {
        this.reads = reads;
    }

    @GetMapping("/jobs")
    public Mono<List<JobView>> list(@AuthenticationPrincipal Jwt jwt,
                                    @RequestParam(required = false) String limit,
                                    @RequestParam(required = false) String before,
                                    @RequestParam(required = false) String modality,
                                    @RequestParam(required = false) String status) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Integer pageSize = parseLimit(limit);
        UUID cursor = parseBefore(before);
        Modality kind = parseModality(modality);
        JobStatus state = parseStatus(status);
        return Mono.fromCallable(() -> reads.list(userId, pageSize, cursor, kind, state))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/jobs/{id}")
    public Mono<JobView> getJob(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> reads.get(userId, id))
                .subscribeOn(Schedulers.boundedElastic());
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

    private static ResponseStatusException badRequest(String detail) {
        return new ResponseStatusException(HttpStatus.BAD_REQUEST, detail);
    }
}
