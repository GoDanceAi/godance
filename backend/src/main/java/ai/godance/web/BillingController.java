package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;

import ai.godance.security.JwtService;
import ai.godance.service.AutoRechargeService;
import ai.godance.service.BillingService;
import ai.godance.service.BillingService.TopUpIntent;
import ai.godance.web.dto.BillingDtos.AutoRechargeConfigRequest;
import ai.godance.web.dto.BillingDtos.AutoRechargeConfigView;
import ai.godance.web.dto.BillingDtos.TopUpRequest;
import ai.godance.web.dto.BillingDtos.TopUpResponse;
import jakarta.validation.Valid;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/v1/billing")
public class BillingController {

    private final BillingService billing;
    private final AutoRechargeService autoRecharge;

    public BillingController(BillingService billing, AutoRechargeService autoRecharge) {
        this.billing = billing;
        this.autoRecharge = autoRecharge;
    }

    /** Mint a Stripe Checkout Session for a one-shot credit top-up. */
    @PostMapping("/topup")
    public Mono<ResponseEntity<TopUpResponse>> topup(
            @Valid @RequestBody TopUpRequest req,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> billing.startTopUp(
                        userId, req.amountCents(), req.saveCardOrFalse(), clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(intent -> ResponseEntity.status(HttpStatus.CREATED).body(toResponse(intent)));
    }

    /**
     * Stripe webhook receiver. Body is bound as raw String — re-parsing the
     * JSON would change byte ordering and break the HMAC signature check.
     * 200 on accepted (including dedup); 400 on invalid signature so Stripe
     * surfaces the failure in the dashboard.
     */
    @PostMapping("/webhooks/stripe")
    public Mono<ResponseEntity<Void>> stripeWebhook(
            @RequestBody String payload,
            @RequestHeader("Stripe-Signature") String signature,
            ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> billing.handleWebhook(payload, signature, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.<Void>ok().build()));
    }

    /** NOWPayments crypto (USDT) equivalent of {@link #topup}. Returns the hosted-payment URL. */
    @PostMapping("/nowpayments/topup")
    public Mono<ResponseEntity<TopUpResponse>> nowPaymentsTopup(
            @Valid @RequestBody TopUpRequest req,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> billing.startNowPaymentsTopUp(userId, req.amountCents(), clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .map(intent -> ResponseEntity.status(HttpStatus.CREATED).body(toResponse(intent)));
    }

    /**
     * NOWPayments IPN receiver. Same raw-body invariant as Stripe — the
     * signature is HMAC-SHA512 over the (sorted-JSON) payload. 200 on
     * accept/dedup; 400 on bad signature.
     */
    @PostMapping("/webhooks/nowpayments")
    public Mono<ResponseEntity<Void>> nowPaymentsWebhook(
            @RequestBody String payload,
            @RequestHeader("x-nowpayments-sig") String signature,
            ServerWebExchange exchange) {
        return Mono.fromRunnable(() -> billing.handleNowPaymentsWebhook(payload, signature, clientIp(exchange)))
                .subscribeOn(Schedulers.boundedElastic())
                .then(Mono.just(ResponseEntity.<Void>ok().build()));
    }

    // ===== Auto-recharge ============================================

    @GetMapping("/auto-recharge")
    public Mono<AutoRechargeConfigView> getAutoRecharge(@AuthenticationPrincipal Jwt jwt) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> toView(autoRecharge.currentConfig(userId)))
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Enable / update / disable the user's auto-recharge config. PUT (not
     * POST) — same resource, idempotent. {@code enabled=false} clears the
     * flag; threshold + topup amounts are ignored in that case.
     */
    @PutMapping("/auto-recharge")
    public Mono<AutoRechargeConfigView> putAutoRecharge(
            @Valid @RequestBody AutoRechargeConfigRequest req,
            @AuthenticationPrincipal Jwt jwt,
            ServerWebExchange exchange) {
        UUID userId = JwtService.userIdFromSubject(jwt.getSubject());
        return Mono.fromCallable(() -> {
                    AutoRechargeService.Config cfg;
                    if (!req.enabled()) {
                        cfg = autoRecharge.disable(userId, clientIp(exchange), "user");
                    } else {
                        if (req.thresholdCents() == null || req.topupCents() == null) {
                            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                                    "threshold_cents and topup_cents are required when enabled=true");
                        }
                        cfg = autoRecharge.enable(userId, req.thresholdCents(),
                                req.topupCents(), clientIp(exchange));
                    }
                    return toView(cfg);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private static AutoRechargeConfigView toView(AutoRechargeService.Config c) {
        return new AutoRechargeConfigView(c.enabled(), c.thresholdCents(), c.topupCents(),
                c.paymentMethodOnFile(), c.lastFailureAt(), c.lastFailureReason());
    }

    private static TopUpResponse toResponse(TopUpIntent intent) {
        return new TopUpResponse(intent.sessionId(), intent.url(), intent.amountCents());
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
