package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
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
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.AutoRechargeService;
import ai.godance.service.AutoRechargeService.Outcome;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Auto-recharge saves and charges cards only. A bank debit (ACH Direct
 * Debit {@code us_bank_account}, SEPA Debit {@code sepa_debit}) confirms
 * off-session to {@code processing} and settles days later, so charging
 * one would take the money without crediting it. Drives the Stripe
 * webhook (where the payment method is saved) and auto-recharge (where it
 * is charged) against WireMock as Stripe.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class AutoRechargePaymentMethodIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_autorecharge_payment_method_secret";

    private static final String EVT_COMPLETED       = "checkout.session.completed";
    private static final String EVT_ASYNC_SUCCEEDED = "checkout.session.async_payment_succeeded";

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
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(json("""
                        {"id":"pi_test_card_ok",
                         "object":"payment_intent",
                         "status":"succeeded",
                         "amount":2000,
                         "currency":"usd",
                         "livemode":false}
                        """)));
    }

    // ---------- helpers ------------------------------------------------

    private record SignedUser(UUID id, String email, String bearer) { }

    private SignedUser signup() {
        String email = "arpm-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Payment Method Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        // Skip the interactive top-up: seed the Stripe customer it would create.
        User u = users.findById(id).orElseThrow();
        u.setStripeCustomerId("cus_test_" + id);
        users.save(u);
        return new SignedUser(id, email, "Bearer " + resp.accessToken());
    }

    private User user(SignedUser u) {
        return users.findById(u.id()).orElseThrow();
    }

    /** State left by the old webhook, which saved any payment method type, and an enable on top. */
    private void seedAutoRechargeOn(SignedUser u, String paymentMethodId) {
        User user = user(u);
        user.setStripePaymentMethodId(paymentMethodId);
        user.setAutoRechargeEnabled(true);
        user.setAutoRechargeThresholdCents(500L);
        user.setAutoRechargeTopupCents(2000L);
        user.setCreditBalanceCents(100L);
        users.save(user);
    }

    private WebTestClient.ResponseSpec enableAutoRecharge(SignedUser u) {
        return http.put().uri("/v1/billing/auto-recharge")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", true, "threshold_cents", 50_00, "topup_cents", 20_00))
                .exchange();
    }

    private static ResponseDefinitionBuilder json(String body) {
        return aResponse().withStatus(200)
                .withHeader("content-type", "application/json")
                .withBody(body);
    }

    /** The session re-fetch the webhook makes to find the payment method, with it expanded. */
    private static void stubCheckoutSession(String sessionId, String paymentMethodId, String type) {
        stripeMock.stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .willReturn(json("""
                        {"id":"%s","object":"checkout.session","livemode":false,
                         "payment_intent":{"id":"pi_%s","object":"payment_intent","livemode":false,
                                           "payment_method":{"id":"%s","object":"payment_method",
                                                             "type":"%s","livemode":false}}}
                        """.formatted(sessionId, sessionId, paymentMethodId, type))));
    }

    /** The saved-method lookup enable() and chargeIfNeeded() make. */
    private static void stubPaymentMethod(String paymentMethodId, String type) {
        stripeMock.stubFor(get(urlPathEqualTo("/v1/payment_methods/" + paymentMethodId))
                .willReturn(json("""
                        {"id":"%s","object":"payment_method","type":"%s","livemode":false}
                        """.formatted(paymentMethodId, type))));
    }

    /**
     * A {@code save_card=true} top-up as Stripe reports it. A card session
     * completes paid. A bank debit session completes unpaid, then settles
     * with {@code async_payment_succeeded}; both events are delivered so
     * the test holds whichever of them the webhook credits on.
     */
    private void completeTopUp(SignedUser u, String sessionId, long amountCents, boolean bankDebit) {
        if (bankDebit) {
            deliver(checkoutSessionEvent(EVT_COMPLETED, "evt_" + sessionId + "_completed",
                    sessionId, u.id(), amountCents, "unpaid"));
            deliver(checkoutSessionEvent(EVT_ASYNC_SUCCEEDED, "evt_" + sessionId + "_async",
                    sessionId, u.id(), amountCents, "paid"));
        } else {
            deliver(checkoutSessionEvent(EVT_COMPLETED, "evt_" + sessionId + "_completed",
                    sessionId, u.id(), amountCents, "paid"));
        }
    }

    private void deliver(String payload) {
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();
    }

    private static String checkoutSessionEvent(String type, String eventId, String sessionId,
                                               UUID userId, long amountCents, String paymentStatus) {
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
                  "metadata": {"godance_user_id": "%s"},
                  "mode": "payment",
                  "payment_status": "%s",
                  "status": "complete",
                  "livemode": false
                }
              }
            }
            """.formatted(eventId, Instant.now().getEpochSecond(), type,
                          sessionId, amountCents, userId, userId, paymentStatus);
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

    private List<AuditLogEntry> auditFor(String eventType, UUID userId) {
        return auditEntries.findByEventType(eventType).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
    }

    private static List<LoggedRequest> paymentIntentRequests() {
        return stripeMock.findAll(postRequestedFor(urlEqualTo("/v1/payment_intents")));
    }

    private static String decoded(String formOrUrl) {
        return URLDecoder.decode(formOrUrl, StandardCharsets.UTF_8);
    }

    // ---------- saving: only cards ---------------------------------------

    @Test
    void cardTopUp_savesCard_andAutoRechargeChargesItAsACardOnlyPaymentIntent() {
        SignedUser u = signup();
        String session = "cs_card_" + u.id();
        String card = "pm_card_" + u.id();
        stubCheckoutSession(session, card, "card");
        stubPaymentMethod(card, "card");

        completeTopUp(u, session, 20_00L, false);

        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(card);
        assertThat(stripeMock.findAll(getRequestedFor(urlPathEqualTo("/v1/checkout/sessions/" + session))))
                .singleElement()
                .satisfies(r -> assertThat(decoded(r.getUrl())).contains("=payment_intent.payment_method"));

        enableAutoRecharge(u).expectStatus().isOk();
        // 20_00 balance < 50_00 threshold.
        assertThat(autoRecharge.chargeIfNeeded(u.id())).isEqualTo(Outcome.CHARGED);

        assertThat(user(u).getCreditBalanceCents()).isEqualTo(40_00L);
        assertThat(paymentIntentRequests()).singleElement().satisfies(r ->
                assertThat(decoded(r.getBodyAsString()))
                        .contains("payment_method=" + card, "off_session=true",
                                "payment_method_types[0]=card")
                        .doesNotContain("automatic_payment_methods"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"us_bank_account", "sepa_debit"})
    void bankDebitTopUp_creditsTopUp_butDoesNotSavePaymentMethod_soEnableReturns409(String type) {
        SignedUser u = signup();
        String session = "cs_bank_" + u.id();
        String bank = "pm_bank_" + u.id();
        stubCheckoutSession(session, bank, type);

        completeTopUp(u, session, 40_00L, true);

        User refreshed = user(u);
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(40_00L);
        assertThat(refreshed.getStripePaymentMethodId()).isNull();
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_SAVED, u.id())).isEmpty();
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REJECTED, u.id())).singleElement()
                .satisfies(e -> {
                    assertThat(e.getOutcome()).isEqualTo(AuditOutcome.DENIED);
                    assertThat(e.getEventDetail())
                            .containsEntry("sessionId", session)
                            .containsEntry("paymentMethodId", bank)
                            .containsEntry("paymentMethodType", type);
                });

        enableAutoRecharge(u).expectStatus().isEqualTo(HttpStatus.CONFLICT);
        assertThat(user(u).isAutoRechargeEnabled()).isFalse();
        assertThat(paymentIntentRequests()).isEmpty();
    }

    @Test
    void bankDebitTopUpThenCardTopUp_savesTheCard() {
        SignedUser u = signup();
        String bankSession = "cs_bank_" + u.id();
        String cardSession = "cs_card_" + u.id();
        String card = "pm_card_" + u.id();
        stubCheckoutSession(bankSession, "pm_bank_" + u.id(), "us_bank_account");
        stubCheckoutSession(cardSession, card, "card");
        stubPaymentMethod(card, "card");

        completeTopUp(u, bankSession, 40_00L, true);
        completeTopUp(u, cardSession, 20_00L, false);

        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(card);
        enableAutoRecharge(u).expectStatus().isOk();
    }

    /** Stripe ignored the expand, so the type is unknown: fail closed. */
    @Test
    void topUp_givenPaymentMethodNotExpanded_doesNotSaveIt() {
        SignedUser u = signup();
        String session = "cs_unexpanded_" + u.id();
        stripeMock.stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + session))
                .willReturn(json("""
                        {"id":"%s","object":"checkout.session","livemode":false,
                         "payment_intent":{"id":"pi_%s","object":"payment_intent","livemode":false,
                                           "payment_method":"pm_unexpanded"}}
                        """.formatted(session, session))));

        completeTopUp(u, session, 20_00L, false);

        assertThat(user(u).getStripePaymentMethodId()).isNull();
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REJECTED, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("paymentMethodType", "null"));
    }

    // ---------- bank debits saved before cards-only ----------------------

    @ParameterizedTest
    @ValueSource(strings = {"us_bank_account", "sepa_debit"})
    void chargeIfNeeded_givenSavedBankDebit_dropsIt_andDisables_withoutCreatingAPaymentIntent(
            String type, CapturedOutput output) {
        SignedUser u = signup();
        String bank = "pm_legacy_" + u.id();
        seedAutoRechargeOn(u, bank);
        stubPaymentMethod(bank, type);

        Outcome outcome = autoRecharge.chargeIfNeeded(u.id());

        assertThat(outcome).isEqualTo(Outcome.HARD_FAIL_DISABLED);
        assertThat(paymentIntentRequests()).isEmpty();
        User refreshed = user(u);
        assertThat(refreshed.isAutoRechargeEnabled()).isFalse();
        assertThat(refreshed.getStripePaymentMethodId()).isNull();
        assertThat(refreshed.getAutoRechargeLastFailureAt()).isNotNull();
        assertThat(refreshed.getAutoRechargeLastFailureReason())
                .isEqualTo("payment_method.type=" + type + ": auto-recharge needs a card");
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(100L);
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REJECTED, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail())
                        .containsEntry("paymentMethodId", bank)
                        .containsEntry("paymentMethodType", type));
        assertThat(output.getOut()).contains("[email-stub] Auto-recharge failed for " + u.email()
                + ": payment_method.type=" + type);
    }

    @Test
    void putAutoRecharge_givenSavedBankDebit_returns409_andDropsIt_soACardTopUpCanReplaceIt() {
        SignedUser u = signup();
        String bank = "pm_legacy_" + u.id();
        User seeded = user(u);
        seeded.setStripePaymentMethodId(bank);
        users.save(seeded);
        stubPaymentMethod(bank, "sepa_debit");

        enableAutoRecharge(u).expectStatus().isEqualTo(HttpStatus.CONFLICT);

        // Dropped even though enable() answered with an error.
        User refreshed = user(u);
        assertThat(refreshed.getStripePaymentMethodId()).isNull();
        assertThat(refreshed.isAutoRechargeEnabled()).isFalse();
        assertThat(auditFor(AuditLogService.EVT_BILLING_PAYMENT_METHOD_REJECTED, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("paymentMethodType", "sepa_debit"));
        assertThat(auditFor(AuditLogService.EVT_BILLING_AUTORECHARGE_ENABLED, u.id())).isEmpty();

        String session = "cs_card_" + u.id();
        String card = "pm_card_" + u.id();
        stubCheckoutSession(session, card, "card");
        stubPaymentMethod(card, "card");
        completeTopUp(u, session, 20_00L, false);

        assertThat(user(u).getStripePaymentMethodId()).isEqualTo(card);
        enableAutoRecharge(u).expectStatus().isOk();
        assertThat(user(u).isAutoRechargeEnabled()).isTrue();
    }
}
