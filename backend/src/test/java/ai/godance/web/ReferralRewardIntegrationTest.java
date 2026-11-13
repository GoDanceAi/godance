package ai.godance.web;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;

import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.AutoRechargeService;
import ai.godance.service.CreditLedgerService;
import ai.godance.service.ReferralRewardService;
import ai.godance.web.ReferralFixture.Account;

/**
 * The referral reward with the feature on: which top-ups pay it, that it
 * is paid once whatever arrives twice, and that it never gets in the way
 * of the top-up. Card top-ups and USDT top-ups arrive as signed webhooks;
 * an automatic recharge runs through {@link AutoRechargeService} against a
 * stubbed Stripe. USDT does not qualify here (the default);
 * {@link ReferralUsdtQualifyingIntegrationTest} turns it on.
 *
 * <p>The monthly limit is 1, so a referrer's second reward in a month is
 * over it; every other test uses a referrer of its own.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.promo.referral.referrer-reward-cents=" + ReferralRewardIntegrationTest.REFERRER_CENTS,
        "godance.promo.referral.referee-reward-cents=" + ReferralRewardIntegrationTest.REFEREE_CENTS,
        "godance.promo.referral.min-topup-cents=1000",
        "godance.promo.referral.monthly-reward-limit=1"})
@AutoConfigureWebTestClient
@Testcontainers
class ReferralRewardIntegrationTest {

    static final long REFERRER_CENTS = 100;
    static final long REFEREE_CENTS = 150;

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
        r.add("godance.stripe.webhook-secret", () -> ReferralFixture.STRIPE_WEBHOOK_SECRET);
        r.add("godance.nowpayments.ipn-secret", () -> ReferralFixture.IPN_SECRET);
        r.add("godance.scheduling.auto-recharge-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerRepository ledgerEntries;
    @Autowired AuditLogEntryRepository audits;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReferralRewardService rewards;
    @Autowired AutoRechargeService autoRecharge;
    @Autowired PlatformTransactionManager txManager;
    /** A spy, so one test can make a reward's ledger write fail. */
    @MockitoSpyBean CreditLedgerService ledgerService;

    private ReferralFixture fx;

    @BeforeEach
    void setUp() {
        stripeMock.resetAll();
        fx = new ReferralFixture(http, users);
    }

    // ===== Helpers ======================================================

    /** A paid Checkout top-up for {@code who}; returns the ledger key (the event id). */
    private String cardTopUp(Account who, long cents) {
        String eventId = ReferralFixture.uniqueId("evt_");
        fx.deliverStripe(ReferralFixture.paidCheckout(eventId, ReferralFixture.uniqueId("cs_test_"), who.id(), cents));
        return eventId;
    }

    private long balance(Account a) {
        return users.findById(a.id()).orElseThrow().getCreditBalanceCents();
    }

    private List<CreditLedger> referralCredits(Account a) {
        return ledgerEntries.findAll().stream()
                .filter(e -> e.getUserId().equals(a.id()) && e.getSource() == CreditSource.REFERRAL)
                .toList();
    }

    private Map<String, Object> referralOf(Account invited) {
        return jdbc.queryForMap("SELECT * FROM referrals WHERE invited_user_id = ?", invited.id());
    }

    private List<AuditLogEntry> auditsFor(String eventType, Account a) {
        return audits.findByEventType(eventType).stream()
                .filter(e -> a.id().equals(e.getUserId()))
                .toList();
    }

    private void assertNoReferralCredit(Account... accounts) {
        for (Account a : accounts) {
            assertThat(referralCredits(a)).as("REFERRAL credits for %s", a.email()).isEmpty();
        }
    }

    /** Both sides credited once, for {@code topUpKey}'s top-up. */
    private void assertRewardedOnce(Account referrer, Account invited, String topUpKey) {
        UUID referralId = (UUID) referralOf(invited).get("id");
        assertThat(referralCredits(invited)).singleElement().satisfies(e -> {
            assertThat(e.getDeltaCents()).isEqualTo(REFEREE_CENTS);
            assertThat(e.getIdempotencyKey()).isEqualTo("referral-" + referralId + "-referee");
            assertThat(e.getSourceRef()).isEqualTo(referralId.toString());
        });
        assertThat(referralCredits(referrer)).singleElement().satisfies(e -> {
            assertThat(e.getDeltaCents()).isEqualTo(REFERRER_CENTS);
            assertThat(e.getIdempotencyKey()).isEqualTo("referral-" + referralId + "-referrer");
        });
        Map<String, Object> referral = referralOf(invited);
        assertThat(referral.get("status")).isEqualTo("REWARDED");
        assertThat(referral.get("rewarded_at")).isNotNull();
        assertThat(referral.get("rewarded_topup_id"))
                .isEqualTo(ledgerEntries.findByIdempotencyKey(topUpKey).orElseThrow().getId());
        assertThat(((Number) referral.get("referrer_reward_cents")).longValue()).isEqualTo(REFERRER_CENTS);
        assertThat(((Number) referral.get("referee_reward_cents")).longValue()).isEqualTo(REFEREE_CENTS);
    }

    // ===== A qualifying card top-up =====================================

    @Test
    void handleWebhook_givenQualifyingCardTopUp_expectsBothSidesCreditedOnceAndAudited() {
        Account referrer = fx.signup("card-r");
        Account invited = fx.signup("card-i");
        fx.invite(referrer, invited);

        String key = cardTopUp(invited, 1000);

        assertRewardedOnce(referrer, invited, key);
        assertThat(balance(invited)).isEqualTo(1000 + REFEREE_CENTS);
        assertThat(balance(referrer)).isEqualTo(REFERRER_CENTS);
        assertThat(auditsFor(AuditLogService.EVT_REFERRAL_REWARDED, invited)).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail())
                        .containsEntry("referrerRewardCents", (int) REFERRER_CENTS)
                        .containsEntry("refereeRewardCents", (int) REFEREE_CENTS));
        assertThat(fx.meBody(referrer)).containsEntry("rewarded", 1)
                .containsEntry("earned_cents", (int) REFERRER_CENTS);
        assertThat(fx.meBody(invited)).containsEntry("pending_reward_cents", null);
    }

    @Test
    void handleWebhook_givenRedeliveredAndSecondEventForSameSession_expectsNothingMore() {
        Account referrer = fx.signup("redeliver-r");
        Account invited = fx.signup("redeliver-i");
        fx.invite(referrer, invited);
        String eventId = ReferralFixture.uniqueId("evt_");
        String sessionId = ReferralFixture.uniqueId("cs_test_");
        String payload = ReferralFixture.paidCheckout(eventId, sessionId, invited.id(), 2500);

        fx.deliverStripe(payload);
        fx.deliverStripe(payload);
        fx.deliverStripe(ReferralFixture.checkoutEvent("checkout.session.async_payment_succeeded",
                ReferralFixture.uniqueId("evt_"), sessionId, invited.id(), 2500, "paid", false));
        assertThat(rewards.sweep()).isZero();

        assertRewardedOnce(referrer, invited, eventId);
        assertThat(balance(invited)).isEqualTo(2500 + REFEREE_CENTS);
        assertThat(balance(referrer)).isEqualTo(REFERRER_CENTS);
        assertThat(auditsFor(AuditLogService.EVT_REFERRAL_REWARDED, invited)).hasSize(1);
    }

    @Test
    void handleWebhook_givenLaterTopUpAfterReward_expectsNoSecondReward() {
        Account referrer = fx.signup("later-r");
        Account invited = fx.signup("later-i");
        fx.invite(referrer, invited);
        String first = cardTopUp(invited, 1000);

        cardTopUp(invited, 5000);

        assertRewardedOnce(referrer, invited, first);
    }

    // ===== Below the minimum ============================================

    @Test
    void handleWebhook_givenTopUpBelowMinimum_expectsPendingThenRewardedOnLaterQualifyingOne() {
        Account referrer = fx.signup("small-r");
        Account invited = fx.signup("small-i");
        fx.invite(referrer, invited);

        cardTopUp(invited, 999);

        assertNoReferralCredit(referrer, invited);
        assertThat(referralOf(invited).get("status")).isEqualTo("PENDING");
        assertThat(auditsFor(AuditLogService.EVT_REFERRAL_SKIPPED, invited)).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("reason", "below-minimum"));
        assertThat(rewards.sweep()).isZero();
        assertThat(fx.meBody(invited)).containsEntry("pending_reward_cents", (int) REFEREE_CENTS);

        String qualifying = cardTopUp(invited, 1000);

        assertRewardedOnce(referrer, invited, qualifying);
        assertThat(balance(invited)).isEqualTo(999 + 1000 + REFEREE_CENTS);
    }

    // ===== What never counts ============================================

    @Test
    void handleWebhook_givenParentalConsentPayment_expectsNoRewardFromWebhookOrSweep() {
        Account referrer = fx.signup("consent-r");
        // A child's account, waiting for a parent: it can't claim a code, so
        // the referral is written directly, as if it had slipped through.
        String childEmail = "consent-child-" + UUID.randomUUID() + "@godance.test";
        Map<String, Object> body = new HashMap<>(Map.of("email", childEmail, "password", ReferralFixture.PASSWORD,
                "fullName", "Kim Lee", "parentEmail", "parent-" + UUID.randomUUID() + "@family.test"));
        body.put("birthMonth", YearMonth.now(ZoneOffset.UTC).minusYears(12).toString());
        http.post().uri("/v1/auth/signup").contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .exchange().expectStatus().isCreated();
        UUID childId = users.findByEmail(childEmail).orElseThrow().getId();
        Account child = new Account(childId, childEmail, "Kim Lee", null);
        jdbc.update("INSERT INTO referrals (id, referrer_id, invited_user_id, claimed_at, status) "
                + "VALUES (?, ?, ?, now(), 'PENDING')", UUID.randomUUID(), referrer.id(), childId);

        fx.deliverStripe(ReferralFixture.checkoutEvent("checkout.session.completed",
                ReferralFixture.uniqueId("evt_"), ReferralFixture.uniqueId("cs_test_"), childId, 2000, "paid", true));

        assertThat(users.findById(childId).orElseThrow().getAgeStatus().name()).isEqualTo("CONSENT_GRANTED");
        assertThat(balance(child)).isEqualTo(2000);
        assertNoReferralCredit(referrer, child);
        assertThat(referralOf(child).get("status")).isEqualTo("PENDING");

        rewards.sweep();

        assertNoReferralCredit(referrer, child);
        assertThat(referralOf(child).get("status")).isEqualTo("VOID");
        assertThat(balance(referrer)).isZero();
    }

    @Test
    void chargeIfNeeded_givenAutomaticRecharge_expectsNoRewardThenCardTopUpRewards() {
        Account referrer = fx.signup("recharge-r");
        Account invited = fx.signup("recharge-i");
        fx.invite(referrer, invited);
        cardTopUp(invited, 500);                     // below the minimum: still pending
        jdbc.update("UPDATE users SET stripe_customer_id = ?, stripe_payment_method_id = 'pm_test_card', "
                + "auto_recharge_enabled = true, auto_recharge_threshold_cents = 1000, "
                + "auto_recharge_topup_cents = 2000 WHERE id = ?", "cus_test_" + invited.id(), invited.id());
        stripeMock.stubFor(get(urlPathMatching("/v1/payment_methods/.+"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("{\"id\":\"pm_test_card\",\"object\":\"payment_method\",\"type\":\"card\",\"livemode\":false}")));
        String paymentIntentId = ReferralFixture.uniqueId("pi_test_");
        stripeMock.stubFor(post(urlEqualTo("/v1/payment_intents"))
                .willReturn(aResponse().withStatus(200).withHeader("content-type", "application/json")
                        .withBody("""
                                {"id":"%s","object":"payment_intent","status":"succeeded","amount":2000,
                                 "currency":"usd","livemode":false}
                                """.formatted(paymentIntentId))));

        assertThat(autoRecharge.chargeIfNeeded(invited.id())).isEqualTo(AutoRechargeService.Outcome.CHARGED);
        assertThat(rewards.sweep()).isZero();

        assertThat(balance(invited)).isEqualTo(500 + 2000);
        assertThat(ledgerEntries.findAll()).anySatisfy(e -> {
            assertThat(e.getUserId()).isEqualTo(invited.id());
            assertThat(e.getSourceRef()).isEqualTo(paymentIntentId);
            assertThat(e.getDeltaCents()).isEqualTo(2000);
        });
        assertNoReferralCredit(referrer, invited);
        assertThat(referralOf(invited).get("status")).isEqualTo("PENDING");

        String qualifying = cardTopUp(invited, 1000);

        assertRewardedOnce(referrer, invited, qualifying);
    }

    @Test
    void handleNowPaymentsWebhook_givenUsdtTopUpByDefault_expectsNoRewardAndSkipAudited() {
        Account referrer = fx.signup("usdt-r");
        Account invited = fx.signup("usdt-i");
        fx.invite(referrer, invited);

        fx.deliverIpn(ReferralFixture.finishedIpn(ReferralFixture.uniquePaymentId(), invited.id(), "25"));
        assertThat(rewards.sweep()).isZero();

        assertThat(balance(invited)).isEqualTo(2500);
        assertNoReferralCredit(referrer, invited);
        assertThat(referralOf(invited).get("status")).isEqualTo("PENDING");
        assertThat(auditsFor(AuditLogService.EVT_REFERRAL_SKIPPED, invited)).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("reason", "source-not-qualifying"));
    }

    // ===== The monthly limit ============================================

    @Test
    void handleWebhook_givenReferrerPastMonthlyLimit_expectsInvitedCreditedAndReferrerNot() {
        Account referrer = fx.signup("limit-r");
        Account first = fx.signup("limit-a");
        Account second = fx.signup("limit-b");
        fx.invite(referrer, first);
        fx.invite(referrer, second);

        cardTopUp(first, 1000);
        cardTopUp(second, 1000);

        assertThat(balance(first)).isEqualTo(1000 + REFEREE_CENTS);
        assertThat(balance(second)).isEqualTo(1000 + REFEREE_CENTS);
        assertThat(balance(referrer)).isEqualTo(REFERRER_CENTS);          // the first reward only
        assertThat(referralCredits(referrer)).hasSize(1);
        Map<String, Object> overLimit = referralOf(second);
        assertThat(overLimit.get("status")).isEqualTo("REWARDED");
        assertThat(((Number) overLimit.get("referrer_reward_cents")).longValue()).isZero();
        assertThat(((Number) overLimit.get("referee_reward_cents")).longValue()).isEqualTo(REFEREE_CENTS);
        assertThat(auditsFor(AuditLogService.EVT_REFERRAL_SKIPPED, referrer)).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("reason", "monthly-limit")
                        .doesNotContainValue(second.id().toString()));
        assertThat(fx.meBody(referrer)).containsEntry("invited", 2).containsEntry("rewarded", 2)
                .containsEntry("earned_cents", (int) REFERRER_CENTS);
    }

    // ===== Never in the payment's way, and not lost =====================

    @Test
    void handleWebhook_givenRewardFails_expectsTopUpAppliedThenSweepPaysOnce() {
        Account referrer = fx.signup("fail-r");
        Account invited = fx.signup("fail-i");
        fx.invite(referrer, invited);
        // The second credit fails, after the first was written in the same transaction.
        doThrow(new IllegalStateException("ledger unavailable")).when(ledgerService)
                .topUp(eq(referrer.id()), anyLong(), eq(CreditSource.REFERRAL), any(), any(), any());

        String key = ReferralFixture.uniqueId("evt_");
        String payload = ReferralFixture.paidCheckout(key, ReferralFixture.uniqueId("cs_test_"), invited.id(), 1000);
        fx.deliverStripe(payload);                       // still answers 200

        assertThat(ledgerEntries.findByIdempotencyKey(key)).isPresent();
        assertThat(balance(invited)).isEqualTo(1000);
        assertNoReferralCredit(referrer, invited);     // the invited side rolled back with it
        assertThat(referralOf(invited).get("status")).isEqualTo("PENDING");
        assertThat(auditsFor(AuditLogService.EVT_REFERRAL_FAILED, invited)).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsEntry("error", "IllegalStateException"));

        Mockito.reset(ledgerService);
        assertThat(rewards.sweep()).isEqualTo(1);
        assertThat(rewards.sweep()).isZero();
        fx.deliverStripe(payload);                       // a redelivery after the sweep paid

        assertRewardedOnce(referrer, invited, key);
        assertThat(balance(invited)).isEqualTo(1000 + REFEREE_CENTS);
        assertThat(balance(referrer)).isEqualTo(REFERRER_CENTS);
    }

    @Test
    void afterTopUp_givenCallerTransactionRollsBack_expectsNoReward() {
        Account referrer = fx.signup("rollback-r");
        Account invited = fx.signup("rollback-i");
        fx.invite(referrer, invited);
        String key = ReferralFixture.uniqueId("evt_");

        TransactionTemplate tx = new TransactionTemplate(txManager);
        tx.executeWithoutResult(status -> {
            ledgerService.topUp(invited.id(), 1000, CreditSource.STRIPE, ReferralFixture.uniqueId("cs_test_"), key, "test");
            rewards.afterTopUp(invited.id(), key);
            status.setRollbackOnly();
        });

        assertThat(ledgerEntries.findByIdempotencyKey(key)).isEmpty();
        assertNoReferralCredit(referrer, invited);
        assertThat(referralOf(invited).get("status")).isEqualTo("PENDING");
    }
}
