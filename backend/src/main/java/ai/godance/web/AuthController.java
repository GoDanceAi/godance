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
import ai.godance.security.AuthRateLimits;
import ai.godance.security.JwtService;
import ai.godance.security.SessionCookies;
import ai.godance.service.AuthService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.AuthDtos.ChangeEmailRequest;
import ai.godance.web.dto.AuthDtos.ForgotPasswordRequest;
import ai.godance.web.dto.AuthDtos.LoginRequest;
import ai.godance.web.dto.AuthDtos.MeResponse;
import ai.godance.web.dto.AuthDtos.ReleaseEmailRequest;
import ai.godance.web.dto.AuthDtos.ReleaseLinkRequest;
import ai.godance.web.dto.AuthDtos.ResetPasswordRequest;
import ai.godance.web.dto.AuthDtos.SignupRequest;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/auth")
public class AuthController {

    private final AuthService auth;
    private final AuthRateLimits rateLimits;
    private final SessionCookies sessionCookies;

    public AuthController(AuthService auth, AuthRateLimits rateLimits, SessionCookies sessionCookies) {
        this.auth = auth;
        this.rateLimits = rateLimits;
        this.sessionCookies = sessionCookies;
    }

    @PostMapping("/signup")
    public Mono<ResponseEntity<AuthResponse>> signup(
            @Valid @RequestBody SignupRequest req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.signup(ip)
                .then(Mono.fromCallable(() -> auth.signup(req, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(session -> ResponseEntity.status(HttpStatus.CREATED).body(sessionCookies.start(exchange, session)));
    }

    @PostMapping("/login")
    public Mono<AuthResponse> login(
            @Valid @RequestBody LoginRequest req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.login(ip, req.email())
                .then(Mono.fromCallable(() -> auth.login(req, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(session -> sessionCookies.start(exchange, session));
    }

    /**
     * Sign out: clears the cookie and bumps the session version, which ends
     * this account's sessions on every device.
     */
    @PostMapping("/logout")
    public Mono<ResponseEntity<Void>> logout(@AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromRunnable(() -> auth.logout(userId, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.fromSupplier(() -> {
                    sessionCookies.clear(exchange.getResponse());
                    return ResponseEntity.noContent().build();
                }));
    }

    /**
     * Move a session from before the cookie into it, once, when that browser
     * first loads an app that uses the cookie ({@code lib/legacy-session.ts}).
     * The same JWT with the same expiry: reissuing would let a stolen token
     * renew itself for ever. Goes away once no client gets a token in the
     * response ({@link SessionCookies#start}).
     */
    @PostMapping("/session")
    public Mono<ResponseEntity<Void>> adoptSession(@AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        sessionCookies.set(exchange.getResponse(), jwt.getTokenValue(), jwt.getExpiresAt());
        return Mono.just(ResponseEntity.noContent().build());
    }

    @GetMapping("/verify")
    public Mono<ResponseEntity<Void>> verify(@AuthenticationPrincipal Jwt jwt,
            @RequestParam("token") String token, ServerWebExchange exchange) {
        UUID userId = sessionUserId(jwt);
        String ip = clientIp(exchange);
        return rateLimits.verify(ip)
                .then(Mono.fromRunnable(() -> auth.verifyEmail(userId, token, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    @PostMapping("/verify/resend")
    public Mono<ResponseEntity<Void>> resendVerification(@AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = sessionUserId(jwt);
        String ip = clientIp(exchange);
        return rateLimits.resendVerification(ip, userId)
                .then(Mono.fromRunnable(() -> auth.resendVerificationEmail(userId, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
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

    /**
     * Re-send that email to an address held by an unverified account. Always
     * 202; over a rate limit the email is just not sent.
     */
    @PostMapping("/email/release-link")
    public Mono<ResponseEntity<Void>> sendReleaseLink(
            @Valid @RequestBody ReleaseLinkRequest req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.releaseLink(ip, req.email())
                .filter(Boolean::booleanValue)
                .flatMap(allowed -> Mono.fromRunnable(() -> auth.sendReleaseLink(req.email(), ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(Mono.just(ResponseEntity.accepted().build()));
    }

    /**
     * Always 202, so the response never says whether the address is
     * registered. Over a rate limit the email is just not sent.
     */
    @PostMapping("/password/forgot")
    public Mono<ResponseEntity<Void>> forgotPassword(
            @Valid @RequestBody ForgotPasswordRequest req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.forgotPassword(ip, req.email())
                .filter(Boolean::booleanValue)
                .flatMap(allowed -> Mono.fromRunnable(() -> auth.requestPasswordReset(req, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(Mono.just(ResponseEntity.accepted().build()));
    }

    /**
     * Every session ended with the old password, this browser's included, so
     * the cookie goes and a cookie client signs in with the new password,
     * and its code when two-factor sign-in is on. A client without the cookie
     * still gets what it has always got: a token, or that challenge.
     */
    @PostMapping("/password/reset")
    public Mono<ResponseEntity<AuthResponse>> resetPassword(
            @Valid @RequestBody ResetPasswordRequest req, ServerWebExchange exchange) {
        return Mono.fromCallable(() -> auth.resetPassword(req, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(session -> {
                    sessionCookies.clear(exchange.getResponse());
                    return SessionCookies.isCookieClient(exchange.getRequest())
                            ? ResponseEntity.noContent().<AuthResponse>build()
                            : ResponseEntity.ok(session);
                });
    }

    @GetMapping("/me")
    public Mono<MeResponse> me(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> auth.me(userId))
                .subscribeOn(Schedulers.boundedElastic());
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
