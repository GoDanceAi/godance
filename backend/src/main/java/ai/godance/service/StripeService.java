package ai.godance.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.stripe.Stripe;
import com.stripe.exception.SignatureVerificationException;
import com.stripe.exception.StripeException;
import com.stripe.model.Customer;
import com.stripe.model.Event;
import com.stripe.model.PaymentIntent;
import com.stripe.model.checkout.Session;
import com.stripe.net.Webhook;
import com.stripe.param.CustomerCreateParams;
import com.stripe.param.PaymentIntentCreateParams;
import com.stripe.param.checkout.SessionCreateParams;
import com.stripe.param.checkout.SessionRetrieveParams;

import ai.godance.config.BillingProperties;
import ai.godance.config.StripeProperties;
import ai.godance.domain.User;
import jakarta.annotation.PostConstruct;

/**
 * Thin adapter over the Stripe SDK. All Stripe HTTP I/O lives here.
 * The SDK uses static globals ({@code Stripe.apiKey},
 * {@code Stripe.overrideApiBase}) — we set them once at startup so
 * tests can redirect to WireMock by setting {@code godance.stripe.api-base}.
 */
@Service
public class StripeService {

    private static final Logger log = LoggerFactory.getLogger(StripeService.class);

    /** Stripe metadata key — also set on Checkout Sessions so webhooks can resolve the user. */
    public static final String META_USER_ID = "godance_user_id";

    private final StripeProperties stripe;
    private final BillingProperties billing;

    public StripeService(StripeProperties stripe, BillingProperties billing) {
        this.stripe = stripe;
        this.billing = billing;
    }

    @PostConstruct
    void wireSdk() {
        Stripe.apiKey = stripe.secretKey();
        if (stripe.apiBase() != null && !stripe.apiBase().isBlank()) {
            Stripe.overrideApiBase(stripe.apiBase());
            log.info("Stripe API base overridden to {}", stripe.apiBase());
        }
    }

    /**
     * Create (or no-op fetch) the Stripe customer record for this user.
     * Returns the customer id; caller persists it on {@link User#setStripeCustomerId}.
     */
    public String ensureCustomer(User user) throws StripeException {
        if (user.getStripeCustomerId() != null) {
            return user.getStripeCustomerId();
        }
        CustomerCreateParams params = CustomerCreateParams.builder()
                .setEmail(user.getEmail())
                .setName(user.getFullName())
                .putMetadata(META_USER_ID, user.getId().toString())
                .build();
        Customer customer = Customer.create(params);
        return customer.getId();
    }

    /**
     * Build a Checkout Session for a credit top-up. The hosted-checkout
     * URL is in {@link Session#getUrl()}; caller redirects the browser there.
     *
     * <p>When {@code saveCardForFuture} is true, Stripe captures the payment
     * method with {@code setup_future_usage=off_session} so we can charge
     * the same card later (auto-recharge). The {@code payment_method} id
     * surfaces via the session's {@code payment_intent}; the webhook
     * persists it on the user once the payment has succeeded.
     */
    public Session createTopUpCheckout(User user, String stripeCustomerId,
                                       long amountCents, boolean saveCardForFuture) throws StripeException {
        SessionCreateParams.Builder builder = SessionCreateParams.builder()
                .setMode(SessionCreateParams.Mode.PAYMENT)
                .setCustomer(stripeCustomerId)
                .setSuccessUrl(stripe.successUrl() + "?session_id={CHECKOUT_SESSION_ID}")
                .setCancelUrl(stripe.cancelUrl())
                .setClientReferenceId(user.getId().toString())
                .putMetadata(META_USER_ID, user.getId().toString())
                .addLineItem(SessionCreateParams.LineItem.builder()
                        .setQuantity(1L)
                        .setPriceData(SessionCreateParams.LineItem.PriceData.builder()
                                .setCurrency(billing.currency())
                                .setUnitAmount(amountCents)
                                .setProductData(SessionCreateParams.LineItem.PriceData.ProductData.builder()
                                        .setName("Godance credit top-up")
                                        .build())
                                .build())
                        .build());
        if (saveCardForFuture) {
            builder.setPaymentIntentData(SessionCreateParams.PaymentIntentData.builder()
                    .setSetupFutureUsage(
                            SessionCreateParams.PaymentIntentData.SetupFutureUsage.OFF_SESSION)
                    .build());
        }
        return Session.create(builder.build());
    }

    /**
     * Re-fetch a completed Checkout Session with its {@code payment_intent}
     * expanded so we can read the {@code payment_method} id and whether the
     * intent set it up for off-session use. Returns null if the session has
     * no payment method (e.g., zero-amount session).
     */
    public CheckoutPaymentMethod retrieveCheckoutPaymentMethod(String sessionId) throws StripeException {
        SessionRetrieveParams params = SessionRetrieveParams.builder()
                .addExpand("payment_intent")
                .build();
        Session full = Session.retrieve(sessionId, params, null);
        PaymentIntent pi = full.getPaymentIntentObject();
        if (pi == null || pi.getPaymentMethod() == null) return null;
        return new CheckoutPaymentMethod(pi.getPaymentMethod(),
                "off_session".equals(pi.getSetupFutureUsage()));
    }

    /**
     * Charge a saved payment method off-session. Used by auto-recharge.
     * Throws {@link StripeException} for any decline / SCA-required / etc;
     * caller treats that as a hard failure and disables auto-recharge.
     */
    public PaymentIntent chargeOffSession(String stripeCustomerId, String paymentMethodId,
                                          long amountCents, String idempotencyKey) throws StripeException {
        PaymentIntentCreateParams params = PaymentIntentCreateParams.builder()
                .setAmount(amountCents)
                .setCurrency(billing.currency())
                .setCustomer(stripeCustomerId)
                .setPaymentMethod(paymentMethodId)
                .setConfirm(true)
                .setOffSession(true)
                // No redirect-based payment methods are eligible off-session.
                .setAutomaticPaymentMethods(PaymentIntentCreateParams.AutomaticPaymentMethods.builder()
                        .setEnabled(true)
                        .setAllowRedirects(PaymentIntentCreateParams.AutomaticPaymentMethods.AllowRedirects.NEVER)
                        .build())
                .build();
        com.stripe.net.RequestOptions opts = com.stripe.net.RequestOptions.builder()
                .setIdempotencyKey(idempotencyKey)
                .build();
        return PaymentIntent.create(params, opts);
    }

    /**
     * Verify the Stripe-Signature header against the raw body and return
     * the parsed event. Throws {@link SignatureVerificationException} on
     * any mismatch — caller must surface that as 400.
     */
    public Event verifyAndParse(String payload, String sigHeader) throws SignatureVerificationException {
        return Webhook.constructEvent(payload, sigHeader, stripe.webhookSecret());
    }

    /**
     * The payment method a Checkout Session's payment used.
     * {@code savedForOffSession}: its PaymentIntent had
     * {@code setup_future_usage=off_session} (a {@code save_card=true}
     * top-up), so Stripe attached the method to the customer for
     * off-session charges.
     */
    public record CheckoutPaymentMethod(String id, boolean savedForOffSession) { }
}
