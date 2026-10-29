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

    public ImageController(ImageGenerationService service) {
        this.service = service;
    }

    @PostMapping("/images/generations")
    public Mono<ResponseEntity<?>> generate(
            @Valid @RequestBody ImageGenerationRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        UUID apiKeyId = apiKeyId(jwt);
        ImageRequest domain = toDomain(req);

        // Auto-promote to async if the model requires it (e.g. gpt-image-1-hd
        // takes long enough that sync HTTP is risky), OR if the client opted in.
        boolean forceAsync = service.requiresAsyncFor(domain);
        if (Boolean.TRUE.equals(req.async()) || forceAsync) {
            return Mono.fromCallable(() ->
                            service.executeAsync(userId, apiKeyId, domain, req.webhookUrl()))
                    .subscribeOn(Schedulers.boundedElastic())
                    .map(jobId -> ResponseEntity.accepted()
                            .<Object>body(AsyncJobAcknowledged.forJob(jobId)));
        }

        return Mono.fromCallable(() -> service.execute(userId, apiKeyId, domain))
                .subscribeOn(Schedulers.boundedElastic())
                .map(result -> ResponseEntity.status(HttpStatus.OK)
                        .<Object>body(ImageGenerationResponse.from(result)));
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
