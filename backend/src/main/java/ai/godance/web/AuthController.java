package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.AuthService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.ForgotPasswordRequest;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.MeResponse;
import ai.godance.web.dto.AuthDtos.ResetPasswordRequest;
import ai.godance.web.dto.AuthDtos.SignupRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService auth;

    public AuthController(AuthService auth) {
        this.auth = auth;
    }

    @PostMapping("/signup")
    public Mono<ResponseEntity<AuthResponse>> signup(
            @Valid @RequestBody SignupRequest req, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> auth.signup(req, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @PostMapping("/login")
    public Mono<AuthResponse> login(
            @Valid @RequestBody LoginRequest req, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> auth.login(req, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/verify")
    public Mono<ResponseEntity<Void>> verify(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("token") String token, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> auth.verifyEmail(userId, token, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PostMapping("/verify/resend")
    public Mono<ResponseEntity<Void>> resendVerification(@AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> auth.resendVerificationEmail(userId, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    /** Always 202, so the response never says whether the address is registered. */
    @PostMapping("/password/forgot")
    public Mono<ResponseEntity<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req, ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> auth.requestPasswordReset(req, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.accepted().build()));
    }

    @PostMapping("/password/reset")
    public Mono<AuthResponse> resetPassword(
            @Valid @RequestBody ResetPasswordRequest req, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> auth.resetPassword(req, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    @GetMapping("/me")
    public Mono<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> auth.loadById(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(MeResponse::from);
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy (forward-headers-strategy=framework) the
        // remote address can be UNRESOLVED — getAddress() returns null.
        // Fall back to the host string instead of NPE-ing.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
