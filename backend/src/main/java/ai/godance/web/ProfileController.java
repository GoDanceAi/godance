package ai.godance.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.ModerationService;
import ai.godance.service.ProfileService;
import ai.godance.service.PublishedRecipeViews;
import ai.godance.web.dto.CommunityDtos.ProfileRequest;
import ai.godance.web.dto.CommunityDtos.ProfileView;
import ai.godance.web.dto.CommunityDtos.PublicRecipeView;
import ai.godance.web.dto.CommunityDtos.ReportRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * The caller's side of the community (round 14 brief 01): their profile,
 * who they follow, the feed of recipes from those accounts, and reports.
 * A signed-in session only: an API key cannot reach any of these.
 */
@RestController
public class ProfileController {

    private final ProfileService profiles;
    private final PublishedRecipeViews views;
    private final ModerationService moderation;

    public ProfileController(ProfileService profiles, PublishedRecipeViews views, ModerationService moderation) {
        this.profiles = profiles;
        this.views = views;
        this.moderation = moderation;
    }

    @GetMapping("/v1/profile")
    public Mono<ProfileView> get(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> profiles.get(userId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PutMapping("/v1/profile")
    public Mono<ProfileView> put(@RequestBody ProfileRequest req, @AuthenticationPrincipal Jwt jwt,
                                 ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = Requests.clientIp(exchange);
        return Mono.fromCallable(() -> profiles.put(userId, req, ip)).subscribeOn(Schedulers.boundedElastic());
    }

    /** The handles the caller follows, most recently followed first. */
    @GetMapping("/v1/follows")
    public Mono<List<String>> following(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> profiles.following(userId)).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/v1/follows/{handle}")
    public Mono<ResponseEntity<Void>> follow(@PathVariable String handle, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> profiles.follow(userId, handle))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    @DeleteMapping("/v1/follows/{handle}")
    public Mono<ResponseEntity<Void>> unfollow(@PathVariable String handle, @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> profiles.unfollow(userId, handle))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }

    /**
     * The newest public recipes of the accounts the caller follows, as the
     * public routes show them: a bare array, with {@code limit} (1–100) and
     * {@code before} (the public id of the last recipe of the previous page).
     */
    @GetMapping("/v1/follows/feed")
    public Mono<List<PublicRecipeView>> feed(@AuthenticationPrincipal Jwt jwt,
                                             @RequestParam(required = false) String limit,
                                             @RequestParam(required = false) String before,
                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Integer size = Requests.limit(limit, PublishedRecipeViews.MAX_LIMIT);
        String base = Requests.apiBase(exchange);
        return Mono.fromCallable(() -> views.feed(base, userId, size, before)).subscribeOn(Schedulers.boundedElastic());
    }

    /** Reports a public recipe or profile: 202. */
    @PostMapping("/v1/reports")
    public Mono<ResponseEntity<Void>> report(@RequestBody ReportRequest req, @AuthenticationPrincipal Jwt jwt,
                                             ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = Requests.clientIp(exchange);
        return Mono.fromRunnable(() -> moderation.report(userId, req, ip))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.accepted().<Void>build()));
    }
}
