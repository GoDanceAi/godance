package ai.godance.service;

import java.util.Map;
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

@Service
public class BillingService {

    private static final Logger log = LoggerFactory.getLogger(BillingService.class);
    private static final String EVENT_CHECKOUT_COMPLETED = "checkout.session.completed";

    private final StripeService stripe;
    private final NowPaymentsService nowPayments;
    private final CreditLedgerService ledger;
    private final UserRepository users;
    private final AuditLogService audit;
    private final BillingProperties billing;

    public BillingService(StripeService stripe, NowPaymentsService nowPayments,
                          CreditLedgerService ledger, UserRepository users,
                          AuditLogService audit, BillingProperties billing) {
        this.stripe = stripe;
        this.nowPayments = nowPayments;
        this.ledger = ledger;
        this.users = users;
        this.audit = audit;
        this.billing = billing;
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
     * Handle a verified Stripe webhook. Returns true if processed (or
     * deliberately ignored — unknown event types are not errors). Throws
     * 400 if signature is invalid.
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

        if (!EVENT_CHECKOUT_COMPLETED.equals(event.getType())) {
            // Stripe will keep retrying on non-2xx, so explicitly succeed on
            // unknown events. We log for visibility but don't error.
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, AuditOutcome.SUCCESS,
                    null, ip, Map.of("eventType", event.getType(), "eventId", event.getId()));
            return;
        }

        // getObject() returns Optional.empty() when the event's Stripe API
        // version doesn't match the SDK's pinned version (e.g. live account
        // on 2026-04-22.dahlia, SDK 28.4.0 pinned to 2024-12-18.acacia).
        // The fields we actually read (id, metadata, client_reference_id,
        // amount_total) are stable across versions, so fall through to
        // deserializeUnsafe() — Stripe themselves recommend this for the
        // skew case.
        var deser = event.getDataObjectDeserializer();
        Session session = (Session) deser.getObject().orElse(null);
        if (session == null) {
            try {
                session = (Session) deser.deserializeUnsafe();
            } catch (com.stripe.exception.EventDataObjectDeserializationException e) {
                log.warn("checkout.session.completed deserialize failed (eventId={} apiVersion={}): {}",
                        event.getId(), event.getApiVersion(), e.getMessage());
                audit.record(AuditLogService.EVT_BILLING_WEBHOOK_REJECTED, AuditOutcome.FAILURE,
                        null, ip, Map.of(
                                "reason", "deserialize-failed",
                                "eventId", event.getId(),
                                "apiVersion", String.valueOf(event.getApiVersion())));
                throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                        "could not deserialize checkout.session.completed payload");
            }
        }
        log.info("stripe webhook checkout.session.completed eventId={} apiVersion={} sessionId={}",
                event.getId(), event.getApiVersion(), session.getId());

        UUID userId = userIdFromSession(session);
        Long amount = session.getAmountTotal();
        if (amount == null || amount <= 0) {
            // Free / zero-amount sessions don't credit. Log and ack.
            audit.record(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, AuditOutcome.SUCCESS,
                    userId, ip, Map.of("eventId", event.getId(), "reason", "zero-amount"));
            return;
        }

        TopUpResult result = ledger.topUp(
                userId, amount, CreditSource.STRIPE, session.getId(), event.getId(),
                "Stripe Checkout " + session.getId());

        String evtType = result == TopUpResult.APPLIED
                ? AuditLogService.EVT_BILLING_TOPUP_SUCCEEDED
                : AuditLogService.EVT_BILLING_TOPUP_DUPLICATE;
        audit.record(evtType, AuditOutcome.SUCCESS, userId, ip, Map.of(
                "amountCents", amount, "eventId", event.getId(), "sessionId", session.getId()));

        // If the session was created with setup_future_usage=off_session, the
        // PaymentIntent will have a payment_method we can charge later. Persist
        // it on the user the first time we see it (don't overwrite — the user
        // may already have a saved card from an earlier session).
        capturePaymentMethodIfPresent(userId, session.getId(), ip);
    }

    /**
     * Best-effort: pull the payment_method off the completed Checkout Session
     * and persist it so auto-recharge can use it later. Failures here don't
     * roll back the top-up — credits should always apply.
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
            if (user == null || user.getStripePaymentMethodId() != null) return;
            StripeService.CheckoutPaymentMethod pm = stripe.retrieveCheckoutPaymentMethod(sessionId);
            if (pm == null) return;
            if (!pm.isCard()) {
                log.info("not saving {} payment method {} from session {}: auto-recharge charges cards only",
                        pm.type(), pm.id(), sessionId);
                audit.record(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REJECTED,
                        AuditOutcome.DENIED, userId, ip,
                        Map.of("sessionId", sessionId, "paymentMethodId", pm.id(),
                                "paymentMethodType", String.valueOf(pm.type())));
                return;
            }
            user.setStripePaymentMethodId(pm.id());
            users.save(user);
            audit.record(AuditLogService.EVT_BILLING_PAYMENT_METHOD_SAVED,
                    AuditOutcome.SUCCESS, userId, ip,
                    Map.of("sessionId", sessionId, "paymentMethodId", pm.id()));
        } catch (StripeException e) {
            log.warn("could not retrieve payment_method for session {}: {}", sessionId, e.getMessage());
        }
    }

    private static UUID userIdFromSession(Session session) {
        // Prefer metadata; fall back to client_reference_id. Both set by us at create time.
        Map<String, String> meta = session.getMetadata();
        if (meta != null && meta.containsKey(StripeService.META_USER_ID)) {
            return UUID.fromString(meta.get(StripeService.META_USER_ID));
        }
        if (session.getClientReferenceId() != null) {
            return UUID.fromString(session.getClientReferenceId());
        }
        log.warn("checkout.session.completed missing user id: sessionId={} metadataKeys={} clientRef={}",
                session.getId(),
                meta == null ? "null" : meta.keySet(),
                session.getClientReferenceId());
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "checkout.session.completed missing user id (no metadata or client_reference_id)");
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
