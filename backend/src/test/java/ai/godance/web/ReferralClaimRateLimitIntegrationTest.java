package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.ThreadLocalRandom;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.repository.UserRepository;
import ai.godance.web.ReferralFixture.Account;

/**
 * Claims are limited per account and per client IP, before any lookup, so
 * codes cannot be found by trying them. Limits are off in the test profile;
 * this class turns them on, small, against the shared Redis. Every test
 * uses fresh random IPs and new accounts, so counters never carry over.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "server.forward-headers-strategy=framework",
        "godance.rate-limit.enabled=true",
        "godance.promo.referral.referrer-reward-cents=100",
        "godance.promo.referral.referee-reward-cents=100",
        "godance.promo.referral.claim-limit-per-account=2",
        "godance.promo.referral.claim-limit-per-ip=3"})
@AutoConfigureWebTestClient
@Testcontainers
class ReferralClaimRateLimitIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;

    private ReferralFixture fx;

    @BeforeEach
    void setUp() {
        fx = new ReferralFixture(http, users);
    }

    private static String randomClientIp() {
        ThreadLocalRandom r = ThreadLocalRandom.current();
        return "10." + r.nextInt(256) + "." + r.nextInt(256) + "." + (1 + r.nextInt(254));
    }

    @Test
    void claim_givenAccountPastItsLimit_expects429EvenForAValidCode() {
        Account referrer = fx.signup("rl-acct-r", randomClientIp());
        Account guesser = fx.signup("rl-acct", randomClientIp());
        String validCode = fx.codeOf(referrer);

        // Each try from a new address, so only the per-account counter fills.
        fx.claim(guesser.bearer(), ReferralFixture.randomCode(), randomClientIp()).expectStatus().isNotFound();
        fx.claim(guesser.bearer(), ReferralFixture.randomCode(), randomClientIp()).expectStatus().isNotFound();
        fx.claim(guesser.bearer(), validCode, randomClientIp())
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectHeader().contentType("application/problem+json");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM referrals WHERE invited_user_id = ?",
                Integer.class, guesser.id())).isZero();
    }

    @Test
    void claim_givenAddressPastItsLimit_expects429ForAnotherAccount() {
        String ip = randomClientIp();
        Account first = fx.signup("rl-ip-1", randomClientIp());
        Account second = fx.signup("rl-ip-2", randomClientIp());
        Account third = fx.signup("rl-ip-3", randomClientIp());

        fx.claim(first.bearer(), ReferralFixture.randomCode(), ip).expectStatus().isNotFound();
        fx.claim(second.bearer(), ReferralFixture.randomCode(), ip).expectStatus().isNotFound();
        fx.claim(third.bearer(), ReferralFixture.randomCode(), ip).expectStatus().isNotFound();
        fx.claim(third.bearer(), ReferralFixture.randomCode(), ip)
                .expectStatus().isEqualTo(429)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER);

        // The same account from elsewhere is still under its own limit.
        fx.claim(first.bearer(), ReferralFixture.randomCode(), randomClientIp()).expectStatus().isNotFound();
    }
}
