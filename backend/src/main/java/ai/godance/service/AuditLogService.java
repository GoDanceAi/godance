package ai.godance.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.repository.AuditLogEntryRepository;

/**
 * Thin typed wrapper over the constrained {@link AuditLogEntryRepository}.
 * Centralises audit event types so we can grep all call-sites.
 *
 * <p>Event-type vocabulary lives here. Sessions 4+ extend it as new
 * audited actions land (billing webhooks, gateway dispatch, etc.).
 */
@Service
public class AuditLogService {

    public static final String EVT_AUTH_SIGNUP        = "auth.signup";
    public static final String EVT_AUTH_LOGIN_OK      = "auth.login.success";
    public static final String EVT_AUTH_LOGIN_FAIL    = "auth.login.failure";
    public static final String EVT_AUTH_VERIFY        = "auth.verify";
    public static final String EVT_AUTH_OAUTH_LOGIN   = "auth.oauth.login";
    public static final String EVT_API_KEY_CREATED    = "apikey.created";
    public static final String EVT_API_KEY_DELETED    = "apikey.deleted";
    public static final String EVT_BILLING_TOPUP_INTENT      = "billing.topup.intent";
    public static final String EVT_BILLING_TOPUP_SUCCEEDED   = "billing.topup.succeeded";
    public static final String EVT_BILLING_TOPUP_DUPLICATE   = "billing.topup.duplicate";
    /** Stripe Checkout completed but a delayed-notification payment (ACH, SEPA) hasn't cleared. */
    public static final String EVT_BILLING_TOPUP_PENDING     = "billing.topup.pending";
    /** A delayed-notification payment for a Stripe Checkout top-up failed — never credited. */
    public static final String EVT_BILLING_TOPUP_FAILED      = "billing.topup.failed";
    public static final String EVT_BILLING_WEBHOOK_REJECTED  = "billing.webhook.rejected";
    public static final String EVT_BILLING_WEBHOOK_IGNORED   = "billing.webhook.ignored";
    public static final String EVT_BILLING_NOWPAYMENTS_INTENT    = "billing.topup.nowpayments.intent";
    public static final String EVT_BILLING_NOWPAYMENTS_SUCCEEDED = "billing.topup.nowpayments.succeeded";
    public static final String EVT_BILLING_NOWPAYMENTS_DUPLICATE = "billing.topup.nowpayments.duplicate";
    public static final String EVT_BILLING_PAYMENT_METHOD_SAVED = "billing.payment_method.saved";
    /** A payment method refused for auto-recharge (not saved, or dropped from the user): not a card. */
    public static final String EVT_BILLING_PAYMENT_METHOD_REJECTED = "billing.payment_method.rejected";
    // Auto-recharge (Session 13)
    public static final String EVT_BILLING_AUTORECHARGE_ENABLED   = "billing.autorecharge.enabled";
    public static final String EVT_BILLING_AUTORECHARGE_DISABLED  = "billing.autorecharge.disabled";
    public static final String EVT_BILLING_AUTORECHARGE_FIRED     = "billing.autorecharge.fired";
    public static final String EVT_BILLING_AUTORECHARGE_FAILED    = "billing.autorecharge.failed";
    // Admin console (round 5)
    public static final String EVT_ADMIN_CREDIT_ADJUSTED = "admin.credit.adjusted";
    public static final String EVT_ADMIN_CATALOG_UPDATED = "admin.catalog.updated";

    private final AuditLogEntryRepository repo;

    public AuditLogService(AuditLogEntryRepository repo) {
        this.repo = repo;
    }

    public void record(String eventType, AuditOutcome outcome, UUID userId,
                       String ipAddress, Map<String, Object> detail) {
        AuditLogEntry entry = new AuditLogEntry();
        entry.setEventType(eventType);
        entry.setOutcome(outcome);
        entry.setUserId(userId);
        entry.setIpAddress(ipAddress);
        entry.setEventDetail(detail);
        repo.save(entry);
    }

    /**
     * {@link #record} in its own transaction, so the row commits even when
     * the caller's transaction rolls back. For a denial thrown after the
     * caller has written state that must not commit; when nothing has been
     * written yet, mark the caller {@code noRollbackFor} instead.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordInNewTransaction(String eventType, AuditOutcome outcome, UUID userId,
                                       String ipAddress, Map<String, Object> detail) {
        record(eventType, outcome, userId, ipAddress, detail);
    }
}
