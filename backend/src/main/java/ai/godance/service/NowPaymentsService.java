package ai.godance.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import ai.godance.config.BillingProperties;
import ai.godance.config.NowPaymentsProperties;
import ai.godance.domain.CreditSource;
import ai.godance.domain.User;

/**
 * Thin adapter over the NOWPayments REST API — the v1 crypto top-up rail
 * (USDT on TRC-20 / ERC-20 / BEP-20). No official Java SDK exists; the
 * surface we need is small (one POST + one IPN verification) so we drive
 * it with Spring's {@code RestClient}.
 *
 * <p><b>IPN signature scheme.</b> NOWPayments signs the callback body with
 * HMAC-SHA512 of the JSON re-serialised with its keys sorted recursively
 * (not the raw bytes), in JavaScript's {@code JSON.stringify} format —
 * header {@code x-nowpayments-sig}. We reproduce that canonical form
 * ({@link NowPaymentsCanonicalJson}), HMAC it, and
 * {@linkplain MessageDigest#isEqual constant-time compare}.
 */
@Service
public class NowPaymentsService implements CryptoPaymentRail {

    /** Terminal success status — the only one that credits the ledger. */
    public static final String STATUS_FINISHED = "finished";

    private final NowPaymentsProperties props;
    private final BillingProperties billing;
    private final ObjectMapper json;
    private final RestClient client;

    public NowPaymentsService(NowPaymentsProperties props, BillingProperties billing,
                              ObjectMapper json) {
        this.props = props;
        this.billing = billing;
        this.json = json;
        this.client = RestClient.builder()
                .baseUrl(props.apiBase())
                .defaultHeader("x-api-key", props.apiKey())
                .build();
    }

    @Override
    public CreditSource creditSource() {
        return CreditSource.NOWPAYMENTS;
    }

    @Override
    public boolean isEnabled() {
        return true;
    }

    /**
     * Create a hosted NOWPayments invoice for a credit top-up. The
     * top-up is denominated in the platform fiat currency; the user
     * picks the crypto / network on the hosted page. The user id is
     * carried in {@code order_id} so the IPN can resolve the account.
     */
    @Override
    public CryptoInvoice createInvoice(User user, long amountCents) {
        BigDecimal priceAmount = BigDecimal.valueOf(amountCents)
                .movePointLeft(2)
                .setScale(2, RoundingMode.HALF_UP);

        Map<String, Object> body = Map.of(
                "price_amount", priceAmount,
                "price_currency", billing.currency(),
                "order_id", user.getId().toString(),
                "order_description", "Godance credit top-up",
                "success_url", props.successUrl(),
                "cancel_url", props.cancelUrl());

        @SuppressWarnings("unchecked")
        Map<String, Object> response = client.post()
                .uri("/v1/invoice")
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(Map.class);

        if (response == null) {
            throw new IllegalStateException("NOWPayments /v1/invoice returned empty body");
        }
        Object idObj = response.get("id");
        String invoiceUrl = (String) response.get("invoice_url");
        if (idObj == null || invoiceUrl == null) {
            throw new IllegalStateException("NOWPayments /v1/invoice response missing id or invoice_url");
        }
        return new CryptoInvoice(String.valueOf(idObj), invoiceUrl, amountCents);
    }

    /**
     * Verify {@code x-nowpayments-sig} against the body and parse the IPN.
     * Throws {@link InvalidSignatureException} on mismatch; the controller
     * surfaces that as 400 so NOWPayments flags the delivery failure.
     */
    public NowPaymentsIpn verifyAndParse(String payload, String sigHeader) {
        if (sigHeader == null || sigHeader.isBlank()) {
            throw new InvalidSignatureException("missing x-nowpayments-sig");
        }
        String canonical = canonicalise(payload);
        String expected = hmacSha512Hex(props.ipnSecret(), canonical);
        if (!MessageDigest.isEqual(
                expected.getBytes(StandardCharsets.US_ASCII),
                sigHeader.trim().getBytes(StandardCharsets.US_ASCII))) {
            throw new InvalidSignatureException("signature mismatch");
        }
        try {
            // Parse what was signed, not the raw body: bodies that differ only
            // in number spelling (5077125051 vs 5077125051.0) share a signature
            // and must yield the same payment_id — it's the idempotency key.
            JsonNode root = json.readTree(canonical);
            String paymentId = root.path("payment_id").asString(null);
            String status = root.path("payment_status").asString(null);
            String orderId = root.path("order_id").asString(null);
            if (paymentId == null || paymentId.isBlank() || status == null || status.isBlank()) {
                throw new IllegalArgumentException("NOWPayments IPN missing payment_id or payment_status");
            }
            return new NowPaymentsIpn(paymentId, status, orderId, root);
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("malformed NOWPayments IPN JSON: " + e.getMessage(), e);
        }
    }

    /**
     * Re-serialise {@code payload} the way NOWPayments' reference code does
     * before HMACing it. A malformed body can't be canonicalised, which is
     * itself a signature failure.
     */
    private static String canonicalise(String payload) {
        try {
            return NowPaymentsCanonicalJson.canonicalise(payload);
        } catch (Exception e) {
            throw new InvalidSignatureException("uncanonicalisable IPN body: " + e.getMessage());
        }
    }

    private static String hmacSha512Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA512");
            mac.init(new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA512"));
            byte[] h = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException("HmacSHA512 unavailable; JVM broken", e);
        }
    }

    /**
     * Parsed NOWPayments IPN. {@code paymentId} is the upstream payment id
     * — also the ledger idempotency key, so a retried IPN credits once.
     */
    public record NowPaymentsIpn(String paymentId, String status, String orderId, JsonNode raw) {

        /** True iff this is the terminal success NOWPayments credits on. */
        public boolean isFinished() {
            return STATUS_FINISHED.equalsIgnoreCase(status);
        }

        /**
         * Fiat top-up amount in cents, read from {@code price_amount} /
         * {@code price_currency} — the amount the invoice was denominated
         * in, not the crypto amount paid. Throws if the currency isn't
         * the expected platform fiat currency.
         */
        public long fiatAmountCents(String expectedCurrency) {
            String currency = raw.path("price_currency").asString("");
            if (!expectedCurrency.equalsIgnoreCase(currency)) {
                throw new IllegalArgumentException(
                        "NOWPayments price_currency must be '" + expectedCurrency
                                + "'; got '" + currency + "'");
            }
            String amount = raw.path("price_amount").asString("0");
            return new BigDecimal(amount)
                    .movePointRight(2)
                    .setScale(0, RoundingMode.HALF_UP)
                    .longValueExact();
        }
    }

    public static class InvalidSignatureException extends RuntimeException {
        public InvalidSignatureException(String message) { super(message); }
    }
}
