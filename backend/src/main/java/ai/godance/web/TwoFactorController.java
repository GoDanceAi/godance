package ai.godance.web;

import java.time.Instant;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.AuthRateLimits;
import ai.godance.security.JwtService;
import ai.godance.service.AuthService;
import ai.godance.service.TwoFactorService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.TwoFactorDtos.DisableRequest;
import ai.godance.web.dto.TwoFactorDtos.EnableRequest;
import ai.godance.web.dto.TwoFactorDtos.EnableResponse;
import ai.godance.web.dto.TwoFactorDtos.EnrolRequest;
import ai.godance.web.dto.TwoFactorDtos.EnrolResponse;
import ai.godance.web.dto.TwoFactorDtos.StatusResponse;
import ai.godance.web.dto.TwoFactorDtos.VerifyRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Two-factor sign-in. {@code /verify} takes the challenge token from a
 * sign-in that owes its second factor, and nothing else does. The other
 * routes manage the signed-in account's own 2FA, so they need a full
 * session: {@code SecurityConfig} denies them to API keys and to challenges
 * alike.
 */
@RestController
@RequestMapping("/v1/auth/2fa")
public class TwoFactorController {

    private final AuthService auth;
    private final TwoFactorService twoFactor;
    private final AuthRateLimits rateLimits;

    public TwoFactorController(AuthService auth, TwoFactorService twoFactor, AuthRateLimits rateLimits) {
        this.auth = auth;
        this.twoFactor = twoFactor;
        this.rateLimits = rateLimits;
    }

    @GetMapping
    public Mono<StatusResponse> status(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> twoFactor.status(userId))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @PostMapping("/enrol")
    public Mono<EnrolResponse> enrol(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody EnrolRequest req, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Instant signedInAt = jwt.getIssuedAt();
        String ip = clientIp(exchange);
        return rateLimits.twoFactor(ip, userId)
                .then(Mono.fromCallable(() -> auth.startTwoFactorEnrolment(
                                userId, req.currentPassword(), signedInAt, ip))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/enable")
    public Mono<EnableResponse> enable(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody EnableRequest req, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return rateLimits.twoFactor(ip, userId)
                .then(Mono.fromCallable(() -> auth.enableTwoFactor(userId, req.code(), ip))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @PostMapping("/disable")
    public Mono<ResponseEntity<Void>> disable(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody DisableRequest req, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        Instant signedInAt = jwt.getIssuedAt();
        String ip = clientIp(exchange);
        return rateLimits.twoFactor(ip, userId)
                .then(Mono.fromRunnable(() -> auth.disableTwoFactor(
                                userId, req.currentPassword(), req.code(), signedInAt, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    /** The second step of signing in. The bearer token here is the challenge, not a session. */
    @PostMapping("/verify")
    public Mono<AuthResponse> verify(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody VerifyRequest req, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return rateLimits.twoFactor(ip, userId)
                .then(Mono.fromCallable(() -> auth.completeTwoFactorSignIn(userId, req.code(), ip))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy the remote address can be unresolved; fall back to the host string.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
