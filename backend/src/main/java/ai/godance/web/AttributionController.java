package ai.godance.web;

import java.time.Duration;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.config.AttributionProperties;
import ai.godance.config.RateLimitProperties.Limit;
import ai.godance.security.JwtService;
import ai.godance.security.RateLimiter;
import ai.godance.service.AttributionService;
import ai.godance.web.dto.AttributionDtos.FirstTouchRequest;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * {@code POST /v1/attribution/first-touch}: the app hands in what brought a
 * sign-up here, once, after sign-in. It needs a signed-in session: the
 * route falls under {@code SecurityConfig}'s deny-by-default rule, so an
 * API key is refused with a 403.
 *
 * <p>Always 204 for a well-formed body, whether or not anything was
 * written: a second call, and one for an account past the window, change
 * nothing. Not an audited action.
 */
@RestController
@RequestMapping("/v1/attribution")
public class AttributionController {

    /** The window the per-account limit counts in. */
    static final Duration LIMIT_WINDOW = Duration.ofHours(1);

    private final AttributionService attribution;
    private final RateLimiter limiter;
    private final AttributionProperties props;

    public AttributionController(AttributionService attribution, RateLimiter limiter,
                                 AttributionProperties props) {
        this.attribution = attribution;
        this.limiter = limiter;
        this.props = props;
    }

    @PostMapping("/first-touch")
    public Mono<ResponseEntity<Void>> firstTouch(@RequestBody FirstTouchRequest req,
                                                 @AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return limiter.check("first-touch-account", userId.toString(),
                        new Limit(props.firstTouchLimitPerAccount(), LIMIT_WINDOW))
                .then(Mono.fromRunnable(() -> attribution.recordFirstTouch(userId, req))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(Mono.just(ResponseEntity.noContent().<Void>build()));
    }
}
