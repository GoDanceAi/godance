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
import ai.godance.service.ParentalConsentService;
import ai.godance.web.dto.BillingDtos.TopUpResponse;
import ai.godance.web.dto.ParentalConsentDtos.ParentalConsentCheckoutRequest;
import ai.godance.web.dto.ParentalConsentDtos.ParentalConsentView;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Parental consent for a child's account. The first two endpoints are the
 * parent's, and public: the token from the email is the proof. The third
 * is the waiting child's (the {@code PENDING_CONSENT} scope).
 */
@RestController
@RequestMapping("/v1/auth/parental-consent")
public class ParentalConsentController {

    private final ParentalConsentService consent;
    private final AuthRateLimits rateLimits;

    public ParentalConsentController(ParentalConsentService consent, AuthRateLimits rateLimits) {
        this.consent = consent;
        this.rateLimits = rateLimits;
    }

    /** What the consent page shows. 404 for an unknown or used link, 410 for an expired one. */
    @GetMapping
    public Mono<ParentalConsentView> view(@RequestParam("token") String token,
                                          ServerWebExchange exchange) {
        return rateLimits.parentalConsent(clientIp(exchange))
                .then(Mono.fromCallable(() -> consent.view(token))
                        .subscribeOn(Schedulers.boundedElastic()));
    }

    /** Start the Checkout Session the parent pays to approve; the client redirects to its URL. */
    @PostMapping("/checkout")
    public Mono<ResponseEntity<TopUpResponse>> checkout(
            @Valid @RequestBody ParentalConsentCheckoutRequest req, ServerWebExchange exchange) {
        String ip = clientIp(exchange);
        return rateLimits.parentalConsent(ip)
                .then(Mono.fromCallable(() -> consent.startCheckout(req.token(), req.amountCents(), ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .map(intent -> ResponseEntity.status(HttpStatus.CREATED).body(
                        new TopUpResponse(intent.sessionId(), intent.url(), intent.amountCents())));
    }

    /** The waiting child sends the email to their parent again, with a new link. */
    @PostMapping("/resend")
    public Mono<ResponseEntity<Void>> resend(@AuthenticationPrincipal Jwt jwt, ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        String ip = clientIp(exchange);
        return rateLimits.resendParentalConsent(ip, userId)
                .then(Mono.fromRunnable(() -> consent.resend(userId, ip))
                        .subscribeOn(Schedulers.boundedElastic()))
                .then(Mono.just(ResponseEntity.noContent().build()));
    }

    private static String clientIp(ServerWebExchange exchange) {
        var remote = exchange.getRequest().getRemoteAddress();
        if (remote == null) return null;
        var addr = remote.getAddress();
        // Behind a reverse proxy the remote address can be UNRESOLVED.
        return addr != null ? addr.getHostAddress() : remote.getHostString();
    }
}
