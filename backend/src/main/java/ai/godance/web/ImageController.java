package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.gateway.ImageDtos.ImageRequest;
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
 */
@RestController
@RequestMapping("/v1")
public class ImageController {

    private final ImageGenerationService service;
    private final RecipeRunService recipes;

    public ImageController(ImageGenerationService service, RecipeRunService recipes) {
        this.service = service;
        this.recipes = recipes;
    }

    @PostMapping("/images/generations")
    public Mono<ResponseEntity<?>> generate(
            @Valid @RequestBody ImageGenerationRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID apiKeyId = apiKeyId(jwt);
        // The channel comes from the credentials; a malformed gallery_recipe is a 400 before any charge.
        JobOrigin origin = JobOrigin.of(apiKeyId, req.galleryRecipe());
        ImageRequest domain = toDomain(req);

        // Auto-promote to async if the model requires it (e.g. gpt-image-1-hd
        // takes long enough that sync HTTP is risky), OR if the client opted in.
        boolean forceAsync = service.requiresAsyncFor(domain);
        if (Boolean.TRUE.equals(req.async()) || forceAsync) {
            return Mono.fromCallable(() -> {
                        // A recipe is rendered here, before anything is priced or charged.
                        RunPrompt run = recipes.resolve(userId, Modality.IMAGE, req.prompt(), req.recipe());
                        return service.executeAsync(userId, apiKeyId, withPrompt(domain, run.prompt()),
                                req.webhookUrl(), run.origin(origin));
                    })
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(jobId -> ResponseEntity.accepted()
                            .<Object>body(AsyncJobAcknowledged.forJob(jobId)));
        }

        return Mono.fromCallable(() -> {
                    RunPrompt run = recipes.resolve(userId, Modality.IMAGE, req.prompt(), req.recipe());
                    return service.execute(userId, apiKeyId, withPrompt(domain, run.prompt()), run.origin(origin));
                })
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.status(HttpStatus.OK)
                        .<Object>body(ImageGenerationResponse.from(result)));
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
