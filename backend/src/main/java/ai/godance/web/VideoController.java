package ai.godance.web;

import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.security.JwtService;
import ai.godance.service.VideoGenerationService;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.VideoDtos.VideoGenerationRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code POST /v1/videos/generations} — always async (no sync mode for
 * video; Sora can take 1–5 min and most load balancers time out long
 * before that). Always returns 202 + {@code job_id}; client polls
 * {@code GET /v1/jobs/{id}} or receives a signed webhook at
 * {@code webhook_url}.
 */
@RestController
@RequestMapping("/v1")
public class VideoController {

    private final VideoGenerationService service;

    public VideoController(VideoGenerationService service) {
        this.service = service;
    }

    @PostMapping("/videos/generations")
    public Mono<ResponseEntity<AsyncJobAcknowledged>> generate(
            @Valid @RequestBody VideoGenerationRequest req,
            @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        VideoRequest domain = new VideoRequest(
                req.model(), req.prompt(), req.size(), req.seconds(), req.n());

        UUID apiKeyId = apiKeyId(jwt);
        return Mono.fromCallable(() -> service.submit(userId, apiKeyId, domain, req.webhookUrl()))
                .subscribeOn(Schedulers.boundedElastic())
                .map(jobId -> ResponseEntity.accepted().body(AsyncJobAcknowledged.forJob(jobId)));
    }

    /** The API key id when the caller authenticated with an API key; null for a JWT session. */
    private static UUID apiKeyId(Jwt jwt) {
        String claim = jwt.getClaimAsString("api_key_id");
        return claim == null ? null : UUID.fromString(claim);
    }
}
