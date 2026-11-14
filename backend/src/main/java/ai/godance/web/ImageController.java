package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
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
import ai.godance.service.ImageSyncService;
import ai.godance.security.JwtService;
import ai.godance.service.ImageGenerationService;
import ai.godance.domain.Modality;
import ai.godance.service.JobOrigin;
import ai.godance.service.RecipeRunService;
import ai.godance.service.RecipeRunService.RunPrompt;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.ImageGenerationRequest;
import ai.godance.web.dto.ImageDtos.ImageGenerationResponse;
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
 * ({@code image_response}, see {@link ImageSyncService}). Such a request
 * waits for its job and answers 200 in OpenAI's shape, unless it sets
 * {@code "async": true} or sends {@code Prefer: respond-async}. A
 * signed-in session never waits.
 */
@RestController
@RequestMapping("/v1")
public class ImageController {

    /** What a request that stopped waiting is told to wait before it reads the job. */
    static final int RETRY_AFTER_SECONDS = 5;

    private final ImageGenerationService service;
    private final RecipeRunService recipes;
    private final ImageSyncService sync;

    public ImageController(ImageGenerationService service, RecipeRunService recipes, ImageSyncService sync) {
        this.service = service;
        this.recipes = recipes;
        this.sync = sync;
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
        boolean mayWait = apiKeyId != null && forceAsync
                && !Boolean.TRUE.equals(req.async()) && !prefersAsync(prefer);
        if (Boolean.TRUE.equals(req.async()) || forceAsync) {
            return Mono.fromCallable(() -> {
                        // A recipe is rendered here, before anything is priced or charged.
                        RunPrompt run = recipes.resolve(userId, Modality.IMAGE, req.prompt(), req.recipe());
                        return service.executeAsync(userId, apiKeyId, withPrompt(domain, run.prompt()),
                                req.webhookUrl(), run.origin(origin));
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .flatMap(jobId -> mayWait ? answerFor(apiKeyId, jobId, format) : Mono.just(accepted(jobId)));
        }

        return Mono.fromCallable(() -> {
                    RunPrompt run = recipes.resolve(userId, Modality.IMAGE, req.prompt(), req.recipe());
                    return service.execute(userId, apiKeyId, withPrompt(domain, run.prompt()), run.origin(origin));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.status(HttpStatus.OK)
                        .<Object>body(inlines(format, domain.model())
                                ? sync.response(result.jobId(), format)
                                : ImageGenerationResponse.from(result)));
    }

    /** Today's answer for a job: 202 with its id and where to poll it. */
    private static ResponseEntity<?> accepted(UUID jobId) {
        return ResponseEntity.accepted().<Object>body(AsyncJobAcknowledged.forJob(jobId));
    }

    /**
     * The answer for a job the caller may wait on. A key left on async gets
     * the 202 at once. A sync key waits for the job: done is 200 in OpenAI's
     * shape; failed is a 502 problem with {@code code: "generation_failed"};
     * still running at the limit is the 202 with {@code Retry-After}, and the
     * job carries on.
     */
    private Mono<ResponseEntity<?>> answerFor(UUID apiKeyId, UUID jobId, ImageResponseFormat format) {
        return sync.waits(apiKeyId).flatMap(waits -> {
            if (!waits) return Mono.just(accepted(jobId));
            return sync.await(jobId).flatMap(waited -> switch (waited.outcome()) {
                case DONE -> Mono.fromCallable(() -> sync.response(waited.job(), format))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(body -> ResponseEntity.status(HttpStatus.OK).<Object>body(body));
                case FAILED -> Mono.error(ImageSyncService.generationFailed(waited.job()));
                case TIMEOUT -> Mono.just(ResponseEntity.accepted()
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                        .<Object>body(waited.job() == null
                                ? AsyncJobAcknowledged.forJob(jobId)
                                : AsyncJobAcknowledged.forOpenJob(jobId, waited.job().getStatus())));
            });
        });
    }

    /** RFC 7240: {@code Prefer: respond-async} asks for the job, whatever the key is set to. */
    private static boolean prefersAsync(String prefer) {
        if (prefer == null) return false;
        for (String preference : prefer.split("[,;]")) {
            if (preference.trim().equalsIgnoreCase("respond-async")) return true;
        }
        return false;
    }

    /** Whether a response that carries the images carries any as base64. */
    private static boolean inlines(ImageResponseFormat format, String model) {
        return format == ImageResponseFormat.B64_JSON
                || (format == null && ImageResponseFormat.inlineByDefault(model));
    }

    /** The API key id when the caller authenticated with an API key; null for a JWT session. */
    private static UUID apiKeyId(Jwt jwt) {
        String claim = jwt.getClaimAsString("api_key_id");
        return claim == null ? null : UUID.fromString(claim);
    }

    /** The request with the prompt that is run: the one it was sent with, or its recipe rendered. */
    private static ImageRequest withPrompt(ImageRequest request, String prompt) {
        return new ImageRequest(request.model(), prompt, request.size(), request.n(), request.style(), request.quality());
    }

    private static ImageRequest toDomain(ImageGenerationRequest req) {
        return new ImageRequest(req.model(), req.prompt(), req.size(),
                req.n(), req.style(), req.quality());
    }
}
