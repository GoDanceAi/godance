package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.User;
import ai.godance.repository.UserRepository;
import ai.godance.service.AutoRechargeService;
import ai.godance.service.AutoRechargeService.Outcome;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.dto.AuthDtos.AuthResponse;
import ai.godance.web.dto.BillingDtos.AutoRechargeConfigView;

/**
 * Session 13 — auto-recharge end-to-end. Drives the off-session Stripe
 * PaymentIntent path against WireMock and verifies both manual
 * ({@code chargeIfNeeded}) and debit-driven (via Quartz) firing.
 *
 * <p>The Quartz cron safety net's cron is overridden to a never-fires
 * expression so the only thing that schedules jobs during the test is
 * our debit hook.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AutoRechargeIntegrationTest {

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
        // Push the daily cron 75 years out so it never fires during the
        // test (Quartz rejects expressions with no future fire time, so
        // we can't just use a literally-impossible date like Feb 31).
        r.add("godance.scheduling.auto-recharge-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired AutoRechargeService autoRecharge;
    @Autowired CreditLedgerService ledger;
    @Autowired ai.godance.service.AutoRechargeTrigger trigger;

    @BeforeEach
    void resetStubs() {
        stripeMock.resetAll();
        // Default stub for off-session PaymentIntent: succeeds.
        stripeMock.stubFor(post(urlMatching("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"pi_test_succeed",
                                 "object":"payment_intent",
                                 "status":"succeeded",
                                 "amount":2000,
                                 "currency":"usd",
                                 "livemode":false}
                                """)));
    }

    // ---------- helpers ------------------------------------------------

    private record SignedUser(UUID id, String bearer) { }

    private SignedUser signup() {
        String email = "ar-" + UUID.randomUUID() + "@godance.test";
        AuthResponse resp = http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Auto-recharge Tester"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        UUID id = users.findByEmail(email).orElseThrow().getId();
        return new SignedUser(id, "Bearer " + resp.accessToken());
    }

    /** Skip the interactive Stripe Checkout flow — seed the customer + PM directly. */
    private void seedSavedPaymentMethod(UUID userId) {
        User u = users.findById(userId).orElseThrow();
        // Unique per-user IDs — users.stripe_customer_id has a UNIQUE constraint.
        u.setStripeCustomerId("cus_test_" + userId);
        u.setStripePaymentMethodId("pm_test_" + userId);
        users.save(u);
    }

    private void seedBalance(UUID userId, long cents) {
        users.findById(userId).ifPresent(u -> {
            u.setCreditBalanceCents(cents);
            users.save(u);
        });
    }

    // ---------- PUT /v1/billing/auto-recharge --------------------------

    @Test
    void putAutoRecharge_withoutSavedPaymentMethod_returns409() {
        SignedUser u = signup();
        http.put().uri("/v1/billing/auto-recharge")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", true,
                        "threshold_cents", 500, "topup_cents", 2000))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void putAutoRecharge_withPaymentMethod_persistsConfig() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());

        AutoRechargeConfigView view = http.put().uri("/v1/billing/auto-recharge")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", true,
                        "threshold_cents", 500, "topup_cents", 2000))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AutoRechargeConfigView.class).returnResult().getResponseBody();

        assertThat(view.enabled()).isTrue();
        assertThat(view.thresholdCents()).isEqualTo(500);
        assertThat(view.topupCents()).isEqualTo(2000);
        assertThat(view.paymentMethodOnFile()).isTrue();
    }

    @Test
    void putAutoRecharge_disable_clearsFlag() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());
        autoRecharge.enable(u.id(), 500, 2000, "127.0.0.1");

        AutoRechargeConfigView view = http.put().uri("/v1/billing/auto-recharge")
                .header("Authorization", u.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("enabled", false))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AutoRechargeConfigView.class).returnResult().getResponseBody();

        assertThat(view.enabled()).isFalse();
        assertThat(users.findById(u.id()).orElseThrow().isAutoRechargeEnabled()).isFalse();
    }

    // ---------- chargeIfNeeded direct ----------------------------------

    @Test
    void chargeIfNeeded_belowThreshold_firesOffSessionPaymentIntent_andCreditsLedger() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());
        autoRecharge.enable(u.id(), 500, 2000, null);
        seedBalance(u.id(), 100);   // 100¢ < 500¢ threshold

        Outcome outcome = autoRecharge.chargeIfNeeded(u.id());

        assertThat(outcome).isEqualTo(Outcome.CHARGED);
        User refreshed = users.findById(u.id()).orElseThrow();
        // 100 (seeded) + 2000 (topup) = 2100
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(2100L);
        stripeMock.verify(1, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlEqualTo("/v1/payment_intents")));
    }

    @Test
    void chargeIfNeeded_aboveThreshold_skipsAndChargesNothing() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());
        autoRecharge.enable(u.id(), 500, 2000, null);
        seedBalance(u.id(), 10_000);   // way above threshold

        assertThat(autoRecharge.chargeIfNeeded(u.id())).isEqualTo(Outcome.SKIPPED_ABOVE_THRESHOLD);
        stripeMock.verify(0, com.github.tomakehurst.wiremock.client.WireMock
                .postRequestedFor(urlEqualTo("/v1/payment_intents")));
    }

    @Test
    void chargeIfNeeded_stripeDeclines_disablesAutoRechargeAndAuditsFailure() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());
        autoRecharge.enable(u.id(), 500, 2000, null);
        seedBalance(u.id(), 100);

        // Replace the default success stub with a decline.
        stripeMock.resetAll();
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(402)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"error":{"type":"card_error",
                                          "code":"card_declined",
                                          "message":"Your card was declined."}}
                                """)));

        Outcome outcome = autoRecharge.chargeIfNeeded(u.id());

        assertThat(outcome).isEqualTo(Outcome.HARD_FAIL_DISABLED);
        User refreshed = users.findById(u.id()).orElseThrow();
        assertThat(refreshed.isAutoRechargeEnabled()).isFalse();
        assertThat(refreshed.getAutoRechargeLastFailureAt()).isNotNull();
        assertThat(refreshed.getAutoRechargeLastFailureReason()).contains("card_declined");
    }

    /**
     * Stripe answers 200 (no exception) but the off-session PaymentIntent
     * never reached {@code succeeded}. BillingService's off-session contract
     * treats any non-{@code succeeded} status as a hard fail — distinct from
     * the declined-card path above, which surfaces as a StripeException.
     */
    @Test
    void chargeIfNeeded_paymentIntentNotSucceeded_disablesAutoRechargeAndPersistsReason() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());
        autoRecharge.enable(u.id(), 500, 2000, null);
        seedBalance(u.id(), 100);

        stripeMock.resetAll();
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200)
                        .withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"pi_test_processing",
                                 "object":"payment_intent",
                                 "status":"processing",
                                 "amount":2000,
                                 "currency":"usd",
                                 "livemode":false}
                                """)));

        Outcome outcome = autoRecharge.chargeIfNeeded(u.id());

        assertThat(outcome).isEqualTo(Outcome.HARD_FAIL_DISABLED);
        User refreshed = users.findById(u.id()).orElseThrow();
        assertThat(refreshed.isAutoRechargeEnabled()).isFalse();
        assertThat(refreshed.getAutoRechargeLastFailureAt()).isNotNull();
        assertThat(refreshed.getAutoRechargeLastFailureReason()).contains("processing");
        // Hard fail short-circuits before any ledger credit — balance untouched.
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(100L);
    }

    // ---------- debit-driven path (via Quartz) -------------------------

    @Test
    void debit_belowThreshold_enqueuesAndChargesUserViaQuartz() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());
        autoRecharge.enable(u.id(), 500, 2000, null);
        seedBalance(u.id(), 600);   // 600 - 200 debit = 400 < 500 threshold

        boolean ok = ledger.debit(u.id(), 200, "req-debit-trigger");
        assertThat(ok).isTrue();

        // Quartz fires the job on its scheduler thread; poll until the balance jumps.
        await().atMost(Duration.ofSeconds(10)).untilAsserted(() -> {
            long bal = users.findById(u.id()).orElseThrow().getCreditBalanceCents();
            // After debit: 400. After auto-recharge: 400 + 2000 = 2400.
            assertThat(bal).isEqualTo(2400L);
        });
    }

    /** Confirms the Quartz path itself works when invoked directly, isolating
     *  any debit-driven hookup issue from the scheduler plumbing. */
    @Test
    void triggerMaybeEnqueue_directInvocation_chargesUserViaQuartz() {
        SignedUser u = signup();
        seedSavedPaymentMethod(u.id());
        autoRecharge.enable(u.id(), 500, 2000, null);
        seedBalance(u.id(), 100);   // below threshold

        boolean enqueued = trigger.maybeEnqueue(u.id());
        assertThat(enqueued).isTrue();

        await().atMost(Duration.ofSeconds(10)).untilAsserted(() ->
                assertThat(users.findById(u.id()).orElseThrow().getCreditBalanceCents())
                        .isEqualTo(2100L));   // 100 + 2000
    }
}
