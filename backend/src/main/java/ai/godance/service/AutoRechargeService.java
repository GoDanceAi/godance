package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.exception.ApiConnectionException;
import com.stripe.exception.CardException;
import com.stripe.exception.StripeException;
import com.stripe.model.PaymentIntent;

import ai.godance.config.BillingProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.AutoRechargeAttempt;
import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.AutoRechargeAttemptRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService.TopUpResult;
import ai.godance.service.mail.MailService;

/**
 * Manages the off-session auto-recharge loop:
 *
 * <ol>
 *   <li>User enables: must already have a saved {@code stripe_payment_method_id}
 *       (captured during a prior interactive top-up with
 *       {@code setup_future_usage=off_session}), and it must be a card.
 *       Bank debits (ACH, SEPA) settle days after confirming, so an
 *       off-session charge on one can't be credited when it's made.</li>
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

    /**
     * How long a PENDING attempt with no PaymentIntent at Stripe is left
     * alone: longer than the SDK's request timeout, so the first request
     * can't still be in flight when the attempt is retired.
     */
    static final Duration UNCONFIRMED_GRACE = Duration.ofMinutes(5);

    private final UserRepository users;
    private final CreditLedgerService ledger;
    private final StripeService stripe;
    private final AuditLogService audit;
    private final BillingProperties billing;
    private final MailService mail;
    private final AutoRechargeAttemptRepository attempts;
    private final AutoRechargeAttempts attemptLog;

    public AutoRechargeService(UserRepository users, CreditLedgerService ledger,
                               StripeService stripe, AuditLogService audit,
                               BillingProperties billing, MailService mail,
                               AutoRechargeAttemptRepository attempts, AutoRechargeAttempts attemptLog) {
        this.users = users;
        this.ledger = ledger;
        this.stripe = stripe;
        this.audit = audit;
        this.billing = billing;
        this.mail = mail;
        this.attempts = attempts;
        this.attemptLog = attemptLog;
    }

    @Transactional(readOnly = true)
    public Config currentConfig(UUID userId) {
        User u = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        return Config.from(u);
    }

    /**
     * Enable + configure (or update) auto-recharge. Requires a saved card;
     * {@code thresholdCents} and {@code topupCents} must be inside the
     * platform top-up bounds. A saved payment method that isn't a card
     * (saved before cards-only was enforced) is dropped and the call
     * answers 409, so the user's next card top-up can save a card.
     */
    @Transactional(noRollbackFor = PaymentMethodNotCardException.class)
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
        String pmType;
        try {
            pmType = stripe.retrievePaymentMethodType(u.getStripePaymentMethodId());
        } catch (StripeException e) {
            log.warn("could not retrieve payment_method {} for user {}: {}",
                    u.getStripePaymentMethodId(), userId, e.getMessage());
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment provider unavailable");
        }
        if (!StripeService.isCard(pmType)) {
            dropNonCardPaymentMethod(u, pmType, ip);
            throw new PaymentMethodNotCardException(pmType);
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
     * still below the configured threshold, and credit the ledger exactly
     * once for each charge (spec risk R-04).
     *
     * <p>An {@link AutoRechargeAttempt} is committed before the charge is
     * sent; its id is the Stripe idempotency key and the PaymentIntent's
     * metadata. The credit and the attempt's resolution commit together, so
     * a crash or rollback after the charge leaves the attempt PENDING. A
     * PENDING attempt is settled before anything new is charged: from the
     * next run here, or from the {@code payment_intent.succeeded} webhook
     * ({@link #reconcileAttempt}), by finding its PaymentIntent. One that
     * never reached Stripe is retired after {@link #UNCONFIRMED_GRACE}.
     *
     * <p>When Stripe's answer is unknown (a network failure, a 5xx, a 429)
     * nothing is disabled: the attempt stays PENDING and the outcome is
     * {@link Outcome#RETRY_LATER}. Any definite {@link StripeException} is a
     * hard failure and disables auto-recharge in the same transaction. The
     * user has to re-enable (which presumably means updating their card
     * first via a new interactive top-up). This matches "disable after 1
     * hard fail".
     *
     * <p>A saved payment method that isn't a card is never charged: it is
     * dropped and auto-recharge is disabled the same way, before any
     * PaymentIntent is created.
     */
    @Transactional
    public Outcome chargeIfNeeded(UUID userId) {
        AutoRechargeAttempt pending = attempts
                .findByUserIdAndStatus(userId, AutoRechargeAttempt.Status.PENDING).orElse(null);
        if (pending != null) {
            Outcome resumed = resume(pending);
            if (resumed != null) return resumed;
            // The pending attempt never reached Stripe and was retired; charge afresh.
        }

        User u = users.findById(userId).orElse(null);
        if (u == null || !u.isAutoRechargeEnabled())   return Outcome.SKIPPED_DISABLED;
        if (u.getStripePaymentMethodId() == null)      return Outcome.SKIPPED_NO_PM;
        Long threshold = u.getAutoRechargeThresholdCents();
        Long topup     = u.getAutoRechargeTopupCents();
        if (threshold == null || topup == null)        return Outcome.SKIPPED_NO_CONFIG;
        if (u.getCreditBalanceCents() >= threshold)    return Outcome.SKIPPED_ABOVE_THRESHOLD;

        try {
            // Users who enabled before cards-only was enforced may have a bank
            // debit saved. chargeOffSession only accepts cards too, but drop
            // the method here so enable() refuses until the user saves a card.
            String pmType = stripe.retrievePaymentMethodType(u.getStripePaymentMethodId());
            if (!StripeService.isCard(pmType)) {
                dropNonCardPaymentMethod(u, pmType, null);
                return hardFail(u, "payment_method.type=" + pmType + ": auto-recharge needs a card");
            }
        } catch (StripeException e) {
            if (isOutcomeUnknown(e)) {
                log.warn("auto-recharge for user {}: Stripe unavailable ({}); will retry", userId, e.getMessage());
                return Outcome.RETRY_LATER;
            }
            return hardFail(u, e.getCode() == null ? e.getMessage() : e.getCode() + ": " + e.getMessage());
        }

        AutoRechargeAttempt attempt;
        try {
            attempt = attemptLog.begin(userId, topup);
        } catch (DataIntegrityViolationException anotherRunHasOne) {
            return Outcome.SKIPPED_IN_PROGRESS;
        }
        return charge(u, attempt);
    }

    /**
     * Credits the charge a PENDING attempt made. Called from the
     * {@code payment_intent.succeeded} webhook; does nothing for an attempt
     * that is unknown or already resolved.
     */
    @Transactional
    public void reconcileAttempt(UUID attemptId) {
        attempts.findById(attemptId)
                .filter(a -> a.getStatus() == AutoRechargeAttempt.Status.PENDING)
                .ifPresent(this::resume);
    }

    /** Sends the attempt's charge and settles it, unless Stripe's answer is unknown. */
    private Outcome charge(User u, AutoRechargeAttempt attempt) {
        PaymentIntent pi;
        try {
            pi = stripe.chargeOffSession(u.getStripeCustomerId(), u.getStripePaymentMethodId(),
                    attempt.getAmountCents(), attempt.idempotencyKey(), attempt.getId());
        } catch (StripeException e) {
            if (isOutcomeUnknown(e)) {
                // The card may have been charged. The attempt stays PENDING, so
                // the next run finds the charge instead of making another.
                log.warn("auto-recharge attempt {} for user {}: outcome unknown ({}); will reconcile",
                        attempt.getId(), u.getId(), e.getMessage());
                return Outcome.RETRY_LATER;
            }
            String declineCode = e instanceof CardException card ? card.getDeclineCode() : null;
            if (isUnrecoverableCardCode(e.getCode()) || isUnrecoverableCardCode(declineCode)) {
                dropUnrecoverableCard(u, e.getCode(), declineCode);
            }
            String reason = e.getCode() == null ? e.getMessage() : e.getCode() + ": " + e.getMessage();
            resolve(attempt, AutoRechargeAttempt.Status.FAILED, null, reason);
            return hardFail(u, reason);
        }
        return settle(u, attempt, pi);
    }

    /**
     * Credits a succeeded PaymentIntent once and resolves the attempt with
     * it, in the caller's transaction; any other status is a hard failure.
     */
    private Outcome settle(User u, AutoRechargeAttempt attempt, PaymentIntent pi) {
        // "requires_action" (SCA) for off-session charges throws StripeException;
        // anything else non-succeeded we treat as hard fail.
        if (!"succeeded".equals(pi.getStatus())) {
            String reason = "payment_intent.status=" + pi.getStatus();
            resolve(attempt, AutoRechargeAttempt.Status.FAILED, pi.getId(), reason);
            return hardFail(u, reason);
        }
        // Idempotency key combines userId + PI id — defends against the
        // (unlikely-but-possible) case where Stripe reuses a PI id across
        // users, and keeps tests with shared stubs honest.
        String ledgerIdem = "autorecharge-" + u.getId() + "-" + pi.getId();
        TopUpResult result = ledger.topUp(
                u.getId(), attempt.getAmountCents(), CreditSource.STRIPE, pi.getId(), ledgerIdem,
                "Auto-recharge $" + (attempt.getAmountCents() / 100.0));
        resolve(attempt, AutoRechargeAttempt.Status.SUCCEEDED, pi.getId(), null);
        audit.record(AuditLogService.EVT_BILLING_AUTORECHARGE_FIRED, AuditOutcome.SUCCESS,
                u.getId(), null, Map.of(
                        "topupCents", attempt.getAmountCents(),
                        "paymentIntentId", pi.getId(),
                        "attemptId", attempt.getId().toString(),
                        "ledger", result.name()));
        return Outcome.CHARGED;
    }

    /**
     * Settles a PENDING attempt from what Stripe holds for it. Returns null
     * when Stripe has no charge for it and none can still arrive: the attempt
     * is then retired and the caller may make a new one.
     */
    private Outcome resume(AutoRechargeAttempt attempt) {
        User u = users.findById(attempt.getUserId()).orElse(null);
        if (u == null || u.getStripeCustomerId() == null) {
            return Outcome.RETRY_LATER;
        }
        PaymentIntent pi;
        try {
            pi = stripe.findPaymentIntentForAttempt(
                    u.getStripeCustomerId(), attempt.getId(), attempt.getCreatedAt());
        } catch (StripeException e) {
            log.warn("auto-recharge attempt {}: could not look up its charge ({}); will retry",
                    attempt.getId(), e.getMessage());
            return Outcome.RETRY_LATER;
        }
        if (pi != null) {
            return settle(u, attempt, pi);
        }
        if (attempt.getCreatedAt().isAfter(Instant.now().minus(UNCONFIRMED_GRACE))) {
            return Outcome.RETRY_LATER;   // the first request may still be in flight
        }
        attemptLog.retire(attempt.getId(), "no charge reached Stripe");
        return null;
    }

    private void resolve(AutoRechargeAttempt attempt, AutoRechargeAttempt.Status status,
                         String paymentIntentId, String failureReason) {
        attempt.setStatus(status);
        attempt.setPaymentIntentId(paymentIntentId);
        attempt.setFailureReason(failureReason);
        attempt.setResolvedAt(Instant.now());
        attempts.save(attempt);
    }

    /**
     * True when Stripe may not have answered for the request: the network
     * failed, Stripe answered 5xx, or it asked us to slow down (429). None
     * of these says the card was declined, so none disables auto-recharge.
     */
    static boolean isOutcomeUnknown(StripeException e) {
        if (e instanceof ApiConnectionException) return true;
        Integer status = e.getStatusCode();
        return status != null && (status >= 500 || status == 429);
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
     * Forget a saved payment method that isn't a card, so {@link #enable}
     * answers 409 until the user's next card top-up with
     * {@code save_card=true} saves a card (which would also replace it).
     */
    private void dropNonCardPaymentMethod(User u, String pmType, String ip) {
        String pmId = u.getStripePaymentMethodId();
        log.warn("dropping saved {} payment method {} for user {}: auto-recharge charges cards only",
                pmType, pmId, u.getId());
        u.setStripePaymentMethodId(null);
        users.save(u);
        audit.record(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REJECTED, AuditOutcome.DENIED,
                u.getId(), ip, Map.of(
                        "paymentMethodId", pmId,
                        "paymentMethodType", String.valueOf(pmType)));
    }

    /**
     * 409: the saved payment method isn't a card. {@link #enable} drops the
     * method before throwing this, and that must not roll back.
     */
    static final class PaymentMethodNotCardException extends ResponseStatusException {
        PaymentMethodNotCardException(String pmType) {
            super(HttpStatus.CONFLICT, "saved payment method is " + pmType
                    + ", not a card — auto-recharge charges cards only; run a top-up with a card"
                    + " and save_card=true first");
        }
    }

    public enum Outcome {
        CHARGED,
        /** Nothing settled now; Stripe's answer is unknown or unavailable. The next run settles it. */
        RETRY_LATER,
        /** Another run holds this user's pending attempt. */
        SKIPPED_IN_PROGRESS,
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
