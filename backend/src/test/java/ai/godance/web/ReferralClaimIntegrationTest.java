package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.test.web.reactive.server.WebTestClient.ResponseSpec;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.CreditSource;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.AuditLogService;
import ai.godance.service.CreditLedgerService;
import ai.godance.web.ReferralFixture.Account;
import ai.godance.web.dto.ApiKeyDtos.CreateApiKeyResponse;

/**
 * {@code GET /v1/referrals/me} and {@code POST /v1/referrals/claim} with the
 * feature on: a code made once and shown with counts only, a claim accepted
 * when every condition holds, and refused, with its problem code, when any
 * one does not. Rate limits are off here (the test profile);
 * {@link ReferralClaimRateLimitIntegrationTest} turns them on.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.promo.referral.referrer-reward-cents=100",
        "godance.promo.referral.referee-reward-cents=150"})
@AutoConfigureWebTestClient
@Testcontainers
class ReferralClaimIntegrationTest {

    private static final Set<String> CONTRACT_FIELDS = Set.of("enabled", "code", "referrer_reward_cents",
            "referee_reward_cents", "min_topup_cents", "invited", "rewarded", "earned_cents",
            "pending_reward_cents");

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired JdbcTemplate jdbc;
    @Autowired CreditLedgerService ledger;
    @Autowired AuditLogEntryRepository audits;

    private ReferralFixture fx;

    @BeforeEach
    void setUp() {
        fx = new ReferralFixture(http, users);
    }

    private static void expectRefused(ResponseSpec response, int status, String code) {
        response.expectStatus().isEqualTo(status)
                .expectHeader().contentType("application/problem+json")
                .expectBody().jsonPath("$.code").isEqualTo(code);
    }

    private int referralsInvolving(UUID userId) {
        Integer n = jdbc.queryForObject(
                "SELECT count(*) FROM referrals WHERE invited_user_id = ? OR referrer_id = ?",
                Integer.class, userId, userId);
        return n == null ? 0 : n;
    }

    private void setAgeStatus(UUID userId, String status) {
        jdbc.update("UPDATE users SET age_status = ? WHERE id = ?", status, userId);
    }

    // ===== GET /v1/referrals/me =======================================

    @Test
    void me_givenFeatureOn_expectsCodeMadeOnceAndTheContractFields() {
        Account a = fx.signup("me");

        Map<String, Object> first = fx.meBody(a);
        Map<String, Object> second = fx.meBody(a);

        assertThat(first.keySet()).isEqualTo(CONTRACT_FIELDS);
        assertThat((String) first.get("code")).matches("[" + ReferralFixture.CODE_ALPHABET + "]{8}");
        assertThat(second.get("code")).isEqualTo(first.get("code"));
        assertThat(first).containsEntry("enabled", true)
                .containsEntry("referrer_reward_cents", 100)
                .containsEntry("referee_reward_cents", 150)
                .containsEntry("min_topup_cents", 1000)
                .containsEntry("invited", 0)
                .containsEntry("rewarded", 0)
                .containsEntry("earned_cents", 0)
                .containsEntry("pending_reward_cents", null);
    }

    @Test
    void me_givenAccountNotAdult_expectsFeatureOffAndNoCode() {
        Account a = fx.signup("me-minor");
        setAgeStatus(a.id(), "CONSENT_GRANTED");

        Map<String, Object> body = fx.meBody(a);

        assertThat(body).isEqualTo(Map.of("enabled", false));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM referral_codes WHERE user_id = ?",
                Integer.class, a.id())).isZero();
    }

    @Test
    void me_givenInvitedUsers_expectsReferrerSeesCountsAndNothingAboutThem() {
        Account referrer = fx.signup("counts-r");
        Account first = fx.signup("counts-a");
        Account second = fx.signup("counts-b");
        fx.invite(referrer, first);
        fx.invite(referrer, second);

        String raw = fx.me(referrer).expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        Map<String, Object> body = fx.meBody(referrer);

        assertThat(body.keySet()).isEqualTo(CONTRACT_FIELDS);
        assertThat(body).containsEntry("invited", 2).containsEntry("rewarded", 0)
                .containsEntry("earned_cents", 0).containsEntry("pending_reward_cents", null);
        for (Account invited : List.of(first, second)) {
            assertThat(raw).doesNotContain(invited.id().toString(), invited.email(), invited.fullName());
        }
    }

    // ===== POST /v1/referrals/claim: accepted ==========================

    @Test
    void claim_givenEveryConditionHolds_expectsAcceptedPendingAndAudited() {
        Account referrer = fx.signup("ok-r");
        Account invited = fx.signup("ok-i");

        fx.claim(invited, fx.codeOf(referrer)).expectStatus().isOk()
                .expectBody().json("{\"referee_reward_cents\":150,\"min_topup_cents\":1000}");

        assertThat(jdbc.queryForObject("SELECT status FROM referrals WHERE invited_user_id = ? AND referrer_id = ?",
                String.class, invited.id(), referrer.id())).isEqualTo("PENDING");
        assertThat(fx.meBody(invited)).containsEntry("pending_reward_cents", 150);
        assertThat(fx.meBody(referrer)).containsEntry("invited", 1).containsEntry("rewarded", 0);
        List<AuditLogEntry> claimed = audits.findByEventType(AuditLogService.EVT_REFERRAL_CLAIMED).stream()
                .filter(e -> invited.id().equals(e.getUserId()))
                .toList();
        assertThat(claimed).singleElement()
                .satisfies(e -> assertThat(e.getEventDetail()).containsKey("referralId")
                        .doesNotContainValue(referrer.id().toString()));
    }

    @Test
    void claim_givenLowerCaseCodeWithSpaces_expectsAccepted() {
        Account referrer = fx.signup("case-r");
        Account invited = fx.signup("case-i");

        fx.claim(invited, "  " + fx.codeOf(referrer).toLowerCase() + " ").expectStatus().isOk();

        assertThat(referralsInvolving(invited.id())).isEqualTo(1);
    }

    @Test
    void claim_givenOnlyAPromotionalCredit_expectsAccepted() {
        // The signup credit is not a payment: it doesn't make a paying customer.
        Account referrer = fx.signup("promo-r");
        Account invited = fx.signup("promo-i");
        ledger.topUp(invited.id(), 100, CreditSource.PROMO, "signup-credit", "promo-signup-" + invited.id(), "seed");

        fx.claim(invited, fx.codeOf(referrer)).expectStatus().isOk();
    }

    // ===== POST /v1/referrals/claim: refused, one condition at a time ==

    @Test
    void claim_givenCodeNobodyHolds_expectsCodeUnknown() {
        Account invited = fx.signup("unknown");

        expectRefused(fx.claim(invited, ReferralFixture.randomCode()), 404, "referral_code_unknown");

        assertThat(referralsInvolving(invited.id())).isZero();
    }

    @Test
    void claim_givenMalformedCode_expectsCodeUnknown() {
        Account invited = fx.signup("malformed");

        expectRefused(fx.claim(invited, "K7QM2XP0"), 404, "referral_code_unknown");
        expectRefused(fx.claim(invited, "short"), 404, "referral_code_unknown");
        expectRefused(fx.claim(invited, ""), 404, "referral_code_unknown");
    }

    @Test
    void claim_givenOwnCode_expectsNotEligible() {
        Account a = fx.signup("own");

        expectRefused(fx.claim(a, fx.codeOf(a)), 409, "referral_not_eligible");

        assertThat(referralsInvolving(a.id())).isZero();
    }

    @Test
    void claim_givenReferrerAccountInactive_expectsCodeUnknown() {
        Account referrer = fx.signup("closed-r");
        Account invited = fx.signup("closed-i");
        String code = fx.codeOf(referrer);
        jdbc.update("UPDATE users SET is_active = false WHERE id = ?", referrer.id());

        expectRefused(fx.claim(invited, code), 404, "referral_code_unknown");

        assertThat(referralsInvolving(invited.id())).isZero();
    }

    @Test
    void claim_givenAccountOlderThanClaimWindow_expectsNotEligible() {
        Account referrer = fx.signup("old-r");
        Account invited = fx.signup("old-i");
        jdbc.update("UPDATE users SET created_at = now() - interval '169 hours' WHERE id = ?", invited.id());

        expectRefused(fx.claim(invited, fx.codeOf(referrer)), 409, "referral_not_eligible");

        assertThat(referralsInvolving(invited.id())).isZero();
    }

    @Test
    void claim_givenAccountJustInsideClaimWindow_expectsAccepted() {
        Account referrer = fx.signup("window-r");
        Account invited = fx.signup("window-i");
        jdbc.update("UPDATE users SET created_at = now() - interval '167 hours' WHERE id = ?", invited.id());

        fx.claim(invited, fx.codeOf(referrer)).expectStatus().isOk();
    }

    @Test
    void claim_givenAccountAlreadyInvited_expectsNotEligibleAndFirstReferralKept() {
        Account first = fx.signup("twice-r1");
        Account second = fx.signup("twice-r2");
        Account invited = fx.signup("twice-i");
        fx.invite(first, invited);

        expectRefused(fx.claim(invited, fx.codeOf(second)), 409, "referral_not_eligible");
        expectRefused(fx.claim(invited, fx.codeOf(first)), 409, "referral_not_eligible");

        assertThat(jdbc.queryForObject("SELECT referrer_id FROM referrals WHERE invited_user_id = ?",
                UUID.class, invited.id())).isEqualTo(first.id());
        assertThat(referralsInvolving(second.id())).isZero();
    }

    @Test
    void claim_givenCardTopUpAlready_expectsNotEligible() {
        Account referrer = fx.signup("paid-r");
        Account invited = fx.signup("paid-i");
        ledger.topUp(invited.id(), 1000, CreditSource.STRIPE, "cs_test_paid_" + invited.id(),
                "evt_test_paid_" + invited.id(), "seed");

        expectRefused(fx.claim(invited, fx.codeOf(referrer)), 409, "referral_not_eligible");

        assertThat(referralsInvolving(invited.id())).isZero();
    }

    @Test
    void claim_givenUsdtTopUpAlready_expectsNotEligible() {
        Account referrer = fx.signup("usdt-paid-r");
        Account invited = fx.signup("usdt-paid-i");
        String paymentId = ReferralFixture.uniquePaymentId();
        ledger.topUp(invited.id(), 100, CreditSource.NOWPAYMENTS, paymentId, paymentId, "seed");

        expectRefused(fx.claim(invited, fx.codeOf(referrer)), 409, "referral_not_eligible");
    }

    @Test
    void claim_givenInvitedAccountNotAdult_expectsNotEligible() {
        Account referrer = fx.signup("minor-i-r");
        Account invited = fx.signup("minor-i");
        String code = fx.codeOf(referrer);
        setAgeStatus(invited.id(), "CONSENT_GRANTED");

        expectRefused(fx.claim(invited, code), 409, "referral_not_eligible");

        assertThat(referralsInvolving(invited.id())).isZero();
    }

    @Test
    void claim_givenReferrerNotAdult_expectsNotEligible() {
        Account referrer = fx.signup("minor-r");
        Account invited = fx.signup("minor-r-i");
        String code = fx.codeOf(referrer);
        setAgeStatus(referrer.id(), "CONSENT_GRANTED");

        expectRefused(fx.claim(invited, code), 409, "referral_not_eligible");

        assertThat(referralsInvolving(invited.id())).isZero();
    }

    @Test
    void claim_givenCodeOwnerInvitedByCaller_expectsNotEligible() {
        // Two accounts inviting each other would each earn both rewards.
        Account a = fx.signup("mutual-a");
        Account b = fx.signup("mutual-b");
        fx.invite(a, b);

        expectRefused(fx.claim(a, fx.codeOf(b)), 409, "referral_not_eligible");

        assertThat(jdbc.queryForObject("SELECT count(*) FROM referrals WHERE invited_user_id = ?",
                Integer.class, a.id())).isZero();
    }

    // ===== Sessions only ================================================

    @Test
    void endpoints_givenApiKey_expectsForbiddenAndNothingWritten() {
        Account owner = fx.signup("apikey");
        Account referrer = fx.signup("apikey-r");
        String code = fx.codeOf(referrer);
        CreateApiKeyResponse key = http.post().uri("/v1/keys")
                .header("Authorization", owner.bearer())
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("name", "referral-test-key"))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(CreateApiKeyResponse.class).returnResult().getResponseBody();
        String keyBearer = "Bearer " + key.plaintext();

        http.get().uri("/v1/referrals/me").header("Authorization", keyBearer).exchange()
                .expectStatus().isForbidden();
        fx.claim(keyBearer, code, null).expectStatus().isForbidden();

        assertThat(jdbc.queryForObject("SELECT count(*) FROM referral_codes WHERE user_id = ?",
                Integer.class, owner.id())).isZero();
        assertThat(referralsInvolving(owner.id())).isZero();
    }

    @Test
    void endpoints_givenNoSession_expectsUnauthorized() {
        http.get().uri("/v1/referrals/me").exchange().expectStatus().isUnauthorized();
        http.post().uri("/v1/referrals/claim")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("code", ReferralFixture.randomCode()))
                .exchange()
                .expectStatus().isUnauthorized();
    }
}
