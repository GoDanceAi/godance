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

import ai.godance.security.AuthRateLimits;
import ai.godance.security.JwtService;
import ai.godance.service.AuthService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.MeResponse;
import ai.godance.web.dto.AuthDtos.SignupRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final AuthRateLimits rateLimits;

    public AuthController(AuthService auth, AuthRateLimits rateLimits) {
        this.auth = auth;
        this.rateLimits = rateLimits;
    }

    @PostMapping("/signup")
    public Mono<ResponseEntity<AuthResponse>> signup(
            @Valid @RequestBody SignupRequest req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.signup(ip)
                .then(Mono.fromCallable(() -> auth.signup(req, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(body -> ResponseEntity.status(HttpStatus.CREATED).body(body));
    }

    @PostMapping("/login")
    public Mono<AuthResponse> login(
            @Valid @RequestBody LoginRequest req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.login(ip, req.email())
                .then(Mono.fromCallable(() -> auth.login(req, ip))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    @GetMapping("/verify")
    public Mono<ResponseEntity<Void>> verify(
            @RequestParam("token") String token, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.verify(ip)
                .then(Mono.fromRunnable(() -> auth.verifyEmail(token, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(Mono.just(ResponseEntity.noContent().build()));
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
