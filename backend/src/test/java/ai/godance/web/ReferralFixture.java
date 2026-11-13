package ai.godance.web;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;

import ai.godance.repository.UserRepository;
import ai.godance.service.NowPaymentsCanonicalJson;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Accounts, codes, claims and signed payment webhooks for the referral
 * integration tests. Each test class registers {@link #STRIPE_WEBHOOK_SECRET}
 * and {@link #IPN_SECRET} as the webhook secrets.
 */
final class ReferralFixture {

    static final String PASSWORD = "correct-horse-battery-staple";
    static final String STRIPE_WEBHOOK_SECRET = "whsec_test_referral_integration_secret";
    static final String IPN_SECRET = "np_test_ipn_secret_for_referral_tests";
    /** The code alphabet, as {@code ReferralService} has it. */
    static final String CODE_ALPHABET = "23456789ABCDEFGHJKMNPQRSTUVWXYZ";
    static final ParameterizedTypeReference<Map<String, Object>> JSON_OBJECT = new ParameterizedTypeReference<>() { };

    private static final SecureRandom RNG = new SecureRandom();

    record Account(UUID id, String email, String fullName, String bearer) { }

    private final WebTestClient http;
    private final UserRepository users;

    ReferralFixture(WebTestClient http, UserRepository users) {
        this.http = http;
        this.users = users;
    }

    /** A new adult account, signed in. Each gets its own name, so a body naming it can be spotted. */
    Account signup(String tag) {
        return signup(tag, null);
    }

    /** {@link #signup(String)} from {@code clientIp} (an {@code X-Forwarded-For}), or none. */
    Account signup(String tag, String clientIp) {
        String email = tag + "-" + UUID.randomUUID() + "@godance.test";
        String fullName = "Tester " + UUID.randomUUID().toString().substring(0, 8);
        WebTestClient.RequestBodySpec request = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON);
        if (clientIp != null) {
            request = request.header("X-Forwarded-For", clientIp);
        }
        AuthResponse resp = request
                .bodyValue(Map.of("email", email, "password", PASSWORD, "fullName", fullName))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        return new Account(id, email, fullName, "Bearer " + resp.accessToken());
    }

    ResponseSpec me(Account a) {
        return http.get().uri("/v1/referrals/me").header("Authorization", a.bearer()).exchange();
    }

    Map<String, Object> meBody(Account a) {
        return me(a).expectStatus().isOk().expectBody(JSON_OBJECT).returnResult().getResponseBody();
    }

    /** The account's code, made by asking for it. */
    String codeOf(Account a) {
        return (String) meBody(a).get("code");
    }

    ResponseSpec claim(Account a, String code) {
        return claim(a.bearer(), code, null);
    }

    ResponseSpec claim(String bearer, String code, String clientIp) {
        WebTestClient.RequestBodySpec request = http.post().uri("/v1/referrals/claim")
                .header("Authorization", bearer)
                .contentType(MediaType.APPLICATION_JSON);
        if (clientIp != null) {
            request = request.header("X-Forwarded-For", clientIp);
        }
        return request.bodyValue(Map.of("code", code)).exchange();
    }

    /** {@code invited} claims {@code referrer}'s code, which must be accepted. */
    void invite(Account referrer, Account invited) {
        claim(invited, codeOf(referrer)).expectStatus().isOk();
    }

    /** A well-formed code nobody is likely to hold. */
    static String randomCode() {
        StringBuilder sb = new StringBuilder(8);
        for (int i = 0; i < 8; i++) {
            sb.append(CODE_ALPHABET.charAt(RNG.nextInt(CODE_ALPHABET.length())));
        }
        return sb.toString();
    }

    /** A unique id with Stripe's or NOWPayments' look, e.g. {@code uniqueId("cs_test_")}. */
    static String uniqueId(String prefix) {
        return prefix + UUID.randomUUID().toString().replace("-", "");
    }

    // ===== Stripe ======================================================

    /** A paid {@code checkout.session.completed} for an ordinary top-up. */
    static String paidCheckout(String eventId, String sessionId, UUID userId, long amountCents) {
        return checkoutEvent("checkout.session.completed", eventId, sessionId, userId, amountCents, "paid", false);
    }

    /** A Checkout Session event as Stripe sends it; {@code consent} adds the parental-consent metadata. */
    static String checkoutEvent(String type, String eventId, String sessionId, UUID userId,
                                long amountCents, String paymentStatus, boolean consent) {
        String metadata = consent
                ? "{\"godance_user_id\": \"%s\", \"godance_purpose\": \"parental_consent\"}".formatted(userId)
                : "{\"godance_user_id\": \"%s\"}".formatted(userId);
        return """
            {
              "id": "%s",
              "object": "event",
              "api_version": "2024-12-18.acacia",
              "created": %d,
              "livemode": false,
              "pending_webhooks": 1,
              "request": {"id": null, "idempotency_key": null},
              "type": "%s",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "checkout.session",
                  "amount_total": %d,
                  "currency": "usd",
                  "client_reference_id": "%s",
                  "metadata": %s,
                  "mode": "payment",
                  "payment_status": "%s",
                  "status": "complete",
                  "livemode": false
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond(), type, sessionId, amountCents,
                          userId, metadata, paymentStatus);
    }

    /** Signed delivery to the Stripe webhook; Stripe needs a 2xx for every one. */
    void deliverStripe(String payload) {
        long t = Instant.now().getEpochSecond();
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", "t=" + t + ",v1=" + hmacHex("HmacSHA256", STRIPE_WEBHOOK_SECRET, t + "." + payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();
    }

    // ===== NOWPayments =================================================

    /** A {@code finished} IPN for a USDT top-up of {@code priceAmountUsd} dollars. */
    static String finishedIpn(String paymentId, UUID userId, String priceAmountUsd) {
        return """
            {
              "payment_id": %s,
              "payment_status": "finished",
              "pay_address": "TXkV21abc000example000address000000000",
              "price_amount": %s,
              "price_currency": "usd",
              "pay_amount": 24.9,
              "pay_currency": "usdttrc20",
              "order_id": "%s",
              "order_description": "Godance credit top-up"
            }
            """.formatted(paymentId, priceAmountUsd, userId);
    }

    /** A NOWPayments payment id: digits only. */
    static String uniquePaymentId() {
        return Long.toString(5_600_000_000L + Math.floorMod(RNG.nextLong(), 1_000_000_000L));
    }

    /** Signed delivery to the NOWPayments IPN receiver. */
    void deliverIpn(String payload) {
        http.post().uri("/v1/billing/webhooks/nowpayments")
                .header("x-nowpayments-sig",
                        hmacHex("HmacSHA512", IPN_SECRET, NowPaymentsCanonicalJson.canonicalise(payload)))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();
    }

    private static String hmacHex(String algorithm, String key, String data) {
        try {
            Mac mac = Mac.getInstance(algorithm);
            mac.init(new SecretKeySpec(key.getBytes(), algorithm));
            return HexFormat.of().formatHex(mac.doFinal(data.getBytes()));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
