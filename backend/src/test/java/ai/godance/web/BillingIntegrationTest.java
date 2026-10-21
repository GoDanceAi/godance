package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.BillingDtos.TopUpResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class BillingIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_billing_integration_secret_value";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    static WireMockServer stripeMock;

    @BeforeAll
    static void startStripeMock() {
        stripeMock = new WireMockServer(WireMockConfiguration.options().dynamicPort());
        stripeMock.start();
    }

    @AfterAll
    static void stopStripeMock() {
        if (stripeMock != null) stripeMock.stop();
    }

    @DynamicPropertySource
    static void stripeProps(DynamicPropertyRegistry r) {
        r.add("godance.stripe.api-base",       () -> stripeMock.baseUrl());
        r.add("godance.stripe.webhook-secret", () -> TEST_WEBHOOK_SECRET);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerRepository ledger;

    @BeforeEach
    void resetStubs() {
        stripeMock.resetAll();
    }

    // ===== Helpers ====================================================

    private record SignedUser(UUID id, String bearer) { }

    private SignedUser signupAndAuth() {
        String email = "billing-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Billing Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID userId = users.findByEmail(email).orElseThrow().getId();
        return new SignedUser(userId, "Bearer " + resp.accessToken());
    }

    private static String stripeSignature(String payload) {
        long t = Instant.now().getEpochSecond();
        String signed = t + "." + payload;
        return "t=" + t + ",v1=" + hmacSha256Hex(TEST_WEBHOOK_SECRET, signed);
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"));
            byte[] h = mac.doFinal(data.getBytes());
            StringBuilder sb = new StringBuilder(h.length * 2);
            for (byte b : h) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static String checkoutCompletedEvent(String eventId, String sessionId,
                                                 UUID userId, long amountCents) {
        // SDK-pinned release name (stripe-java 28.4.0 → "2025-02-24.acacia"),
        // so EventDataObjectDeserializer.getObject() takes the safe path.
        return checkoutCompletedEvent(eventId, sessionId, userId, amountCents, "2024-12-18.acacia");
    }

    private static String checkoutCompletedEvent(String eventId, String sessionId,
                                                 UUID userId, long amountCents, String apiVersion) {
        return """
            {
              "id": "%s",
              "object": "event",
              "api_version": "%s",
              "created": %d,
              "livemode": false,
              "pending_webhooks": 1,
              "request": {"id": null, "idempotency_key": null},
              "type": "checkout.session.completed",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "checkout.session",
                  "amount_total": %d,
                  "currency": "usd",
                  "client_reference_id": "%s",
                  "metadata": {"godance_user_id": "%s"},
                  "mode": "payment",
                  "payment_status": "paid",
                  "status": "complete",
                  "livemode": false
                }
              }
            }
            """.formatted(eventId, apiVersion, Instant.now().getEpochSecond(),
                          sessionId, amountCents, userId, userId);
    }

    private static String unknownEvent(String eventId) {
        return """
            {
              "id": "%s",
              "object": "event",
              "api_version": "2024-12-18.acacia",
              "created": %d,
              "livemode": false,
              "pending_webhooks": 1,
              "request": {"id": null, "idempotency_key": null},
              "type": "customer.created",
              "data": {"object": {"id": "cus_x", "object": "customer", "livemode": false}}
            }
            """.formatted(eventId, Instant.now().getEpochSecond());
    }

    // ===== Webhook tests ==============================================

    @Test
    void webhook_givenValidSignedCheckoutCompleted_creditsLedgerAndBalance() {
        SignedUser u = signupAndAuth();
        String payload = checkoutCompletedEvent("evt_wh_apply", "cs_wh_apply", u.id(), 25_00L);
        String sig = stripeSignature(payload);

        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", sig)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(25_00L);
        assertThat(ledger.findByIdempotencyKey("evt_wh_apply")).isPresent();
    }

    @Test
    void webhook_replayedSameEvent_doesNotDoubleCredit() {
        SignedUser u = signupAndAuth();
        String payload = checkoutCompletedEvent("evt_wh_dup", "cs_wh_dup", u.id(), 10_00L);

        // First delivery
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        // Replay — Stripe retries on transient failures, sometimes with a fresh signature timestamp
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(10_00L);
    }

    @Test
    void webhook_givenBadSignature_returns400_doesNotMutateBalance() {
        SignedUser u = signupAndAuth();
        String payload = checkoutCompletedEvent("evt_wh_bad", "cs_wh_bad", u.id(), 5_00L);

        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", "t=1700000000,v1=deadbeefnotrealhash")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isBadRequest();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
    }

    @Test
    void webhook_givenUnknownEventType_returns200_doesNotChangeBalance() {
        SignedUser u = signupAndAuth();
        String payload = unknownEvent("evt_wh_ignore");

        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isZero();
        assertThat(ledger.findByIdempotencyKey("evt_wh_ignore")).isNotPresent();
    }

    /**
     * API-version skew — the bug the live smoke test caught. A live Stripe
     * account on a newer API version mints events whose {@code api_version}
     * release name differs from the SDK-pinned one ({@code dahlia} vs
     * {@code acacia}), so {@code EventDataObjectDeserializer.getObject()}
     * returns empty. BillingService must fall through to
     * {@code deserializeUnsafe()} and still credit the ledger.
     */
    @Test
    void webhook_givenApiVersionSkew_fallsBackToDeserializeUnsafe_andCreditsOnce() {
        SignedUser u = signupAndAuth();
        String payload = checkoutCompletedEvent(
                "evt_wh_skew", "cs_wh_skew", u.id(), 30_00L, "2026-04-22.dahlia");

        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(30_00L);
        assertThat(ledger.findByIdempotencyKey("evt_wh_skew")).isPresent();

        // Replay the identical skewed event — idempotency on the Stripe event
        // id must hold on the unsafe-deserialize path too, not just the safe one.
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();

        assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(30_00L);
    }

    // ===== Top-up checkout tests ======================================

    @Test
    void topup_authenticated_returnsCheckoutUrl_andPersistsStripeCustomerId() {
        SignedUser u = signupAndAuth();

        stripeMock.stubFor(post(urlEqualTo("/v1/customers"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"cus_test_xyz","object":"customer","livemode":false,"email":"x@y"}
                                """)));

        stripeMock.stubFor(post(urlEqualTo("/v1/checkout/sessions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"cs_test_abc","object":"checkout.session","mode":"payment",
                                 "url":"https://checkout.stripe.com/c/pay/cs_test_abc","livemode":false}
                                """)));

        TopUpResponse out = http.post().uri("/v1/billing/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 25_00L))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(TopUpResponse.class).returnResult().getResponseBody();

        assertThat(out.sessionId()).isEqualTo("cs_test_abc");
        assertThat(out.checkoutUrl()).isEqualTo("https://checkout.stripe.com/c/pay/cs_test_abc");
        assertThat(out.amountCents()).isEqualTo(25_00L);

        User refreshed = users.findById(u.id()).orElseThrow();
        assertThat(refreshed.getStripeCustomerId()).isEqualTo("cus_test_xyz");
    }

    @Test
    void topup_amountBelowFloor_returns400() {
        SignedUser u = signupAndAuth();

        http.post().uri("/v1/billing/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 50L))   // floor is 100¢
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void topup_amountAboveCeiling_returns400() {
        SignedUser u = signupAndAuth();

        http.post().uri("/v1/billing/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 100_000_01L))   // ceiling is 100_000¢
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void topup_unauthenticated_returns401() {
        http.post().uri("/v1/billing/topup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 10_00L))
                .exchange()
                .expectStatus().isUnauthorized();
    }

    @Test
    void topup_existingStripeCustomer_doesNotCreateAnotherOne() {
        SignedUser u = signupAndAuth();

        // Pre-seed: user already has a stripe customer id
        User user = users.findById(u.id()).orElseThrow();
        user.setStripeCustomerId("cus_existing_999");
        users.save(user);

        stripeMock.stubFor(post(urlEqualTo("/v1/checkout/sessions"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"cs_test_existing","object":"checkout.session","mode":"payment",
                                 "url":"https://checkout.stripe.com/c/pay/cs_test_existing","livemode":false}
                                """)));

        http.post().uri("/v1/billing/topup")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 5_00L))
                .exchange()
                .expectStatus().isCreated();

        assertThat(stripeMock.getAllServeEvents()).noneMatch(
                e -> e.getRequest().getUrl().equals("/v1/customers"));

        // Existing customer id unchanged
        assertThat(users.findById(u.id()).orElseThrow().getStripeCustomerId())
                .isEqualTo("cus_existing_999");
    }
}
