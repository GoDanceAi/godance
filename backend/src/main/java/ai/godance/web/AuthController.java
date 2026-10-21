package ai.godance.web;

import java.time.Instant;
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
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.ApiKeyAuthFilter;
import ai.godance.security.JwtService;
import ai.godance.service.AuthService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.ChangeEmailRequest;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.MeResponse;
import ai.godance.web.dto.AuthDtos.ReleaseEmailRequest;
import ai.godance.web.dto.AuthDtos.ReleaseLinkRequest;
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
    public Mono<ResponseEntity<Void>> verify(
            @RequestParam("token") String token, @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = sessionUserId(jwt);
        return Mono.fromRunnable(() -> auth.verifyEmail(token, userId, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PostMapping("/verify/resend")
    public Mono<ResponseEntity<Void>> resendVerification(@AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = sessionUserId(jwt);
        return Mono.fromRunnable(() -> auth.resendVerificationEmail(userId, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    /** Add a first real address (X.com sign-in) or change the account's email. */
    @PostMapping("/email")
    public Mono<ResponseEntity<Void>> changeEmail(@AuthenticationPrincipal Jwt jwt,
            @Valid @RequestBody ChangeEmailRequest req, ServerWebExchange exchange) {
        UUID userId = sessionUserId(jwt);
        Instant signedInAt = jwt.getIssuedAt();
        return Mono.fromRunnable(() -> auth.requestEmailChange(userId, req.email(),
                        req.currentPassword(), signedInAt, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    /** From the verification email: free the address from an account that never verified it. */
    @PostMapping("/email/release")
    public Mono<ResponseEntity<Void>> releaseEmail(
            @Valid @RequestBody ReleaseEmailRequest req, ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> auth.releaseEmail(req.token(), clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    /** Re-send that email to an address held by an unverified account. Always 202. */
    @PostMapping("/email/release-link")
    public Mono<ResponseEntity<Void>> sendReleaseLink(
            @Valid @RequestBody ReleaseLinkRequest req, ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> auth.sendReleaseLink(req.email(), clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.accepted().build()));
    }

    @GetMapping("/me")
    public Mono<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> auth.loadById(userId))
                .subscribeOn(Schedulers.boundedElastic())
                .map(MeResponse::from);
    }

    /**
     * The caller's user id, refusing an API key. Which address the account
     * uses decides whose Google or Apple sign-in can link into it, so only
     * a signed-in session may change or confirm it; an API key is for the
     * model API.
     */
    private static UUID sessionUserId(Jwt jwt) {
        if (jwt.hasClaim(ApiKeyAuthFilter.API_KEY_ID_CLAIM)) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN,
                    "Sign in to manage your email address; API keys can't.");
        }
        return JwtService.userIdFromSubject(jwt.getSubject());
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
