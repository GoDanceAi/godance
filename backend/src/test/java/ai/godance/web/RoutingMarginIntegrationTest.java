package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.CallOutcome;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * {@code GET /v1/admin/routing/margins} (round 4's Phase 2, round 14 brief
 * 04): per routing policy and rule, served requests, failed attempts, what
 * callers paid, what the served models cost and the spread, over a range of
 * days. Rows are written straight to {@code usage_records}, each test in days
 * of its own in the past, so the tests share one database without seeing each
 * other. Who may call the route is checked with every other admin route in
 * {@link AdminRolesIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class RoutingMarginIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    private static final String EMAIL = "routing-finance@godance.test";

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired UsageRecordRepository usage;

    private String financeBearer;
    private UUID financeId;

    @BeforeEach
    void signInFinance() {
        financeBearer = signUpOrLogIn(EMAIL);
        User finance = users.findByEmail(EMAIL).orElseThrow();
        finance.setRole(UserRole.FINANCE);
        finance.setEmailVerified(true);
        users.save(finance);
        financeId = finance.getId();
        TwoFactorFixture.turnOn(twoFactors, financeId);
    }

    /** The next test's sign-in is by password alone, so 2FA can't stay on. */
    @AfterEach
    void turnOffTwoFactor() {
        twoFactors.deleteAll();
    }

    @Test
    void margins_givenRoutedRequestsAcrossPoliciesAndRules_expectsOneRowEachWithItsSums() {
        Instant day = Instant.parse("2025-02-04T12:00:00Z");
        // godance-auto / hard-task: two served requests and one failed attempt.
        // Paid 105 + 252.5 = 357.5 cents, rounded once to 358; cost 100 + 200 = 300.
        routed("godance-auto", "hard-task", CallOutcome.OK, 1_050_000, 1_000_000, day);
        routed("godance-auto", "hard-task", CallOutcome.OK, 2_525_000, 2_000_000, day.plusSeconds(60));
        failedAttempt("godance-auto", "hard-task", day.plusSeconds(30));
        // godance-auto / short-chat: 2.5 cents paid rounds to 3, 1.4 cents of cost to 1.
        routed("godance-auto", "short-chat", CallOutcome.OK, 25_000, 14_000, day);
        // godance-fast / all-cheap, on the range's last day.
        routed("godance-fast", "all-cheap", CallOutcome.OK, 5_000_000, 4_000_000,
                Instant.parse("2025-02-06T23:59:59Z"));
        // Not in the view: a direct call, and routed requests either side of the range.
        direct(9_000_000, 1_000_000, day);
        routed("godance-fast", "all-cheap", CallOutcome.OK, 1_000_000, 0, Instant.parse("2025-02-03T23:59:59Z"));
        routed("godance-fast", "all-cheap", CallOutcome.OK, 1_000_000, 0, Instant.parse("2025-02-07T00:00:00Z"));

        margins("2025-02-04", "2025-02-06").expectStatus().isOk().expectBody()
                .jsonPath("$.from").isEqualTo("2025-02-04")
                .jsonPath("$.to").isEqualTo("2025-02-06")
                .jsonPath("$.rows.length()").isEqualTo(3)
                .jsonPath("$.rows[0].policy").isEqualTo("godance-auto")
                .jsonPath("$.rows[0].rule").isEqualTo("hard-task")
                .jsonPath("$.rows[0].requests").isEqualTo(2)
                .jsonPath("$.rows[0].failed_attempts").isEqualTo(1)
                .jsonPath("$.rows[0].paid_cents").isEqualTo(358)
                .jsonPath("$.rows[0].cost_cents").isEqualTo(300)
                .jsonPath("$.rows[0].spread_cents").isEqualTo(58)
                .jsonPath("$.rows[1].policy").isEqualTo("godance-auto")
                .jsonPath("$.rows[1].rule").isEqualTo("short-chat")
                .jsonPath("$.rows[1].requests").isEqualTo(1)
                .jsonPath("$.rows[1].failed_attempts").isEqualTo(0)
                .jsonPath("$.rows[1].paid_cents").isEqualTo(3)
                .jsonPath("$.rows[1].cost_cents").isEqualTo(1)
                .jsonPath("$.rows[1].spread_cents").isEqualTo(2)
                .jsonPath("$.rows[2].policy").isEqualTo("godance-fast")
                .jsonPath("$.rows[2].rule").isEqualTo("all-cheap")
                .jsonPath("$.rows[2].requests").isEqualTo(1)
                .jsonPath("$.rows[2].paid_cents").isEqualTo(500)
                .jsonPath("$.rows[2].cost_cents").isEqualTo(400)
                .jsonPath("$.rows[2].spread_cents").isEqualTo(100)
                // The total adds up the rows, so the table adds up.
                .jsonPath("$.total.requests").isEqualTo(4)
                .jsonPath("$.total.failed_attempts").isEqualTo(1)
                .jsonPath("$.total.paid_cents").isEqualTo(861)
                .jsonPath("$.total.cost_cents").isEqualTo(701)
                .jsonPath("$.total.spread_cents").isEqualTo(160);
    }

    @Test
    void margins_givenAModelCostingMoreThanItsCallerPaid_expectsANegativeSpread() {
        Instant day = Instant.parse("2025-03-11T08:00:00Z");
        routed("godance-quality", "premium-first", CallOutcome.OK, 1_000_000, 1_250_000, day);

        margins("2025-03-11", "2025-03-11").expectStatus().isOk().expectBody()
                .jsonPath("$.rows.length()").isEqualTo(1)
                .jsonPath("$.rows[0].paid_cents").isEqualTo(100)
                .jsonPath("$.rows[0].cost_cents").isEqualTo(125)
                .jsonPath("$.rows[0].spread_cents").isEqualTo(-25)
                .jsonPath("$.total.spread_cents").isEqualTo(-25);
    }

    @Test
    void margins_givenOnlyDirectCalls_expectsNoRowsAndAZeroTotal() {
        direct(1_000_000, 500_000, Instant.parse("2025-04-15T10:00:00Z"));

        margins("2025-04-15", "2025-04-15").expectStatus().isOk().expectBody()
                .jsonPath("$.rows.length()").isEqualTo(0)
                .jsonPath("$.total.requests").isEqualTo(0)
                .jsonPath("$.total.paid_cents").isEqualTo(0)
                .jsonPath("$.total.spread_cents").isEqualTo(0);
    }

    @Test
    void margins_givenNoRange_expectsTheLastThirtyDays() {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        margins(null, null).expectStatus().isOk().expectBody()
                .jsonPath("$.to").isEqualTo(today.toString())
                .jsonPath("$.from").isEqualTo(today.minusDays(29).toString());
    }

    @Test
    void margins_givenFromAfterTo_expects400() {
        margins("2025-05-02", "2025-05-01").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("from must be <= to");
    }

    @Test
    void margins_givenADateThatIsNotIso_expects400() {
        margins("02/05/2025", "2025-05-03").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("dates must be ISO format (yyyy-MM-dd)");
    }

    @Test
    void margins_givenMoreThanAYear_expects400() {
        margins("2024-01-01", "2025-06-01").expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").isEqualTo("window too large (max ~1 year)");
    }

    // ===== Helpers ======================================================

    private WebTestClient.ResponseSpec margins(String from, String to) {
        String query = (from == null ? "" : "?from=" + from + "&to=" + to);
        return http.get().uri("/v1/admin/routing/margins" + query)
                .header("Authorization", financeBearer)
                .exchange();
    }

    /** A served (OK) or failed record of a routed request. */
    private void routed(String policy, String rule, CallOutcome outcome,
                        long paidMicrocents, long costMicrocents, Instant at) {
        UsageRecord r = record(outcome, paidMicrocents, costMicrocents, at);
        r.setRoutedPolicy(policy);
        r.setRoutedRule(rule);
        usage.save(r);
    }

    /** A failed attempt, written as ChatCompletionService writes one: nothing charged, no microcents. */
    private void failedAttempt(String policy, String rule, Instant at) {
        UsageRecord r = record(CallOutcome.ERROR, 0, 0, at);
        r.setCostMicrocents(null);
        r.setProviderCostMicrocents(null);
        r.setRoutedPolicy(policy);
        r.setRoutedRule(rule);
        usage.save(r);
    }

    private void direct(long paidMicrocents, long costMicrocents, Instant at) {
        usage.save(record(CallOutcome.OK, paidMicrocents, costMicrocents, at));
    }

    private UsageRecord record(CallOutcome outcome, long paidMicrocents, long costMicrocents, Instant at) {
        UsageRecord r = new UsageRecord();
        r.setUserId(financeId);
        r.setModality(Modality.TEXT);
        r.setModelName("gpt-4o");
        r.setProvider(Provider.OPENAI);
        r.setInputTokens(1_000);
        r.setOutputTokens(500);
        r.setCostCents(paidMicrocents / 10_000);
        r.setCostMicrocents(paidMicrocents);
        r.setProviderCostCents((costMicrocents + 5_000) / 10_000);
        r.setProviderCostMicrocents(costMicrocents);
        r.setRegion(Region.LOCAL);
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setRequestId("req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24));
        r.setOutcome(outcome);
        r.setRecordedAt(at);
        return r;
    }

    private String signUpOrLogIn(String email) {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Routing Finance"))
                .exchange()
                .expectStatus().value(s -> assertThat(s).isIn(201, 409));
        AuthResponse resp = http.post().uri("/v1/auth/login")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple"))
                .exchange()
                .expectStatus().isOk()
                .expectBody(AuthResponse.class).returnResult().getResponseBody();
        return "Bearer " + resp.accessToken();
    }
}
