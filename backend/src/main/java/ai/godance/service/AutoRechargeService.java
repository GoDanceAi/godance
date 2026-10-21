package ai.godance.service;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;

import ai.godance.config.BillingProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService.TopUpResult;
import ai.godance.service.mail.MailService;

/**
 * Manages the off-session auto-recharge loop:
 *
 * <ol>
 *   <li>User enables: must already have a saved {@code stripe_payment_method_id}
 *       (captured during a prior interactive top-up with
 *       {@code setup_future_usage=off_session}).</li>
 *   <li>{@link CreditLedgerService} debits → if balance crosses below the
 *       configured threshold AND auto-recharge is enabled, a one-shot Quartz
 *       job is enqueued that calls {@link #chargeIfNeeded(UUID)} on the
 *       scheduler thread (off the request-serving path).</li>
 *   <li>{@link #chargeIfNeeded} re-checks the threshold (the user may have
 *       been topped up another way in the meantime), then creates a Stripe
 *       PaymentIntent with {@code confirm=true, off_session=true}. On
 *       success → credit ledger via {@code topUp}. On any hard failure
 *       (card declined, SCA required, expired card) → disable auto-recharge
 *       + audit-log so the user has to re-engage. A card that can never be
 *       charged again (expired, lost, stolen) is also dropped, so the user
 *       can't re-enable on it.</li>
 * </ol>
 */
@Service
public class AutoRechargeService {

    private static final Logger log = LoggerFactory.getLogger(AutoRechargeService.class);

    /**
     * Stripe error and decline codes after which the card can never be
     * charged again: it has expired, or the issuer has cancelled it. Any
     * other failure (insufficient_funds, generic_decline,
     * authentication_required, ...) may clear without a new card, so the
     * card stays on file.
     */
    private static final Set<String> UNRECOVERABLE_CARD_CODES = Set.of(
            "expired_card", "lost_card", "stolen_card", "pickup_card");

    private final UserRepository users;
    private final CreditLedgerService ledger;
    private final StripeService stripe;
    private final AuditLogService audit;
    private final BillingProperties billing;
    private final MailService mail;

    public AutoRechargeService(UserRepository users, CreditLedgerService ledger,
                               StripeService stripe, AuditLogService audit,
                               BillingProperties billing, MailService mail) {
        this.users = users;
        this.ledger = ledger;
        this.stripe = stripe;
        this.audit = audit;
        this.billing = billing;
        this.mail = mail;
    }

