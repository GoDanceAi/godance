package ai.godance.service;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;

import ai.godance.config.BillingProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService.TopUpResult;
import ai.godance.service.CryptoPaymentRail.CryptoInvoice;
import ai.godance.service.NowPaymentsService.InvalidSignatureException;
import ai.godance.service.NowPaymentsService.NowPaymentsIpn;
import ai.godance.service.mail.MailService;

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final String EVENT_CHECKOUT_COMPLETED = "checkout.session.completed";
    private static final String EVENT_ASYNC_PAYMENT_SUCCEEDED = "checkout.session.async_payment_succeeded";
    private static final String EVENT_ASYNC_PAYMENT_FAILED = "checkout.session.async_payment_failed";
    private static final String EVENT_PAYMENT_INTENT_SUCCEEDED = "payment_intent.succeeded";
    private static final Set<String> CHECKOUT_EVENTS = Set.of(
            EVENT_CHECKOUT_COMPLETED, EVENT_ASYNC_PAYMENT_SUCCEEDED, EVENT_ASYNC_PAYMENT_FAILED);
    /** Checkout Session {@code payment_status} values (the third is {@code no_payment_required}). */
    private static final String PAYMENT_STATUS_PAID = "paid";
    private static final String PAYMENT_STATUS_UNPAID = "unpaid";

    private final StripeService stripe;
    private final NowPaymentsService nowPayments;
    private final CreditLedgerService ledger;
    private final UserRepository users;
    private final AuditLogService audit;
    private final BillingProperties billing;
    private final MailService mail;
    private final AutoRechargeService autoRecharge;
    private final ParentalConsentService parentalConsent;

    public BillingService(StripeService stripe, NowPaymentsService nowPayments,
                          CreditLedgerService ledger, UserRepository users,
                          AuditLogService audit, BillingProperties billing,
                          MailService mail, AutoRechargeService autoRecharge,
                          ParentalConsentService parentalConsent) {
        this.stripe = stripe;
        this.nowPayments = nowPayments;
        this.ledger = ledger;
        this.users = users;
        this.audit = audit;
        this.billing = billing;
        this.mail = mail;
        this.autoRecharge = autoRecharge;
        this.parentalConsent = parentalConsent;
    }

    /**
     * Mint a Stripe Checkout Session for {@code amountCents}. Returns the
     * hosted-checkout URL; client redirects there.
     *
     * <p>When {@code saveCard} is true, the card is captured with
     * {@code setup_future_usage=off_session} so future auto-recharge
     * PaymentIntents can charge it without the user present.
     */
    @Transactional
    public TopUpIntent startTopUp(UUID userId, long amountCents, boolean saveCard, String ip) {
        if (amountCents < billing.topupMinCents() || amountCents > billing.topupMaxCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amountCents must be between " + billing.topupMinCents()
                            + " and " + billing.topupMaxCents());
        }
        User user = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));

        try {
            String customerId = stripe.ensureCustomer(user);
            if (user.getStripeCustomerId() == null) {
                user.setStripeCustomerId(customerId);
                users.save(user);
            }
            Session session = stripe.createTopUpCheckout(user, customerId, amountCents, saveCard);

            audit.record(AuditLogService.EVT_BILLING_TOPUP_INTENT, AuditOutcome.SUCCESS, userId, ip,
                    Map.of("amountCents", amountCents, "sessionId", session.getId(),
                            "saveCard", saveCard));

            return new TopUpIntent(session.getId(), session.getUrl(), amountCents);
        } catch (StripeException e) {
            log.warn("Stripe checkout creation failed for user {}: {}", userId, e.getMessage());
            audit.record(AuditLogService.EVT_BILLING_TOPUP_INTENT, AuditOutcome.FAILURE, userId, ip,
                    Map.of("amountCents", amountCents, "error", e.getMessage()));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment provider unavailable");
        }
    }

    /**
     * Handle a verified Stripe webhook. Unknown event types are acked and
     * ignored, not errors. Throws 400 if the signature is invalid.
     *
     * <p>A top-up credits once its payment has actually succeeded: on
     * {@code checkout.session.completed} with {@code payment_status=paid}
     * (cards), or — for delayed-notification methods such as ACH Direct
     * Debit and SEPA Debit, whose session completes {@code unpaid} while
     * the bank transfer is pending — on the later
     * {@code checkout.session.async_payment_succeeded}.
     * {@code checkout.session.async_payment_failed} never credits.
     *
     * <p>A parent's approving session ({@link StripeService#isParentalConsent})
     * credits the child the same way, and the same paid events, and only
     * they, also grant the child's account parental consent.
     */
    @Transactional
    public void handleWebhook(String payload, String sigHeader, String ip) {
        Event event;
        try {
            event = stripe.verifyAndParse(payload, sigHeader);
        } catch (SignatureVerificationException e) {
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_REJECTED, AuditOutcome.DENIED,
                    null, ip, Map.of("reason", "bad-signature"));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid stripe signature");
        }

        String type = event.getType();
        if (EVENT_PAYMENT_INTENT_SUCCEEDED.equals(type) && reconcileAutoRecharge(event)) {
            return;
        }
        if (!CHECKOUT_EVENTS.contains(type)) {
            // Stripe will keep retrying on non-2xx, so explicitly succeed on
            // unknown events. We log for visibility but don't error.
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, AuditOutcome.SUCCESS,
                    null, ip, Map.of("eventType", type, "eventId", event.getId()));
            return;
        }

        Session session = checkoutSession(event, ip);
        log.info("stripe webhook {} eventId={} apiVersion={} sessionId={} paymentStatus={}",
                type, event.getId(), event.getApiVersion(), session.getId(), session.getPaymentStatus());

        UUID userId = userIdFromSession(session, type);
        switch (type) {
            case EVENT_CHECKOUT_COMPLETED -> {
                String paymentStatus = session.getPaymentStatus();
                if (PAYMENT_STATUS_PAID.equals(paymentStatus)) {
                    creditCheckoutSession(event, session, userId, ip);
                } else if (PAYMENT_STATUS_UNPAID.equals(paymentStatus)) {
                    // Funds still in flight; async_payment_succeeded / _failed settles it.
                    audit.record(AuditLogService.EVT_BILLING_TOPUP_PENDING, AuditOutcome.SUCCESS,
                            userId, ip, Map.of(
                                    "amountCents", amountOrZero(session),
                                    "eventId", event.getId(),
                                    "sessionId", session.getId()));
                } else {
                    // no_payment_required: nothing was collected (zero-amount
                    // session, or a payment deferred to a later date), so
                    // there is nothing to credit yet. Fail closed.
                    audit.record(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, AuditOutcome.SUCCESS,
                            userId, ip, Map.of(
                                    "eventId", event.getId(),
                                    "sessionId", session.getId(),
                                    "reason", "not-paid",
                                    "paymentStatus", String.valueOf(paymentStatus)));
                }
            }
            case EVENT_ASYNC_PAYMENT_SUCCEEDED -> creditCheckoutSession(event, session, userId, ip);
            case EVENT_ASYNC_PAYMENT_FAILED -> recordAsyncPaymentFailed(event, session, userId, ip);
            default -> throw new IllegalStateException("unhandled checkout event " + type);
        }
    }

    /**
     * The Checkout Session carried by {@code event}.
     *
     * <p>getObject() returns Optional.empty() when the event's Stripe API
     * version doesn't match the SDK's pinned version (e.g. live account
     * on 2026-04-22.dahlia, SDK 28.4.0 pinned to 2024-12-18.acacia).
     * The fields we actually read (id, metadata, client_reference_id,
     * amount_total, payment_status) are stable across versions, so fall
     * through to deserializeUnsafe() — Stripe themselves recommend this
     * for the skew case.
     */
    /**
     * Credits an auto-recharge charge that succeeded at Stripe while its
     * attempt is still PENDING here: the process crashed, the database
     * rolled back, or Stripe's answer never arrived (spec risk R-04). The
     * next charge run would find it too; this settles it at once. False
     * for a PaymentIntent with no attempt id, such as Checkout's, which its
     * checkout.session events credit.
     */
    private boolean reconcileAutoRecharge(Event event) {
        var deser = event.getDataObjectDeserializer();
        Object data = deser.getObject().orElse(null);
        if (data == null) {
            try {
                data = deser.deserializeUnsafe();
            } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
                log.warn("{} deserialize failed (eventId={}): {}", event.getType(), event.getId(), e.getMessage());
                return false;
            }
        }
        if (!(data instanceof PaymentIntent pi) || pi.getMetadata() == null) return false;
        String attemptId = pi.getMetadata().get(StripeService.ATTEMPT_METADATA_KEY);
        if (attemptId == null) return false;
        try {
            autoRecharge.reconcileAttempt(UUID.fromString(attemptId));
        } catch (IllegalArgumentException notOurs) {
            return false;
        }
        log.info("stripe webhook {} eventId={} reconciled auto-recharge attempt {}",
                event.getType(), event.getId(), attemptId);
        return true;
    }

    private Session checkoutSession(Event event, String ip) {
        var deser = event.getDataObjectDeserializer();
        Session session = (Session) deser.getObject().orElse(null);
        if (session != null) return session;
        try {
            return (Session) deser.deserializeUnsafe();
        } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
            log.warn("{} deserialize failed (eventId={} apiVersion={}): {}",
                    event.getType(), event.getId(), event.getApiVersion(), e.getMessage());
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_REJECTED, AuditOutcome.FAILURE,
                    null, ip, Map.of(
                            "reason", "deserialize-failed",
                            "eventType", event.getType(),
                            "eventId", event.getId(),
                            "apiVersion", String.valueOf(event.getApiVersion())));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "could not deserialize " + event.getType() + " payload");
        }
    }

    /**
     * Credit a Checkout Session whose payment has succeeded. Called from
     * both {@code checkout.session.completed} (paid) and
     * {@code checkout.session.async_payment_succeeded}, which carry
     * different event ids: the event-id idempotency key stops a replay of
     * one event, and the session-id {@code source_ref} check stops the
     * other event from crediting the same session a second time. That
     * check is a read, not a constraint — enough because Stripe never
     * makes both events creditworthy for one session, so they can't race.
     */
    private void creditCheckoutSession(Event event, Session session, UUID userId, String ip) {
        Long amount = session.getAmountTotal();
        if (amount == null || amount <= 0) {
            // Free / zero-amount sessions don't credit. Log and ack.
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, AuditOutcome.SUCCESS,
                    userId, ip, Map.of("eventId", event.getId(), "reason", "zero-amount"));
            return;
        }

        TopUpResult result = ledger.hasEntryFrom(userId, CreditSource.STRIPE, session.getId())
                ? TopUpResult.DUPLICATE
                : ledger.topUp(userId, amount, CreditSource.STRIPE, session.getId(), event.getId(),
                        "Stripe Checkout " + session.getId());

        String evtType = result == TopUpResult.APPLIED
                ? AuditLogService.EVT_BILLING_TOPUP_SUCCEEDED
                : AuditLogService.EVT_BILLING_TOPUP_DUPLICATE;
        audit.record(evtType, AuditOutcome.SUCCESS, userId, ip, Map.of(
                "amountCents", amount, "eventId", event.getId(), "sessionId", session.getId()));

        if (StripeService.isParentalConsent(session)) {
            // The parent's card paid: it is never saved on the child's account.
            // Granting is a no-op once granted, so a duplicate delivery is safe.
            parentalConsent.grantFromPayment(userId, session.getId(), event.getId(), amount, ip);
            return;
        }

        // If the session was created with setup_future_usage=off_session, the
        // PaymentIntent will have a payment_method we can charge later. Persist
        // it on the user, replacing any card saved by an earlier session. Only
        // here, once the payment has succeeded: a pending one may never clear.
        // And only on the delivery that credited the session: a redelivered
        // older event must not put back a card the user has since replaced.
        if (result == TopUpResult.APPLIED) {
            capturePaymentMethodIfPresent(userId, session.getId(), ip);
        }
    }

    /**
     * A delayed-notification payment failed after checkout, so the session
     * was never credited. A session that was credited anyway (completed
     * under the older handler, which credited regardless of
     * {@code payment_status}) is flagged for an operator to claw back via
     * the admin console rather than debited here, which could push the
     * balance negative.
     */
    private void recordAsyncPaymentFailed(Event event, Session session, UUID userId, String ip) {
        long amount = amountOrZero(session);
        boolean alreadyCredited = ledger.hasEntryFrom(userId, CreditSource.STRIPE, session.getId());
        if (alreadyCredited) {
            log.warn("async payment failed for already-credited session {} (user {}, {} cents)",
                    session.getId(), userId, amount);
        }
        audit.record(AuditLogService.EVT_BILLING_TOPUP_FAILED, AuditOutcome.FAILURE, userId, ip, Map.of(
                "amountCents", amount,
                "eventId", event.getId(),
                "sessionId", session.getId(),
                "alreadyCredited", alreadyCredited));
        if (!alreadyCredited) {
            // Mail failures are swallowed inside MailService — never propagated here.
            users.findById(userId).ifPresent(u -> mail.sendTopUpPaymentFailed(u, amount));
        }
    }

    private static long amountOrZero(Session session) {
        Long amount = session.getAmountTotal();
        return amount == null ? 0L : amount;
    }

    /**
     * Best-effort: pull the payment_method off the completed Checkout Session
     * and persist it so auto-recharge can use it later. Failures here don't
     * roll back the top-up — credits should always apply.
     *
     * <p>Only a {@code save_card=true} session's method is saved: Stripe
     * attaches a method to the customer for off-session charges only when
     * the PaymentIntent has {@code setup_future_usage=off_session}. Such a
     * session replaces the saved method, so a user whose card was declined
     * saves a new one with a top-up, as the auto-recharge-failed email says.
     *
     * <p>Only cards are saved. Auto-recharge charges off-session and credits
     * as soon as the charge succeeds; a bank debit (ACH Direct Debit
     * {@code us_bank_account}, SEPA Debit {@code sepa_debit}) only reaches
     * {@code processing} and settles days later, so auto-recharge would take
     * the money without crediting it. Skipping the save leaves the top-up
     * itself untouched.
     */
    private void capturePaymentMethodIfPresent(UUID userId, String sessionId, String ip) {
        try {
            User user = users.findById(userId).orElse(null);
            if (user == null) return;
            StripeService.CheckoutPaymentMethod pm = stripe.retrieveCheckoutPaymentMethod(sessionId);
            if (pm == null || !pm.savedForOffSession()) return;
            if (!pm.isCard()) {
                log.info("not saving {} payment method {} from session {}: auto-recharge charges cards only",
                        pm.type(), pm.id(), sessionId);
                audit.record(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REJECTED,
                        AuditOutcome.DENIED, userId, ip,
                        Map.of("sessionId", sessionId, "paymentMethodId", pm.id(),
                                "paymentMethodType", String.valueOf(pm.type())));
                return;
            }
            String replaced = user.getStripePaymentMethodId();
            user.setStripePaymentMethodId(pm.id());
            users.save(user);
            Map<String, Object> detail = new HashMap<>(Map.of("sessionId", sessionId, "paymentMethodId", pm.id()));
            // Absent on a first save, and when Checkout reused the card already on file.
            if (replaced != null && !replaced.equals(pm.id())) detail.put("replacedPaymentMethodId", replaced);
            audit.record(AuditLogService.EVT_BILLING_PAYMENT_METHOD_SAVED,
                    AuditOutcome.SUCCESS, userId, ip, detail);
        } catch (StripeException e) {
            log.warn("could not retrieve payment_method for session {}: {}", sessionId, e.getMessage());
        }
    }

    private static UUID userIdFromSession(Session session, String eventType) {
        // Prefer metadata; fall back to client_reference_id. Both set by us at create time.
        Map<String, String> meta = session.getMetadata();
        if (meta != null && meta.containsKey(StripeService.META_USER_ID)) {
            return UUID.fromString(meta.get(StripeService.META_USER_ID));
        }
        if (session.getClientReferenceId() != null) {
            return UUID.fromString(session.getClientReferenceId());
        }
        log.warn("{} missing user id: sessionId={} metadataKeys={} clientRef={}",
                eventType,
                session.getId(),
                meta == null ? "null" : meta.keySet(),
                session.getClientReferenceId());
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                eventType + " missing user id (no metadata or client_reference_id)");
    }

    // ===== NOWPayments (crypto / USDT) ===============================

    /**
     * Mint a NOWPayments hosted invoice for {@code amountCents}. Returns
     * the hosted-payment URL; the client redirects there. The top-up is
     * fiat-denominated — the user picks the crypto / network on the page.
     */
    @Transactional
    public TopUpIntent startNowPaymentsTopUp(UUID userId, long amountCents, String ip) {
        if (amountCents < billing.topupMinCents() || amountCents > billing.topupMaxCents()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "amountCents must be between " + billing.topupMinCents()
                            + " and " + billing.topupMaxCents());
        }
        User user = users.findById(userId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.UNAUTHORIZED, "user not found"));
        try {
            CryptoInvoice invoice = nowPayments.createInvoice(user, amountCents);
            audit.record(AuditLogService.EVT_BILLING_NOWPAYMENTS_INTENT, AuditOutcome.SUCCESS,
                    userId, ip, Map.of(
                            "amountCents", amountCents,
                            "invoiceId", invoice.reference()));
            return new TopUpIntent(invoice.reference(), invoice.hostedUrl(), amountCents);
        } catch (RuntimeException e) {
            log.warn("NOWPayments invoice creation failed for user {}: {}", userId, e.getMessage());
            audit.record(AuditLogService.EVT_BILLING_NOWPAYMENTS_INTENT, AuditOutcome.FAILURE,
                    userId, ip, Map.of("amountCents", amountCents,
                            "error", String.valueOf(e.getMessage())));
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "payment provider unavailable");
        }
    }

    /**
     * Handle a NOWPayments IPN. Only the terminal {@code finished} status
     * credits the ledger; non-terminal statuses (waiting / confirming /
     * confirmed / partially_paid / failed / expired) ack with 200 so
     * NOWPayments stops retrying. The upstream {@code payment_id} is the
     * ledger idempotency key — a replayed IPN credits exactly once.
     */
    @Transactional
    public void handleNowPaymentsWebhook(String payload, String sigHeader, String ip) {
        NowPaymentsIpn ipn;
        try {
            ipn = nowPayments.verifyAndParse(payload, sigHeader);
        } catch (InvalidSignatureException e) {
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_REJECTED, AuditOutcome.DENIED,
                    null, ip, Map.of("provider", "nowpayments", "reason", e.getMessage()));
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid nowpayments signature");
        }

        if (!ipn.isFinished()) {
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, AuditOutcome.SUCCESS,
                    null, ip, Map.of(
                            "provider", "nowpayments",
                            "status", ipn.status(),
                            "paymentId", ipn.paymentId()));
            return;
        }

        String userIdRaw = ipn.orderId();
        if (userIdRaw == null || userIdRaw.isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "nowpayments IPN missing order_id (user id)");
        }
        UUID userId = UUID.fromString(userIdRaw);
        long cents = ipn.fiatAmountCents(billing.currency());
        if (cents <= 0) {
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, AuditOutcome.SUCCESS,
                    userId, ip, Map.of("provider", "nowpayments",
                            "paymentId", ipn.paymentId(), "reason", "zero-amount"));
            return;
        }

        TopUpResult result = ledger.topUp(
                userId, cents, CreditSource.NOWPAYMENTS, ipn.paymentId(), ipn.paymentId(),
                "NOWPayments payment " + ipn.paymentId());

        String evtType = result == TopUpResult.APPLIED
                ? AuditLogService.EVT_BILLING_NOWPAYMENTS_SUCCEEDED
                : AuditLogService.EVT_BILLING_NOWPAYMENTS_DUPLICATE;
        audit.record(evtType, AuditOutcome.SUCCESS, userId, ip, Map.of(
                "amountCents", cents,
                "paymentId", ipn.paymentId()));
    }

    public record TopUpIntent(String sessionId, String url, long amountCents) { }
}
