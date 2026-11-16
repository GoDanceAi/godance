package ai.godance.web;

import java.util.List;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.domain.UserRole;
import ai.godance.security.AdminRoles;
import ai.godance.security.JwtService;
import ai.godance.service.ModerationService;
import ai.godance.web.dto.CommunityDtos.QueueItem;
import ai.godance.web.dto.CommunityDtos.ReasonRequest;
import ai.godance.web.dto.CommunityDtos.ReviewRecipe;
import ai.godance.web.dto.CommunityDtos.ReviewReport;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Community moderation (round 14 brief 01): {@code /v1/admin/community}, for
 * {@code SUPPORT} and {@code ADMIN}. Open whether or not the community is
 * switched on, so staff can always take something down.
 */
@RestController
@RequestMapping("/v1/admin/community")
public class AdminModerationController {

    private final ModerationService moderation;

    public AdminModerationController(ModerationService moderation) {
        this.moderation = moderation;
    }

    /** New submissions, reported public recipes, and profiles to review. */
    @GetMapping("/queue")
    @AdminRoles({UserRole.SUPPORT, UserRole.ADMIN})
    public Mono<List<QueueItem>> queue() {
        return Mono.fromCallable(moderation::queue).subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/reports")
    @AdminRoles({UserRole.SUPPORT, UserRole.ADMIN})
    public Mono<List<ReviewReport>> reports() {
        return Mono.fromCallable(moderation::openReports).subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/recipes/{publicId}/approve")
    @AdminRoles({UserRole.SUPPORT, UserRole.ADMIN})
    public Mono<ReviewRecipe> approve(@PathVariable String publicId, @AuthenticationPrincipal Jwt jwt,
                                      ServerWebExchange exchange) {
        UUID moderatorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = Requests.clientIp(exchange);
        return Mono.fromCallable(() -> moderation.approve(moderatorId, publicId, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/recipes/{publicId}/reject")
    @AdminRoles({UserRole.SUPPORT, UserRole.ADMIN})
    public Mono<ReviewRecipe> reject(@PathVariable String publicId, @RequestBody(required = false) ReasonRequest req,
                                     @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID moderatorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = Requests.clientIp(exchange);
        String reason = req == null ? null : req.reason();
        return Mono.fromCallable(() -> moderation.reject(moderatorId, publicId, reason, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/recipes/{publicId}/remove")
    @AdminRoles({UserRole.SUPPORT, UserRole.ADMIN})
    public Mono<ReviewRecipe> remove(@PathVariable String publicId, @RequestBody(required = false) ReasonRequest req,
                                     @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID moderatorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = Requests.clientIp(exchange);
        String reason = req == null ? null : req.reason();
        return Mono.fromCallable(() -> moderation.remove(moderatorId, publicId, reason, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/profiles/{handle}/approve")
    @AdminRoles({UserRole.SUPPORT, UserRole.ADMIN})
    public Mono<QueueItem> approveProfile(@PathVariable String handle, @AuthenticationPrincipal Jwt jwt,
                                          ServerWebExchange exchange) {
        UUID moderatorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = Requests.clientIp(exchange);
        return Mono.fromCallable(() -> moderation.approveProfile(moderatorId, handle, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/profiles/{handle}/remove")
    @AdminRoles({UserRole.SUPPORT, UserRole.ADMIN})
    public Mono<QueueItem> removeProfile(@PathVariable String handle, @RequestBody(required = false) ReasonRequest req,
                                         @AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID moderatorId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = Requests.clientIp(exchange);
        String reason = req == null ? null : req.reason();
        return Mono.fromCallable(() -> moderation.removeProfile(moderatorId, handle, reason, ip))
                .subscribeOn(Schedulers.boundedElastic());
    }
}
