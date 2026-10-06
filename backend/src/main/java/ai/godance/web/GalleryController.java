package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
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

import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import ai.godance.security.JwtService;
import ai.godance.service.GalleryService;
import ai.godance.service.ShareRewardService;
import ai.godance.web.dto.GalleryDtos.GalleryItemView;
import ai.godance.web.dto.GalleryDtos.GalleryListResponse;
import ai.godance.web.dto.GalleryDtos.LikeResponse;
import ai.godance.web.dto.GalleryDtos.PublishGalleryRequest;
import ai.godance.web.dto.GalleryDtos.ShareRewardResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1")
public class GalleryController {

    private final GalleryService galleryService;
    private final ShareRewardService shareRewardService;
    private final UserRepository userRepository;

    public GalleryController(GalleryService galleryService,
                             ShareRewardService shareRewardService,
                             UserRepository userRepository) {
        this.galleryService = galleryService;
        this.shareRewardService = shareRewardService;
        this.userRepository = userRepository;
    }

    @GetMapping("/gallery")
    public Mono<GalleryListResponse> list(
            @RequestParam(name = "source", required = false) String source,
            @RequestParam(name = "modality", required = false) String modality,
            @RequestParam(name = "category", required = false) String category,
            @RequestParam(name = "model", required = false) String model,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "page", defaultValue = "0") int page,
            @RequestParam(name = "size", defaultValue = "20") int size
    ) {
        return Mono.fromCallable(() -> galleryService.listGallery(source, modality, category, model, query, page, size))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/gallery")
    public Mono<ResponseEntity<GalleryItemView>> publish(
            @RequestBody PublishGalleryRequest request,
            @AuthenticationPrincipal Jwt jwt
    ) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> {
            String authorName = userRepository.findById(userId)
                    .map(User::getFullName)
                    .orElse("Anonymous Creator");
            return galleryService.publish(userId, authorName, request);
        })
        .subscribeOn(Schedulers.boundedElastic())
        .map(item -> ResponseEntity.status(HttpStatus.CREATED).body(item));
    }

    @PostMapping("/gallery/{id}/like")
    public Mono<LikeResponse> like(@PathVariable UUID id) {
        return Mono.fromCallable(() -> galleryService.like(id))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/rewards/share")
    public Mono<ShareRewardResponse> claimReward(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> shareRewardService.claimShareReward(userId))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
