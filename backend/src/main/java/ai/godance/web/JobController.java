package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.repository.GenerationJobRepository;
import ai.godance.security.JwtService;
import ai.godance.web.dto.ImageDtos.JobView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Poll endpoint for async generation jobs. Caller must own the job. */
@RestController
@RequestMapping("/v1")
public class JobController {

    private final GenerationJobRepository jobs;

    public JobController(GenerationJobRepository jobs) {
        this.jobs = jobs;
    }

    @GetMapping("/jobs")
    public Mono<List<JobView>> list(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> jobs
                        .findByUserIdOrderByCreatedAtDesc(userId)
                        .stream().map(JobView::from).toList())
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/jobs/{id}")
    public Mono<JobView> getJob(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> jobs.findById(id)
                        .filter(j -> j.getUserId().equals(userId))
                        // 404 (not 403) on cross-user lookup — same leak-prevention
                        // pattern as ApiKeyService.delete.
                        .orElseThrow(() -> new ResponseStatusException(
                                HttpStatus.NOT_FOUND, "job not found")))
                .subscribeOn(Schedulers.boundedElastic())
                .map(JobView::from);
    }
}
