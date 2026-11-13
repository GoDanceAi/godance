package ai.godance.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.config.RateLimitProperties.Limit;
import ai.godance.config.ReferralProperties;
import ai.godance.security.JwtService;
import ai.godance.security.RateLimiter;
import ai.godance.service.ReferralService;
import ai.godance.service.ReferralService.ClaimResult;
import ai.godance.service.ReferralService.Enabled;
import ai.godance.service.ReferralService.MeView;
import ai.godance.web.dto.ReferralDtos.ClaimRequest;
import ai.godance.web.dto.ReferralDtos.ClaimResponse;
import ai.godance.web.dto.ReferralDtos.DisabledView;
import ai.godance.web.dto.ReferralDtos.EnabledView;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * A user's referral code and counts, and claiming one. Both need a
 * signed-in session: they fall under {@code SecurityConfig}'s
 * deny-by-default rule, so an API key is refused with a 403.
 */
@RestController
@RequestMapping("/v1/referrals")
public class ReferralController {

    /** The window the per-account and per-IP claim limits count in. */
    static final Duration CLAIM_LIMIT_WINDOW = Duration.ofHours(1);

    private final ReferralService referrals;
    private final RateLimiter limiter;
    private final ReferralProperties props;

    public ReferralController(ReferralService referrals, RateLimiter limiter, ReferralProperties props) {
        this.referrals = referrals;
        this.limiter = limiter;
        this.props = props;
    }

    /** The caller's code, the offer and their counts; {@code {"enabled": false}} while off. */
    @GetMapping("/me")
    public Mono<Object> me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> toView(referrals.me(userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Claim someone's code for the caller. Limited per client IP, then per
     * account, before any lookup, so codes can't be found by trying them.
     * A refusal is problem+json with {@code code} {@code referral_code_unknown}
     * (404) or {@code referral_not_eligible} (409).
     */
    @PostMapping("/claim")
    public Mono<ClaimResponse> claim(@RequestBody ClaimRequest req,
                                     @AuthenticationPrincipal Jwt jwt,
                                     ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        Mono<ClaimResponse> claim = Mono.fromCallable(() -> toResponse(referrals.claim(userId, req.code(), ip)))
                .subscribeOn(Schedulers.boundedElastic());
        if (!props.enabled()) {
            return claim;   // refused at once; nothing to count
        }
        return limiter.check("referral-claim-ip", ip, new Limit(props.claimLimitPerIp(), CLAIM_LIMIT_WINDOW))
                .then(limiter.check("referral-claim-account", userId.toString(),
                        new Limit(props.claimLimitPerAccount(), CLAIM_LIMIT_WINDOW)))
                .then(claim);
    }

    private static Object toView(MeView view) {
        if (view instanceof Enabled e) {
            return new EnabledView(true, e.code(), e.referrerRewardCents(), e.refereeRewardCents(),
                    e.minTopupCents(), e.invited(), e.rewarded(), e.earnedCents(), e.pendingRewardCents());
        }
        return new DisabledView(false);
    }

    private static ClaimResponse toResponse(ClaimResult result) {
        return new ClaimResponse(result.refereeRewardCents(), result.minTopupCents());
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy (forward-headers-strategy=framework) the
        // remote address can be UNRESOLVED — getAddress() returns null.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
