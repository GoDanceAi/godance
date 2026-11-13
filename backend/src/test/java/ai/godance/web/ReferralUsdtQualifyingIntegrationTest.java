package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.UserRepository;
import ai.godance.service.ReferralRewardService;
import ai.godance.web.ReferralFixture.Account;

/**
 * With USDT added to the qualifying sources, a finished NOWPayments top-up
 * pays the referral reward, once, however often its IPN arrives.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.promo.referral.referrer-reward-cents=100",
        "godance.promo.referral.referee-reward-cents=150",
        "godance.promo.referral.qualifying-sources=STRIPE,NOWPAYMENTS"})
@AutoConfigureWebTestClient
@Testcontainers
class ReferralUsdtQualifyingIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.nowpayments.ipn-secret", () -> ReferralFixture.IPN_SECRET);
    }

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired ReferralRewardService rewards;

    private ReferralFixture fx;

    @BeforeEach
    void setUp() {
        fx = new ReferralFixture(http, users);
    }

    private int referralCredits(Account a) {
        Integer n = jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE source = 'REFERRAL' AND user_id = ?",
                Integer.class, a.id());
        return n == null ? 0 : n;
    }

    @Test
    void handleNowPaymentsWebhook_givenUsdtQualifies_expectsBothSidesCreditedOnce() {
        Account referrer = fx.signup("usdt-on-r");
        Account invited = fx.signup("usdt-on-i");
        fx.invite(referrer, invited);
        String paymentId = ReferralFixture.uniquePaymentId();
        String ipn = ReferralFixture.finishedIpn(paymentId, invited.id(), "10");

        fx.deliverIpn(ipn);
        fx.deliverIpn(ipn);                              // NOWPayments redelivers
        assertThat(rewards.sweep()).isZero();

        assertThat(users.findById(invited.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(1000 + 150);
        assertThat(users.findById(referrer.id()).orElseThrow().getCreditBalanceCents()).isEqualTo(100);
        assertThat(referralCredits(invited)).isEqualTo(1);
        assertThat(referralCredits(referrer)).isEqualTo(1);
        Map<String, Object> referral = jdbc.queryForMap(
                "SELECT r.status, c.idempotency_key AS topup_key FROM referrals r "
                        + "JOIN credit_ledger c ON c.id = r.rewarded_topup_id WHERE r.invited_user_id = ?",
                invited.id());
        assertThat(referral).containsEntry("status", "REWARDED").containsEntry("topup_key", paymentId);
    }
}
