package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.BillingDtos.TopUpResponse;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class BillingIntegrationTest {

    private static final String TEST_WEBHOOK_SECRET = "whsec_test_billing_integration_secret_value";

    private static final String EVT_COMPLETED       = "checkout.session.completed";
    private static final String EVT_ASYNC_SUCCEEDED = "checkout.session.async_payment_succeeded";
    private static final String EVT_ASYNC_FAILED    = "checkout.session.async_payment_failed";

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
    @Autowired CreditLedgerService ledgerService;
    @Autowired AuditLogEntryRepository auditEntries;

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
        return checkoutSessionEvent(EVT_COMPLETED, eventId, sessionId, userId, amountCents,
                "paid", apiVersion);
    }

    private static String checkoutSessionEvent(String type, String eventId, String sessionId,
                                               UUID userId, long amountCents, String paymentStatus) {
        return checkoutSessionEvent(type, eventId, sessionId, userId, amountCents, paymentStatus,
                "2024-12-18.acacia");
    }

    private static String checkoutSessionEvent(String type, String eventId, String sessionId,
                                               UUID userId, long amountCents, String paymentStatus,
                                               String apiVersion) {
        return """
            {
              "id": "%s",
              "object": "event",
              "api_version": "%s",
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
            """.formatted(eventId, apiVersion, Instant.now().getEpochSecond(), type,
                          sessionId, amountCents, userId, userId, paymentStatus);
    }

    /** Signed delivery to the webhook endpoint; Stripe needs a 2xx for every one. */
    private void deliver(String payload) {
        http.post().uri("/v1/billing/webhooks/stripe")
                .header("Stripe-Signature", stripeSignature(payload))
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(payload)
                .exchange()
                .expectStatus().isOk();
    }

    /**
     * The session re-fetch {@code capturePaymentMethodIfPresent} makes
     * (payment_intent expanded), for a {@code save_card=true} top-up.
     */
    private static void stubPaymentMethodLookup(String sessionId, String paymentMethodId) {
        stripeMock.stubFor(get(urlPathEqualTo("/v1/checkout/sessions/" + sessionId))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"%s","object":"checkout.session","livemode":false,
                                 "payment_intent":{"id":"pi_%s","object":"payment_intent",
                                                   "payment_method":"%s",
                                                   "setup_future_usage":"off_session","livemode":false}}
                                """.formatted(sessionId, sessionId, paymentMethodId))));
    }

    private static boolean paymentMethodLookedUp(String sessionId) {
        return stripeMock.getAllServeEvents().stream().anyMatch(
                e -> e.getRequest().getUrl().startsWith("/v1/checkout/sessions/" + sessionId));
    }

    private long balance(UUID userId) {
        return users.findById(userId).orElseThrow().getCreditBalanceCents();
    }

    private List<CreditLedger> ledgerRows(UUID userId) {
        return ledger.findByUserIdOrderByOccurredAtDesc(userId, PageRequest.of(0, 50)).getContent();
    }

    private List<AuditLogEntry> auditFor(String eventType, UUID userId) {
        return auditEntries.findByEventType(eventType).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
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

    // ===== payment_status + delayed-notification methods (ACH, SEPA) ==

    @Test
    void webhook_givenCardSessionPaid_creditsOnce_andSavesPaymentMethod() {
        SignedUser u = signupAndAuth();
        stubPaymentMethodLookup("cs_card_paid", "pm_card_paid");
        String payload = checkoutSessionEvent(
                EVT_COMPLETED, "evt_card_paid", "cs_card_paid", u.id(), 20_00L, "paid");

        deliver(payload);
        deliver(payload);

        assertThat(balance(u.id())).isEqualTo(20_00L);
        assertThat(ledgerRows(u.id())).singleElement().satisfies(row -> {
            assertThat(row.getSourceRef()).isEqualTo("cs_card_paid");
            assertThat(row.getIdempotencyKey()).isEqualTo("evt_card_paid");
        });
        assertThat(users.findById(u.id()).orElseThrow().getStripePaymentMethodId())
                .isEqualTo("pm_card_paid");
    }

    @Test
    void webhook_givenAchSessionCompletedUnpaid_doesNotCredit_orSavePaymentMethod() {
        SignedUser u = signupAndAuth();
        stubPaymentMethodLookup("cs_ach_pending", "pm_ach_pending");

        deliver(checkoutSessionEvent(
                EVT_COMPLETED, "evt_ach_pending", "cs_ach_pending", u.id(), 40_00L, "unpaid"));

        assertThat(balance(u.id())).isZero();
        assertThat(ledgerRows(u.id())).isEmpty();
        assertThat(users.findById(u.id()).orElseThrow().getStripePaymentMethodId()).isNull();
        assertThat(paymentMethodLookedUp("cs_ach_pending")).isFalse();
        assertThat(auditFor(AuditLogService.EVT_BILLING_TOPUP_PENDING, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("sessionId", "cs_ach_pending"));
    }

    @Test
    void webhook_givenAchCompletedUnpaidThenAsyncSucceeded_creditsOnce_andSavesPaymentMethod() {
        SignedUser u = signupAndAuth();
        stubPaymentMethodLookup("cs_ach_ok", "pm_ach_ok");
        String completed = checkoutSessionEvent(
                EVT_COMPLETED, "evt_ach_ok_completed", "cs_ach_ok", u.id(), 40_00L, "unpaid");
        String succeeded = checkoutSessionEvent(
                EVT_ASYNC_SUCCEEDED, "evt_ach_ok_async", "cs_ach_ok", u.id(), 40_00L, "paid");

        deliver(completed);
        assertThat(balance(u.id())).isZero();

        // Duplicate deliveries of both events — Stripe retries until it sees a 2xx.
        deliver(succeeded);
        deliver(succeeded);
        deliver(completed);

        assertThat(balance(u.id())).isEqualTo(40_00L);
        assertThat(ledgerRows(u.id())).singleElement().satisfies(row -> {
            assertThat(row.getSourceRef()).isEqualTo("cs_ach_ok");
            assertThat(row.getIdempotencyKey()).isEqualTo("evt_ach_ok_async");
        });
        assertThat(users.findById(u.id()).orElseThrow().getStripePaymentMethodId())
                .isEqualTo("pm_ach_ok");
    }

    /** Stripe doesn't guarantee delivery order; the session snapshot in each event decides. */
    @Test
    void webhook_givenAsyncSucceededArrivesBeforeCompletedUnpaid_creditsOnce() {
        SignedUser u = signupAndAuth();

        deliver(checkoutSessionEvent(
                EVT_ASYNC_SUCCEEDED, "evt_ooo_async", "cs_ooo", u.id(), 15_00L, "paid"));
        deliver(checkoutSessionEvent(
                EVT_COMPLETED, "evt_ooo_completed", "cs_ooo", u.id(), 15_00L, "unpaid"));

        assertThat(balance(u.id())).isEqualTo(15_00L);
        assertThat(ledgerRows(u.id())).hasSize(1);
    }

    @Test
    void webhook_givenAchAsyncPaymentFailed_neverCredits_andNotifiesUser(CapturedOutput output) {
        SignedUser u = signupAndAuth();
        String email = users.findById(u.id()).orElseThrow().getEmail();
        stubPaymentMethodLookup("cs_ach_fail", "pm_ach_fail");
        String failed = checkoutSessionEvent(
                EVT_ASYNC_FAILED, "evt_ach_fail_async", "cs_ach_fail", u.id(), 40_00L, "unpaid");

        deliver(checkoutSessionEvent(
                EVT_COMPLETED, "evt_ach_fail_completed", "cs_ach_fail", u.id(), 40_00L, "unpaid"));
        deliver(failed);

        assertThat(balance(u.id())).isZero();
        assertThat(ledgerRows(u.id())).isEmpty();
        assertThat(users.findById(u.id()).orElseThrow().getStripePaymentMethodId()).isNull();
        assertThat(paymentMethodLookedUp("cs_ach_fail")).isFalse();
        assertThat(auditFor(AuditLogService.EVT_BILLING_TOPUP_FAILED, u.id())).singleElement()
                .satisfies(e -> {
                    assertThat(e.getOutcome()).isEqualTo(AuditOutcome.FAILURE);
                    assertThat(e.getEventDetail())
                            .containsEntry("sessionId", "cs_ach_fail")
                            .containsEntry("alreadyCredited", false);
                });
        assertThat(output.getOut())
                .contains("[email-stub] Top-up payment failed for " + email + ": $40.00 not credited");

        // Replayed failure: still nothing credited.
        deliver(failed);
        assertThat(balance(u.id())).isZero();
    }

    /**
     * The two crediting events carry different event ids, so the event-id
     * idempotency key alone can't stop a session being credited by both;
     * the session-id source_ref check does.
     */
    @Test
    void webhook_givenPaidCompletedThenAsyncSucceededForSameSession_doesNotDoubleCredit() {
        SignedUser u = signupAndAuth();

        deliver(checkoutSessionEvent(
                EVT_COMPLETED, "evt_both_completed", "cs_both", u.id(), 12_00L, "paid"));
        deliver(checkoutSessionEvent(
                EVT_ASYNC_SUCCEEDED, "evt_both_async", "cs_both", u.id(), 12_00L, "paid"));

        assertThat(balance(u.id())).isEqualTo(12_00L);
        assertThat(ledgerRows(u.id())).singleElement()
                .satisfies(row -> assertThat(row.getIdempotencyKey()).isEqualTo("evt_both_completed"));
        assertThat(auditFor(AuditLogService.EVT_BILLING_TOPUP_DUPLICATE, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("eventId", "evt_both_async"));
    }

    @Test
    void webhook_givenAsyncSucceededWithApiVersionSkew_fallsBackToDeserializeUnsafe_andCreditsOnce() {
        SignedUser u = signupAndAuth();
        String payload = checkoutSessionEvent(EVT_ASYNC_SUCCEEDED, "evt_async_skew", "cs_async_skew",
                u.id(), 35_00L, "paid", "2026-04-22.dahlia");

        deliver(payload);
        deliver(payload);

        assertThat(balance(u.id())).isEqualTo(35_00L);
        assertThat(ledger.findByIdempotencyKey("evt_async_skew")).isPresent();
    }

    /**
     * Sessions completed before this handler checked payment_status were
     * credited while still unpaid. When such a payment then fails, the
     * credit is flagged for an operator (no automatic clawback) and the
     * user is not told "no credits were added".
     */
    @Test
    void webhook_givenAsyncFailedForSessionAlreadyCredited_flagsIt_andSendsNoMail(CapturedOutput output) {
        SignedUser u = signupAndAuth();
        String email = users.findById(u.id()).orElseThrow().getEmail();
        ledgerService.topUp(u.id(), 25_00L, CreditSource.STRIPE, "cs_legacy",
                "evt_legacy_completed", "Stripe Checkout cs_legacy");

        deliver(checkoutSessionEvent(
                EVT_ASYNC_FAILED, "evt_legacy_failed", "cs_legacy", u.id(), 25_00L, "unpaid"));

        assertThat(balance(u.id())).isEqualTo(25_00L);
        assertThat(auditFor(AuditLogService.EVT_BILLING_TOPUP_FAILED, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("alreadyCredited", true));
        assertThat(output.getOut()).doesNotContain("Top-up payment failed for " + email);
    }

    @Test
    void webhook_givenCompletedNoPaymentRequiredWithPositiveAmount_doesNotCredit() {
        SignedUser u = signupAndAuth();

        deliver(checkoutSessionEvent(EVT_COMPLETED, "evt_npr", "cs_npr", u.id(), 10_00L,
                "no_payment_required"));

        assertThat(balance(u.id())).isZero();
        assertThat(ledgerRows(u.id())).isEmpty();
        assertThat(auditFor(AuditLogService.EVT_BILLING_WEBHOOK_IGNORED, u.id())).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail())
                        .containsEntry("reason", "not-paid")
                        .containsEntry("paymentStatus", "no_payment_required"));
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
