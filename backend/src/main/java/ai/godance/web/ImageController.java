package ai.godance.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.service.ImageResponseFormat;
import ai.godance.security.JwtService;
import ai.godance.service.ImageGenerationService;
import ai.godance.service.JobOrigin;
import ai.godance.service.RecipeRuns;
import ai.godance.service.RecipeRuns.ImageRun;
import ai.godance.web.dto.ImageDtos.ImageGenerationRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * OpenAI-compatible image generation endpoint. Sync by default; pass
 * {@code "async": true} (Godance extension) to get back a 202 +
 * {@code job_id} immediately and resolve via {@code GET /v1/jobs/{id}}
 * or a webhook POST to {@code webhook_url}.
 *
 * <p>Every image model runs as a job, so in practice every request gets
 * the 202, unless it is made with an API key set to {@code sync}
 * ({@code image_response}). Such a request waits for its job and answers
 * 200 in OpenAI's shape, unless it sets {@code "async": true} or sends
 * {@code Prefer: respond-async}. A signed-in session never waits. That rule
 * lives in {@link ImageJobAnswers}, which the edits route uses too.
 */
@RestController
@RequestMapping("/v1")
public class ImageController {

    private final ImageGenerationService service;
    private final RecipeRuns runs;
    private final ImageJobAnswers answers;

    public ImageController(ImageGenerationService service, RecipeRuns runs, ImageJobAnswers answers) {
        this.service = service;
        this.runs = runs;
        this.answers = answers;
    }

    @PostMapping("/images/generations")
    public Mono<ResponseEntity<?>> generate(
            @Valid @RequestBody ImageGenerationRequest req,
            @AuthenticationPrincipal Jwt jwt,
            @RequestHeader(name = "Prefer", required = false) String prefer) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID apiKeyId = apiKeyId(jwt);
        // The channel comes from the credentials; a malformed gallery_recipe is a 400 before any charge.
        JobOrigin origin = JobOrigin.of(apiKeyId, req.galleryRecipe());
        ImageRequest domain = toDomain(req);

        // Auto-promote to async if the model requires it (e.g. gpt-image-1-hd
        // takes long enough that sync HTTP is risky), OR if the client opted in.
        boolean forceAsync = service.requiresAsyncFor(domain);
        ImageResponseFormat format = ImageResponseFormat.parse(req.responseFormat());   // 400 before any charge
        // A request waits for its job only when the caller did not ask for a job:
        // an API key (it must also be set to sync), on a model that runs as one.
        boolean mayWait = answers.mayWait(apiKeyId, forceAsync, req.async(), prefer);
        if (Boolean.TRUE.equals(req.async()) || forceAsync) {
            return Mono.fromCallable(() -> {
                        // The recipe is rendered and its slots' images loaded, before anything is priced or charged.
                        ImageRun run = runs.image(userId, domain, req.prompt(), req.recipe(), origin);
                        return service.executeAsync(userId, apiKeyId, run.request(),
                                req.webhookUrl(), run.origin(), run.inputs());
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(jobId -> answers.forJob(apiKeyId, jobId, format, mayWait));
        }

        return Mono.fromCallable(() -> {
                    ImageRun run = runs.image(userId, domain, req.prompt(), req.recipe(), origin);
                    return service.execute(userId, apiKeyId, run.request(), run.origin(), run.inputs());
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> answers.forResult(result, format, domain.model()));
    }

    /** The API key id when the caller authenticated with an API key; null for a JWT session. */
    private static UUID apiKeyId(Jwt jwt) {
        String claim = jwt.getClaimAsString("api_key_id");
        return claim == null ? null : UUID.fromString(claim);
    }

    private static ImageRequest toDomain(ImageGenerationRequest req) {
        return new ImageRequest(req.model(), req.prompt(), req.size(),
                req.n(), req.style(), req.quality());
    }
}
