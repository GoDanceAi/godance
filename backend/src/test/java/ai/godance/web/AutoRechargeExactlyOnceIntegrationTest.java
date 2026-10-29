package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.time.Duration;
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
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.AutoRechargeAttempt;
import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.AutoRechargeAttemptRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AutoRechargeService;
import ai.godance.service.AutoRechargeService.Outcome;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Spec risk R-04: one auto-recharge charges the card once and credits the
 * ledger once, whatever fails between the two. Stripe is WireMock; the
 * failures are injected after Stripe has (or may have) charged.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AutoRechargeExactlyOnceIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_exactly_once_secret_value";
    private static final long THRESHOLD = 500L;
    private static final long TOPUP = 2000L;

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
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.stripe.api-base", () -> stripeMock.baseUrl());
        r.add("godance.stripe.webhook-secret", () -> TEST_WEBHOOK_SECRET);
        r.add("godance.scheduling.auto-recharge-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired AutoRechargeService autoRecharge;
    @Autowired AutoRechargeAttemptRepository attempts;
    /** A spy so one test can make the credit fail after Stripe charged. */
    @MockitoSpyBean CreditLedgerService ledger;

    @BeforeEach
    void resetStubs() {
        stripeMock.resetAll();
        stripeMock.stubFor(get(urlPathMatching("/v1/payment_methods/.+"))
                .willReturn(json("""
                        {"id":"pm_test_card","object":"payment_method","type":"card","livemode":false}
                        """)));
    }

    // ---------- helpers ------------------------------------------------

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200).withHeader("content-type", "application/json").withBody(body);
    }

    private static String paymentIntent(String id, UUID attemptId) {
        return """
                {"id":"%s","object":"payment_intent","status":"succeeded","amount":%d,"currency":"usd",
                 "metadata":{"auto_recharge_attempt_id":"%s"},"livemode":false}
                """.formatted(id, TOPUP, attemptId);
    }

    /** Stripe's list endpoint answers with these PaymentIntents. */
    private static void stubPaymentIntentList(String... paymentIntents) {
        stripeMock.stubFor(get(urlPathEqualTo("/v1/payment_intents"))
                .willReturn(json("""
                        {"object":"list","url":"/v1/payment_intents","has_more":false,"data":[%s]}
                        """.formatted(String.join(",", paymentIntents)))));
    }

    private static void stubChargeSucceeds(String paymentIntentId) {
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(json("""
                        {"id":"%s","object":"payment_intent","status":"succeeded","amount":%d,
                         "currency":"usd","livemode":false}
                        """.formatted(paymentIntentId, TOPUP))));
    }

    private static int chargesSent() {
        return stripeMock.findAll(postRequestedFor(urlEqualTo("/v1/payment_intents"))).size();
    }

    /** A user below the threshold with auto-recharge on and a saved card. */
    private UUID userNeedingRecharge() {
        String email = "ar1-" + UUID.randomUUID() + "@godance.test";
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email, "password", "correct-horse-battery-staple",
                        "fullName", "Exactly Once"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class);
        User u = users.findByEmail(email).orElseThrow();
        u.setStripeCustomerId("cus_test_" + u.getId());
        u.setStripePaymentMethodId("pm_test_" + u.getId());
        u.setAutoRechargeEnabled(true);
        u.setAutoRechargeThresholdCents(THRESHOLD);
        u.setAutoRechargeTopupCents(TOPUP);
        u.setCreditBalanceCents(100L);
        users.save(u);
        return u.getId();
    }

    private User user(UUID id) {
        return users.findById(id).orElseThrow();
    }

    private List<AutoRechargeAttempt> attemptsOf(UUID userId) {
        return attempts.findByUserIdOrderByCreatedAtDesc(userId);
    }

    // ---------- Stripe's answer is lost after it charged ---------------

    @Test
    void chargeIfNeeded_stripeFailsAfterCharging_staysEnabled_thenNextRunCreditsThatChargeOnce() {
        UUID userId = userNeedingRecharge();
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(500).withHeader("content-type", "application/json")
                        .withBody("{\"error\":{\"type\":\"api_error\",\"message\":\"upstream timeout\"}}")));

        assertThat(autoRecharge.chargeIfNeeded(userId)).isEqualTo(Outcome.RETRY_LATER);

        // Not a decline: auto-recharge stays on, nothing is credited yet, one attempt is pending.
        assertThat(user(userId).isAutoRechargeEnabled()).isTrue();
        assertThat(user(userId).getCreditBalanceCents()).isEqualTo(100L);
        AutoRechargeAttempt attempt = attemptsOf(userId).getFirst();
        assertThat(attempt.getStatus()).isEqualTo(AutoRechargeAttempt.Status.PENDING);
        int sentBefore = chargesSent();

        // Stripe did charge: the PaymentIntent carries the attempt id.
        stubPaymentIntentList(paymentIntent("pi_charged_once", attempt.getId()));
        assertThat(autoRecharge.chargeIfNeeded(userId)).isEqualTo(Outcome.CHARGED);
        assertThat(autoRecharge.chargeIfNeeded(userId)).isEqualTo(Outcome.SKIPPED_ABOVE_THRESHOLD);

        assertThat(chargesSent()).isEqualTo(sentBefore);   // found, not charged again
        assertThat(user(userId).getCreditBalanceCents()).isEqualTo(100L + TOPUP);
        AutoRechargeAttempt settled = attempts.findById(attempt.getId()).orElseThrow();
        assertThat(settled.getStatus()).isEqualTo(AutoRechargeAttempt.Status.SUCCEEDED);
        assertThat(settled.getPaymentIntentId()).isEqualTo("pi_charged_once");
    }

    // ---------- the credit rolls back after Stripe charged ------------

    @Test
    void chargeIfNeeded_creditFailsAfterCharge_nextRunCreditsWithoutChargingAgain() {
        UUID userId = userNeedingRecharge();
        stubChargeSucceeds("pi_rolled_back");
        doThrow(new IllegalStateException("database went away"))
                .when(ledger).topUp(eq(userId), anyLong(), eq(CreditSource.STRIPE), anyString(), anyString(), any());

        assertThatThrownBy(() -> autoRecharge.chargeIfNeeded(userId)).hasMessageContaining("database went away");

        assertThat(chargesSent()).isEqualTo(1);
        assertThat(user(userId).getCreditBalanceCents()).isEqualTo(100L);
        AutoRechargeAttempt attempt = attemptsOf(userId).getFirst();
        assertThat(attempt.getStatus()).isEqualTo(AutoRechargeAttempt.Status.PENDING);

        org.mockito.Mockito.reset(ledger);
        stubPaymentIntentList(paymentIntent("pi_rolled_back", attempt.getId()));
        assertThat(autoRecharge.chargeIfNeeded(userId)).isEqualTo(Outcome.CHARGED);

        assertThat(chargesSent()).isEqualTo(1);   // one card charge in all
        assertThat(user(userId).getCreditBalanceCents()).isEqualTo(100L + TOPUP);
        assertThat(attempts.findById(attempt.getId()).orElseThrow().getStatus())
                .isEqualTo(AutoRechargeAttempt.Status.SUCCEEDED);
    }

    // ---------- a pending attempt that never reached Stripe -----------

    @Test
    void chargeIfNeeded_oldPendingAttemptStripeNeverSaw_retiresItAndChargesOnce() {
        UUID userId = userNeedingRecharge();
        AutoRechargeAttempt stale = new AutoRechargeAttempt();
        stale.setUserId(userId);
        stale.setAmountCents(TOPUP);
        stale.setStatus(AutoRechargeAttempt.Status.PENDING);
        stale.setCreatedAt(Instant.now().minus(Duration.ofMinutes(20)));
        attempts.save(stale);
        stubPaymentIntentList();
        stubChargeSucceeds("pi_fresh");

        assertThat(autoRecharge.chargeIfNeeded(userId)).isEqualTo(Outcome.CHARGED);

        assertThat(chargesSent()).isEqualTo(1);
        assertThat(user(userId).getCreditBalanceCents()).isEqualTo(100L + TOPUP);
        AutoRechargeAttempt retired = attempts.findById(stale.getId()).orElseThrow();
        assertThat(retired.getStatus()).isEqualTo(AutoRechargeAttempt.Status.FAILED);
        assertThat(retired.getFailureReason()).isEqualTo("no charge reached Stripe");
        assertThat(attemptsOf(userId)).filteredOn(a -> a.getStatus() == AutoRechargeAttempt.Status.SUCCEEDED)
                .singleElement().satisfies(a -> assertThat(a.getPaymentIntentId()).isEqualTo("pi_fresh"));
    }

    @Test
    void chargeIfNeeded_recentPendingAttemptStripeHasNotShown_waitsWithoutCharging() {
        UUID userId = userNeedingRecharge();
        AutoRechargeAttempt inFlight = new AutoRechargeAttempt();
        inFlight.setUserId(userId);
        inFlight.setAmountCents(TOPUP);
        inFlight.setStatus(AutoRechargeAttempt.Status.PENDING);
        attempts.save(inFlight);
        stubPaymentIntentList();
        stubChargeSucceeds("pi_should_not_happen");

        assertThat(autoRecharge.chargeIfNeeded(userId)).isEqualTo(Outcome.RETRY_LATER);

        assertThat(chargesSent()).isZero();
        assertThat(attempts.findById(inFlight.getId()).orElseThrow().getStatus())
                .isEqualTo(AutoRechargeAttempt.Status.PENDING);
    }

    // ---------- payment_intent.succeeded settles a pending attempt -----

    @Test
    void paymentIntentSucceededWebhook_creditsThePendingAttemptOnce() {
        UUID userId = userNeedingRecharge();
        AutoRechargeAttempt pending = new AutoRechargeAttempt();
        pending.setUserId(userId);
        pending.setAmountCents(TOPUP);
        pending.setStatus(AutoRechargeAttempt.Status.PENDING);
        attempts.save(pending);
        stubPaymentIntentList(paymentIntent("pi_from_webhook", pending.getId()));

        String payload = paymentIntentSucceededEvent("evt_" + UUID.randomUUID(), "pi_from_webhook", pending.getId());
        deliver(payload);
        deliver(payload);   // Stripe redelivers; the credit still lands once

        assertThat(chargesSent()).isZero();
        assertThat(user(userId).getCreditBalanceCents()).isEqualTo(100L + TOPUP);
        assertThat(attempts.findById(pending.getId()).orElseThrow().getStatus())
                .isEqualTo(AutoRechargeAttempt.Status.SUCCEEDED);
    }

    private static String paymentIntentSucceededEvent(String eventId, String paymentIntentId, UUID attemptId) {
        return """
            {
              "id": "%s",
              "object": "event",
              "api_version": "2024-12-18.acacia",
              "created": %d,
              "livemode": false,
              "pending_webhooks": 1,
              "request": {"id": null, "idempotency_key": null},
              "type": "payment_intent.succeeded",
              "data": {
                "object": {
                  "id": "%s",
                  "object": "payment_intent",
                  "status": "succeeded",
                  "amount": %d,
                  "currency": "usd",
                  "metadata": {"auto_recharge_attempt_id": "%s"},
                  "livemode": false
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond(), paymentIntentId, TOPUP, attemptId);
    }

    private void deliver(String payload) {
        long t = Instant.now().getEpochSecond();
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", "t=" + t + ",v1=" + hmacSha256Hex(TEST_WEBHOOK_SECRET, t + "." + payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();
    }

    private static String hmacSha256Hex(String key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key.getBytes(), "HmacSHA256"));
            StringBuilder sb = new StringBuilder();
            for (byte b : mac.doFinal(data.getBytes())) sb.append(String.format("%02x", b));
            return sb.toString();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
