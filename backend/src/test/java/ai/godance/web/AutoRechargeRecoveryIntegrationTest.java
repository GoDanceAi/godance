package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.AutoRechargeService;
import ai.godance.service.AutoRechargeService.Outcome;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Recovering auto-recharge after a hard failure: the auto-recharge-failed
 * email tells the user to run a top-up with an up-to-date card
 * ({@code save_card=true}) and re-enable. That top-up's card must replace
 * the declined one, or re-enabling charges the declined card again. Drives
 * the Stripe webhook (where the card is saved) and auto-recharge (where it
 * is charged) against WireMock as Stripe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AutoRechargeRecoveryIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_autorecharge_recovery_secret";

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
        r.add("godance.stripe.api-base",       () -> stripeMock.baseUrl());
        r.add("godance.stripe.webhook-secret", () -> TEST_WEBHOOK_SECRET);
        // Keep the daily safety-net cron out of the way (see AutoRechargeIntegrationTest).
        r.add("godance.scheduling.auto-recharge-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository auditEntries;
    @Autowired AutoRechargeService autoRecharge;

    @BeforeEach
    void resetStubs() {
        stripeMock.resetAll();
        // The saved-method type lookup enable() and chargeIfNeeded() make: every card here is one.
        stripeMock.stubFor(get(urlPathMatching("/v1/payment_methods/.+"))
                .willReturn(json(200, """
                        {"id":"pm_test_card","object":"payment_method","type":"card","livemode":false}
                        """)));
    }

    // ---------- helpers ------------------------------------------------

    private record SignedUser(UUID id, String bearer) { }

    private SignedUser signup() {
        String email = "arrec-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Recovery Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        // Skip the interactive top-up: seed the Stripe customer it would create.
        User u = users.findById(id).orElseThrow();
        u.setStripeCustomerId("cus_test_" + id);
        users.save(u);
        return new SignedUser(id, "Bearer " + resp.accessToken());
    }

    private User user(SignedUser u) {
        return users.findById(u.id()).orElseThrow();
    }

    /** 50_00 threshold, 20_00 top-up: every balance these tests reach is below it. */
    private WebTestClient.ResponseSpec enableAutoRecharge(SignedUser u) {
        return http.put().uri("/v1/billing/auto-recharge")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", true, "threshold_cents", 50_00, "topup_cents", 20_00))
                .exchange();
    }

    private static ResponseDefinitionBuilder json(int status, String body) {
        return aResponse().withStatus(status)
                .withHeader("content-type", "application/json")
                .withBody(body);
    }

    /**
     * A Checkout top-up as Stripe reports it: the session is stubbed for the
     * re-fetch the webhook makes, then {@code checkout.session.completed}
     * (paid) is delivered. With {@code saveCard} the session's PaymentIntent
     * has {@code setup_future_usage=off_session}; without it, null.
     */
    private void cardTopUp(SignedUser u, String sessionId, String paymentMethodId,
                           long amountCents, boolean saveCard) {
        stripeMock.stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .willReturn(json(200, """
                        {"id":"%s","object":"checkout.session","livemode":false,
                         "payment_intent":{"id":"pi_%s","object":"payment_intent","livemode":false,
                                           "payment_method":{"id":"%s","object":"payment_method",
                                                             "type":"card","livemode":false},
                                           "setup_future_usage":%s}}
                        """.formatted(sessionId, sessionId, paymentMethodId,
                                      saveCard ? "\"off_session\"" : "null"))));
        deliver(checkoutCompletedEvent("evt_" + sessionId, sessionId, u.id(), amountCents));
    }

    /** Off-session charges on {@code paymentMethodId} succeed. */
    private static void stubChargeSucceeds(String paymentMethodId) {
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .withRequestBody(containing("payment_method=" + paymentMethodId))
                .willReturn(json(200, """
                        {"id":"pi_ok_%s",
                         "object":"payment_intent",
                         "status":"succeeded",
                         "amount":2000,
                         "currency":"usd",
                         "livemode":false}
                        """.formatted(paymentMethodId))));
    }

    /** Off-session charges on {@code paymentMethodId} are declined; {@code declineCode} may be null. */
    private static void stubChargeDeclined(String paymentMethodId, String code, String declineCode) {
        String declineField = declineCode == null ? "" : "\"decline_code\":\"" + declineCode + "\",";
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .withRequestBody(containing("payment_method=" + paymentMethodId))
                .willReturn(json(402, """
                        {"error":{"type":"card_error",
                                  "code":"%s",
                                  %s
                                  "message":"Your card was declined."}}
                        """.formatted(code, declineField))));
    }

    /** The {@code payment_method} each off-session PaymentIntent request named, in order. */
    private static List<String> chargedPaymentMethods() {
        return stripeMock.findAll(postRequestedFor(urlEqualTo("/v1/payment_intents"))).stream()
                .map(r -> Arrays.stream(r.getBodyAsString().split("&"))
                        .map(param -> URLDecoder.decode(param, StandardCharsets.UTF_8))
                        .filter(param -> param.startsWith("payment_method="))
                        .map(param -> param.substring("payment_method=".length()))
                        .findFirst().orElse(null))
                .toList();
    }

    private List<AuditLogEntry> auditFor(String eventType, UUID userId) {
        return auditEntries.findByEventType(eventType).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
    }

    private void deliver(String payload) {
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();
    }

    private static String checkoutCompletedEvent(String eventId, String sessionId,
                                                 UUID userId, long amountCents) {
        return """
            {
              "id": "%s",
              "object": "event",
              "api_version": "2024-12-18.acacia",
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
            """.formatted(eventId, Instant.now().getEpochSecond(),
                          sessionId, amountCents, userId, userId);
    }

    private static String stripeSignature(String payload) {
        long t = Instant.now().getEpochSecond();
        return "t=" + t + ",v1=" + hmacSha256Hex(TEST_WEBHOOK_SECRET, t + "." + payload);
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

    // ---------- decline → new card → re-enable --------------------------

    @Test
    void decline_thenSaveCardTopUpWithNewCard_thenReEnable_chargesTheNewCard() {
        SignedUser u = signup();
        String oldCard = "pm_old_" + u.id();
        String newCard = "pm_new_" + u.id();
        stubChargeDeclined(oldCard, "card_declined", "insufficient_funds");
        stubChargeSucceeds(newCard);

        cardTopUp(u, "cs_old_" + u.id(), oldCard, 20_00L, true);
        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(oldCard);
        enableAutoRecharge(u).expectStatus().isOk();

        // The old card is declined: auto-recharge turns itself off. The
        // decline may clear on its own, so the card stays on file.
        assertThat(autoRecharge.chargeIfNeeded(u.id())).isEqualTo(Outcome.HARD_FAIL_DISABLED);
        User declined = user(u);
        assertThat(declined.isAutoRechargeEnabled()).isFalse();
        assertThat(declined.getAutoRechargeLastFailureReason()).contains("card_declined");
        assertThat(declined.getStripePaymentMethodId()).isEqualTo(oldCard);
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REMOVED, u.id())).isEmpty();

        // What the email says to do: a save_card=true top-up with another card ...
        String newSession = "cs_new_" + u.id();
        cardTopUp(u, newSession, newCard, 10_00L, true);
        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(newCard);
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_SAVED, u.id()))
                .anySatisfy(e -> assertThat(e.getEventDetail())
                        .containsEntry("sessionId", newSession)
                        .containsEntry("paymentMethodId", newCard));

        // ... then re-enable.
        enableAutoRecharge(u).expectStatus().isOk();
        assertThat(user(u).isAutoRechargeEnabled()).isTrue();

        assertThat(autoRecharge.chargeIfNeeded(u.id())).isEqualTo(Outcome.CHARGED);
        assertThat(chargedPaymentMethods()).containsExactly(oldCard, newCard);
        User recharged = user(u);
        // 20_00 + 10_00 from the two top-ups, + 20_00 auto-recharge.
        assertThat(recharged.getCreditBalanceCents()).isEqualTo(50_00L);
        assertThat(recharged.isAutoRechargeEnabled()).isTrue();
        assertThat(recharged.getAutoRechargeLastFailureReason()).isNull();
    }

    /**
     * Expired, or reported lost / stolen: the card is gone for good. Stripe
     * reports an expired card under {@code code}, the issuer's cancellations
     * under {@code decline_code} of a {@code card_declined}.
     */
    @ParameterizedTest
    @CsvSource({
            "expired_card,",
            "card_declined, lost_card",
            "card_declined, stolen_card",
            "card_declined, pickup_card"
    })
    void decline_givenCardCanNeverRecover_dropsIt_soReEnableWaitsForANewCard(String code, String declineCode) {
        SignedUser u = signup();
        String deadCard = "pm_dead_" + u.id();
        String newCard = "pm_new_" + u.id();
        stubChargeDeclined(deadCard, code, declineCode);
        stubChargeSucceeds(newCard);

        cardTopUp(u, "cs_dead_" + u.id(), deadCard, 20_00L, true);
        enableAutoRecharge(u).expectStatus().isOk();

        assertThat(autoRecharge.chargeIfNeeded(u.id())).isEqualTo(Outcome.HARD_FAIL_DISABLED);
        User declined = user(u);
        assertThat(declined.isAutoRechargeEnabled()).isFalse();
        assertThat(declined.getStripePaymentMethodId()).isNull();
        assertThat(autoRecharge.currentConfig(u.id()).paymentMethodOnFile()).isFalse();
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REMOVED, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail())
                        .containsEntry("paymentMethodId", deadCard)
                        .containsEntry("code", code)
                        .containsEntry("declineCode", String.valueOf(declineCode)));

        // Re-enabling on the dead card is refused ...
        enableAutoRecharge(u).expectStatus().isEqualTo(HttpStatus.CONFLICT);

        // ... until a save_card=true top-up saves a new one.
        cardTopUp(u, "cs_new_" + u.id(), newCard, 10_00L, true);
        enableAutoRecharge(u).expectStatus().isOk();
        assertThat(autoRecharge.chargeIfNeeded(u.id())).isEqualTo(Outcome.CHARGED);
        assertThat(chargedPaymentMethods()).containsExactly(deadCard, newCard);
        assertThat(user(u).getCreditBalanceCents()).isEqualTo(50_00L);
    }

    // ---------- which top-ups save a card --------------------------------

    /**
     * Without {@code save_card} Stripe doesn't set the card up for
     * off-session use, so it is neither saved nor allowed to replace a
     * saved one.
     */
    @Test
    void topUpWithoutSaveCard_neverSavesItsCard() {
        SignedUser u = signup();
        String savedCard = "pm_saved_" + u.id();

        cardTopUp(u, "cs_once_" + u.id(), "pm_once_" + u.id(), 10_00L, false);
        assertThat(user(u).getStripePaymentMethodId()).isNull();
        enableAutoRecharge(u).expectStatus().isEqualTo(HttpStatus.CONFLICT);

        cardTopUp(u, "cs_saved_" + u.id(), savedCard, 10_00L, true);
        cardTopUp(u, "cs_once_again_" + u.id(), "pm_once_again_" + u.id(), 10_00L, false);

        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(savedCard);
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_SAVED, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("paymentMethodId", savedCard));
        assertThat(user(u).getCreditBalanceCents()).isEqualTo(30_00L);
    }

    /**
     * Stripe can deliver an event more than once, long after the first time.
     * A redelivered event for an older save_card session must not put back
     * the card a newer one replaced; that card may be the declined one.
     */
    @Test
    void redeliveredOlderSaveCardEvent_doesNotPutBackTheReplacedCard() {
        SignedUser u = signup();
        String oldSession = "cs_old_" + u.id();
        String newCard = "pm_new_" + u.id();

        cardTopUp(u, oldSession, "pm_old_" + u.id(), 20_00L, true);
        cardTopUp(u, "cs_new_" + u.id(), newCard, 10_00L, true);
        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(newCard);

        deliver(checkoutCompletedEvent("evt_" + oldSession, oldSession, u.id(), 20_00L));

        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(newCard);
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_SAVED, u.id())).hasSize(2);
        assertThat(user(u).getCreditBalanceCents()).isEqualTo(30_00L);
    }
}
