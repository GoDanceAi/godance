package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

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
 *
 * <p>Errors are problem+json, as everywhere, and on this route also carry
 * OpenAI's {@code error} object, which is where its SDKs read an error's
 * message ({@link ai.godance.security.OpenAiError}).
 *
 * <p>{@code POST /v1/images/variations}, OpenAI's variations of an image, is
 * here only to answer that Godance does not offer it.
 */
@RestController
@RequestMapping("/v1")
public class ImageController {

    /** The {@code code} of {@code POST /v1/images/variations}'s 404. */
    public static final String CODE_VARIATIONS_NOT_SUPPORTED = "image_variations_not_supported";

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

    // ===== What OpenAI's Images API has and Godance does not ==========

    /**
     * {@code client.images.create_variation}. Godance does not make variations
     * of an image, so this answers 404 with a code of its own, as the video
     * routes Godance does not offer do, to a session and an API key alike. The
     * form is never read and nothing is charged.
     */
    @PostMapping("/images/variations")
    public Mono<Void> createVariation() {
        ResponseStatusException refused = new ResponseStatusException(HttpStatus.NOT_FOUND,
                "Creating a variation of an image is not supported. Godance serves generations and edits "
                        + "of OpenAI's Images API: to work from an image, send it to POST /v1/images/edits "
                        + "with a prompt.");
        refused.getBody().setProperty("code", CODE_VARIATIONS_NOT_SUPPORTED);
        throw refused;
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