    @Transactional(readOnly = true)
    public Config currentConfig(UUID userId) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        return Config.from(u);
    }

    /**
     * Enable + configure (or update) auto-recharge. Requires a saved payment
     * method; {@code thresholdCents} and {@code topupCents} must be inside
     * the platform top-up bounds.
     */
    @Transactional
    public Config enable(UUID userId, long thresholdCents, long topupCents, String ip) {
        if (topupCents < billing.topupMinCents() || topupCents > billing.topupMaxCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "topup_cents must be between " + billing.topupMinCents()
                            + " and " + billing.topupMaxCents());
        }
        if (thresholdCents < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "threshold_cents must be ≥ 0");
        }
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        if (u.getStripePaymentMethodId() == null || u.getStripeCustomerId() == null) {
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "no saved payment method on file — run a top-up with save_card=true first");
        }
        u.setAutoRechargeEnabled(true);
        u.setAutoRechargeThresholdCents(thresholdCents);
        u.setAutoRechargeTopupCents(topupCents);
        // Clear stale failure breadcrumb; user is explicitly re-enabling.
        u.setAutoRechargeLastFailureAt(null);
        u.setAutoRechargeLastFailureReason(null);
        users.save(u);
        audit.record(AuditLogService.EVT_BILLING_AUTORECHARGE_ENABLED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("thresholdCents", thresholdCents, "topupCents", topupCents));
        return Config.from(u);
    }

    @Transactional
    public Config disable(UUID userId, String ip, String reason) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        if (!u.isAutoRechargeEnabled()) return Config.from(u);
        u.setAutoRechargeEnabled(false);
        users.save(u);
        audit.record(AuditLogService.EVT_BILLING_AUTORECHARGE_DISABLED, AuditOutcome.SUCCESS, userId, ip,
                Map.of("reason", reason == null ? "user" : reason));
        return Config.from(u);
    }

    /**
     * Charge the saved card if auto-recharge is enabled and the balance is
     * still below the configured threshold. Idempotent on best-effort —
     * concurrent calls for the same user with overlapping wall-clock minutes
     * share the same Stripe idempotency key. Returns the outcome for caller
     * visibility (debit-trigger doesn't care; the cron logs the count).
     *
     * <p>Hard failure policy: any {@link StripeException} disables
     * auto-recharge in the same transaction. The user has to re-enable
     * (which presumably means updating their card first via a new
     * interactive top-up). This matches "disable after 1 hard fail".
     */
    @Transactional
    public Outcome chargeIfNeeded(UUID userId) {
        User u = users.findById(userId).orElse(null);
        if (u == null || !u.isAutoRechargeEnabled())   return Outcome.SKIPPED_DISABLED;
        if (u.getStripePaymentMethodId() == null)      return Outcome.SKIPPED_NO_PM;
        Long threshold = u.getAutoRechargeThresholdCents();
        Long topup     = u.getAutoRechargeTopupCents();
        if (threshold == null || topup == null)        return Outcome.SKIPPED_NO_CONFIG;
        if (u.getCreditBalanceCents() >= threshold)    return Outcome.SKIPPED_ABOVE_THRESHOLD;

        String idem = idempotencyKey(u, topup);
        try {
            PaymentIntent pi = stripe.chargeOffSession(
                    u.getStripeCustomerId(), u.getStripePaymentMethodId(), topup, idem);
            // Stripe returned without throwing — status should be "succeeded".
            // "requires_action" (SCA) for off-session charges throws StripeException;
            // anything else non-succeeded we treat as hard fail.
            if (!"succeeded".equals(pi.getStatus())) {
                return hardFail(u, "payment_intent.status=" + pi.getStatus());
            }
            // Idempotency key combines userId + PI id — defends against the
            // (unlikely-but-possible) case where Stripe reuses a PI id across
            // users, and keeps tests with shared stubs honest.
            String ledgerIdem = "autorecharge-" + userId + "-" + pi.getId();
            TopUpResult result = ledger.topUp(
                    userId, topup, CreditSource.STRIPE, pi.getId(), ledgerIdem,
                    "Auto-recharge $" + (topup / 100.0));
            audit.record(AuditLogService.EVT_BILLING_AUTORECHARGE_FIRED, AuditOutcome.SUCCESS,
                    userId, null, Map.of(
                            "topupCents", topup,
                            "paymentIntentId", pi.getId(),
                            "ledger", result.name()));
            return Outcome.CHARGED;
        } catch (StripeException e) {
            String declineCode = e instanceof CardException card ? card.getDeclineCode() : null;
            if (isUnrecoverableCardCode(e.getCode()) || isUnrecoverableCardCode(declineCode)) {
                dropUnrecoverableCard(u, e.getCode(), declineCode);
            }
            return hardFail(u, e.getCode() == null ? e.getMessage() : e.getCode() + ": " + e.getMessage());
        }
    }

    private static boolean isUnrecoverableCardCode(String code) {
        return code != null && UNRECOVERABLE_CARD_CODES.contains(code);
    }

    /**
     * Forget a saved card Stripe says can never be charged again, so
     * {@link #enable} answers 409 until a {@code save_card=true} top-up
     * saves a new one. {@link #hardFail} persists the change. The failure
     * reason the user sees keeps Stripe's generic wording: Stripe advises
     * against telling them a card was reported lost or stolen.
     */
    private void dropUnrecoverableCard(User u, String code, String declineCode) {
        String pmId = u.getStripePaymentMethodId();
        log.info("dropping saved card {} for user {}: {} / {}", pmId, u.getId(), code, declineCode);
        u.setStripePaymentMethodId(null);
        audit.record(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REMOVED, AuditOutcome.SUCCESS,
                u.getId(), null, Map.of(
                        "paymentMethodId", pmId,
                        "code", String.valueOf(code),
                        "declineCode", String.valueOf(declineCode)));
    }

    private Outcome hardFail(User u, String reason) {
        log.warn("auto-recharge hard fail for user {}: {}", u.getId(), reason);
        u.setAutoRechargeEnabled(false);
        u.setAutoRechargeLastFailureAt(Instant.now());
        u.setAutoRechargeLastFailureReason(reason);
        users.save(u);
        audit.record(AuditLogService.EVT_BILLING_AUTORECHARGE_FAILED, AuditOutcome.FAILURE,
                u.getId(), null, Map.of("reason", reason));
        // Mail failures are swallowed inside MailService — never propagated here.
        mail.sendAutoRechargeFailed(u, reason);
        return Outcome.HARD_FAIL_DISABLED;
    }

    /**
     * Stripe idempotency key. Floored to the minute so the debit-trigger
     * and the cron safety net can't double-charge if they race within the
     * same window.
     */
    private static String idempotencyKey(User u, long topup) {
        long minute = Instant.now().getEpochSecond() / 60;
        return "autorecharge-" + u.getId() + "-" + topup + "-" + minute;
    }

    public enum Outcome {
        CHARGED,
        SKIPPED_DISABLED,
        SKIPPED_NO_PM,
        SKIPPED_NO_CONFIG,
        SKIPPED_ABOVE_THRESHOLD,
        HARD_FAIL_DISABLED
    }

    public record Config(
            boolean enabled,
            Long thresholdCents,
            Long topupCents,
            boolean paymentMethodOnFile,
            Instant lastFailureAt,
            String lastFailureReason
    ) {
        public static Config from(User u) {
            return new Config(
                    u.isAutoRechargeEnabled(),
                    u.getAutoRechargeThresholdCents(),
                    u.getAutoRechargeTopupCents(),
                    u.getStripePaymentMethodId() != null,
                    u.getAutoRechargeLastFailureAt(),
                    u.getAutoRechargeLastFailureReason());
        }
    }
}
