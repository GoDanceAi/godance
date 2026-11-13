package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.json.JsonCompareMode;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;

import ai.godance.repository.UserRepository;
import ai.godance.service.ReferralRewardService;
import ai.godance.web.ReferralFixture.Account;

/**
 * Referrals with the shipped settings: both rewards 0, so the feature is
 * off. Nothing about it may be visible or do anything: {@code /me} says
 * only that it is off and makes no code, a claim is refused, and no top-up
 * writes a REFERRAL credit or settles a referral, even one left pending
 * from a time it was on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class ReferralDisabledIntegrationTest {

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
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReferralRewardService rewards;

    private ReferralFixture fx;

    @BeforeEach
    void setUp() {
        stripeMock.resetAll();
        fx = new ReferralFixture(http, users);
    }

    private int count(String sql, Object... args) {
        Integer n = jdbc.queryForObject(sql, Integer.class, args);
        return n == null ? 0 : n;
    }

    @Test
    void me_givenDefaults_expectsOnlyEnabledFalseAndNoCodeMade() {
        Account a = fx.signup("off-me");

        fx.me(a).expectStatus().isOk()
                .expectBody().json("{\"enabled\":false}", JsonCompareMode.STRICT);

        assertThat(count("SELECT count(*) FROM referral_codes WHERE user_id = ?", a.id())).isZero();
    }

    @Test
    void claim_givenDefaults_expectsNotEligibleAndNoReferral() {
        Account referrer = fx.signup("off-referrer");
        Account invited = fx.signup("off-invited");
        // A code made while the feature was on.
        String code = ReferralFixture.randomCode();
        jdbc.update("INSERT INTO referral_codes (user_id, code) VALUES (?, ?)", referrer.id(), code);

        fx.claim(invited, code).expectStatus().isEqualTo(409)
                .expectHeader().contentType("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo("referral_not_eligible");

        assertThat(count("SELECT count(*) FROM referrals WHERE invited_user_id = ?", invited.id())).isZero();
        assertThat(count("SELECT count(*) FROM audit_logs WHERE event_type LIKE 'referral.%' AND user_id = ?",
                invited.id())).isZero();
    }

    @Test
    void topUps_givenDefaultsAndPendingReferral_expectsNoReferralCreditOnAnyTopUp() {
        Account referrer = fx.signup("off-r");
        Account invited = fx.signup("off-i");
        // Left pending from a time the feature was on.
        UUID referralId = UUID.randomUUID();
        jdbc.update("INSERT INTO referrals (id, referrer_id, invited_user_id, claimed_at, status) "
                + "VALUES (?, ?, ?, now(), 'PENDING')", referralId, referrer.id(), invited.id());

        fx.deliverStripe(ReferralFixture.paidCheckout(ReferralFixture.uniqueId("evt_"),
                ReferralFixture.uniqueId("cs_test_"), invited.id(), 50_00));
        fx.deliverIpn(ReferralFixture.finishedIpn(ReferralFixture.uniquePaymentId(), invited.id(), "50"));
        assertThat(rewards.sweep()).isZero();

        assertThat(users.findById(invited.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(100_00L);
        assertThat(users.findById(referrer.id()).orElseThrow().getCreditBalanceCents()).isZero();
        assertThat(count("SELECT count(*) FROM credit_ledger WHERE source = 'REFERRAL' AND user_id IN (?, ?)",
                invited.id(), referrer.id())).isZero();
        assertThat(jdbc.queryForObject("SELECT status FROM referrals WHERE id = ?", String.class, referralId))
                .isEqualTo("PENDING");
        assertThat(count("SELECT count(*) FROM audit_logs WHERE event_type LIKE 'referral.%' AND user_id IN (?, ?)",
                invited.id(), referrer.id())).isZero();
    }
}
