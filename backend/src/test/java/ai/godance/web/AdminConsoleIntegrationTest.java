package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webtestclient.autoconfigure.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
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
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
import ai.godance.repository.UserTwoFactorRepository;
import ai.godance.service.AuditLogService;
import ai.godance.web.dto.AuthDtos.AuthResponse;

/**
 * Admin console user/overview surface: {@code /v1/admin/me}, user
 * search, paged ledger, credit adjustment (with audit + idempotency),
 * and the usage summary. Every endpoint is checked for the allowlist
 * 403 on a non-admin JWT — same harness as
 * {@link AdminPricingFlowIntegrationTest}.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@AutoConfigureWebTestClient
@Testcontainers
class AdminConsoleIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @DynamicPropertySource
    static void props(DynamicPropertyRegistry r) {
        r.add("godance.admin.emails", () -> "admin@godance.test");
        r.add("godance.pricing.refresh-cron", () -> "0 0 0 1 1 ? 2099");
    }

    @Autowired UserTwoFactorRepository twoFactors;
    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerRepository ledger;
    @Autowired UsageRecordRepository usageRecords;
    @Autowired AuditLogEntryRepository audit;

    private String adminBearer;
    private String userBearer;
    private String userEmail;
    private UUID userId;

    /** The next test's sign-in is by password alone, so 2FA can't stay on. */
    @AfterEach
    void turnOffTwoFactor() {
        twoFactors.deleteAll();
    }

    @BeforeEach
    void seedAccounts() {
        adminBearer = signUpOrLogIn("admin@godance.test");
        markEmailVerified("admin@godance.test");
        TwoFactorFixture.turnOn(twoFactors, users.findByEmail("admin@godance.test").orElseThrow().getId());
        userEmail = "user-" + UUID.randomUUID() + "@godance.test";
        userBearer = signUpOrLogIn(userEmail);
        userId = users.findByEmail(userEmail).orElseThrow().getId();
    }

    // ===== /v1/admin/me =================================================

    @Test
    void me_givenAdminCaller_returnsEmail() {
        http.get().uri("/v1/admin/me")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.email").isEqualTo("admin@godance.test");
    }

    @Test
    void me_givenNonAdminCaller_returns403() {
        http.get().uri("/v1/admin/me")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== User search ==================================================

    @Test
    void searchUsers_givenEmailSubstring_returnsMatchWithBalanceAndKeyCount() {
        http.get().uri("/v1/admin/users?q=" + userEmail.substring(0, 20))
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.users[0].email").isEqualTo(userEmail)
                .jsonPath("$.users[0].id").isEqualTo(userId.toString())
                .jsonPath("$.users[0].role").isEqualTo("USER")
                .jsonPath("$.users[0].creditBalanceCents").isEqualTo(0)
                .jsonPath("$.users[0].apiKeyCount").isEqualTo(0)
                .jsonPath("$.users[0].createdAt").exists();
    }

    @Test
    void searchUsers_givenUuid_returnsExactUser() {
        http.get().uri("/v1/admin/users?q=" + userId)
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.users.length()").isEqualTo(1)
                .jsonPath("$.users[0].email").isEqualTo(userEmail);
    }

    @Test
    void searchUsers_givenBlankQuery_returnsEmptyList() {
        http.get().uri("/v1/admin/users")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.users.length()").isEqualTo(0);
    }

    @Test
    void searchUsers_givenNonAdminCaller_returns403() {
        http.get().uri("/v1/admin/users?q=whatever")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== Credit adjust ================================================

    @Test
    void creditAdjust_positiveAmount_creditsLedgerAndBalance_writesAudit() {
        http.post().uri("/v1/admin/users/" + userId + "/credit-adjust")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 5_00, "note", "goodwill credit"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.user.creditBalanceCents").isEqualTo(500)
                .jsonPath("$.entry.deltaCents").isEqualTo(500)
                .jsonPath("$.entry.source").isEqualTo("ADMIN_ADJUSTMENT")
                .jsonPath("$.entry.sourceRef").isEqualTo("admin@godance.test")
                .jsonPath("$.entry.note").isEqualTo("goodwill credit");

        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(500);
        assertThat(users.findById(userId).orElseThrow().getCreditBalanceCents()).isEqualTo(500);

        var events = audit.findByEventType(AuditLogService.EVT_ADMIN_CREDIT_ADJUSTED).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
        assertThat(events).hasSize(1);
        Map<String, Object> detail = events.get(0).getEventDetail();
        assertThat(detail.get("adminEmail")).isEqualTo("admin@godance.test");
        assertThat(detail.get("targetUserId")).isEqualTo(userId.toString());
        assertThat(((Number) detail.get("amountCents")).longValue()).isEqualTo(500L);
        assertThat(detail.get("note")).isEqualTo("goodwill credit");
    }

    @Test
    void creditAdjust_negativeAmount_mayPushBalanceNegative() {
        http.post().uri("/v1/admin/users/" + userId + "/credit-adjust")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", -2_50, "note", "clawback of mistaken promo"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.user.creditBalanceCents").isEqualTo(-250);

        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(-250);
    }

    @Test
    void creditAdjust_sameIdempotencyKey_appliesOnce() {
        Map<String, Object> body = Map.of(
                "amountCents", 10_00, "note", "double-submit guard", "idempotencyKey", "test-key-1");
        for (int i = 0; i < 2; i++) {
            http.post().uri("/v1/admin/users/" + userId + "/credit-adjust")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(body)
                    .exchange()
                    .expectStatus().isOk();
        }
        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(1_000);
    }

    @Test
    void creditAdjust_missingNote_returns400() {
        http.post().uri("/v1/admin/users/" + userId + "/credit-adjust")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 100))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void creditAdjust_zeroAmount_returns400() {
        http.post().uri("/v1/admin/users/" + userId + "/credit-adjust")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 0, "note", "nope"))
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void creditAdjust_unknownUser_returns404() {
        http.post().uri("/v1/admin/users/" + UUID.randomUUID() + "/credit-adjust")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 100, "note", "ghost"))
                .exchange()
                .expectStatus().isNotFound();
    }

    @Test
    void creditAdjust_givenNonAdminCaller_returns403_andNoLedgerWrite() {
        http.post().uri("/v1/admin/users/" + userId + "/credit-adjust")
                .header("Authorization", userBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("amountCents", 999_99, "note", "self-serve heist"))
                .exchange()
                .expectStatus().isForbidden();
        assertThat(ledger.sumDeltaForUser(userId)).isZero();
    }

    // ===== Ledger page ==================================================

    @Test
    void userLedger_pagesNewestFirst() {
        for (int i = 1; i <= 3; i++) {
            http.post().uri("/v1/admin/users/" + userId + "/credit-adjust")
                    .header("Authorization", adminBearer)
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue(Map.of("amountCents", i * 100, "note", "entry " + i))
                    .exchange()
                    .expectStatus().isOk();
        }
        http.get().uri("/v1/admin/users/" + userId + "/ledger?page=0")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.entries.length()").isEqualTo(3)
                .jsonPath("$.entries[0].note").isEqualTo("entry 3")
                .jsonPath("$.entries[2].note").isEqualTo("entry 1")
                .jsonPath("$.totalEntries").isEqualTo(3)
                .jsonPath("$.page").isEqualTo(0);
    }

    @Test
    void userLedger_givenNonAdminCaller_returns403() {
        http.get().uri("/v1/admin/users/" + userId + "/ledger")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== Usage summary ================================================

    @Test
    void usageSummary_aggregatesPerDayWithTopModelsAndUsers() {
        Instant today = Instant.now().truncatedTo(ChronoUnit.DAYS).plus(2, ChronoUnit.HOURS);
        Instant yesterday = today.minus(1, ChronoUnit.DAYS);
        seedUsage(userId, "kimi-k2.6", 30, 9, today);
        seedUsage(userId, "kimi-k2.6", 50, 20, today);
        seedUsage(userId, "glm-4.7-flash", 10, 4, yesterday);

        LocalDate to = LocalDate.ofInstant(today, ZoneOffset.UTC);
        LocalDate from = to.minusDays(1);

        http.get().uri("/v1/admin/usage/summary?from=" + from + "&to=" + to + "&groupBy=day")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.days.length()").isEqualTo(2)
                .jsonPath("$.days[0].date").isEqualTo(from.toString())
                .jsonPath("$.days[0].revenueCents").isEqualTo(10)
                .jsonPath("$.days[0].costCents").isEqualTo(4)
                .jsonPath("$.days[0].requests").isEqualTo(1)
                .jsonPath("$.days[1].revenueCents").isEqualTo(80)
                .jsonPath("$.days[1].costCents").isEqualTo(29)
                .jsonPath("$.days[1].requests").isEqualTo(2)
                .jsonPath("$.topModels[0].modelName").isEqualTo("kimi-k2.6")
                .jsonPath("$.topModels[0].revenueCents").isEqualTo(80)
                .jsonPath("$.topUsers[0].email").isEqualTo(userEmail)
                .jsonPath("$.topUsers[0].revenueCents").isEqualTo(90);
    }

    @Test
    void usageSummary_zeroFillsDaysWithoutTraffic() {
        LocalDate to = LocalDate.now(ZoneOffset.UTC);
        LocalDate from = to.minusDays(6);
        http.get().uri("/v1/admin/usage/summary?from=" + from + "&to=" + to)
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.days.length()").isEqualTo(7);
    }

    @Test
    void usageSummary_givenUnsupportedGroupBy_returns400() {
        http.get().uri("/v1/admin/usage/summary?groupBy=week")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isBadRequest();
    }

    @Test
    void usageSummary_givenNonAdminCaller_returns403() {
        http.get().uri("/v1/admin/usage/summary")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== Helpers ======================================================

    private void seedUsage(UUID uid, String model, long costCents, long providerCostCents, Instant at) {
        UsageRecord r = new UsageRecord();
        r.setUserId(uid);
        r.setModality(Modality.TEXT);
        r.setModelName(model);
        r.setProvider(Provider.MOONSHOT);
        r.setInputTokens(100);
        r.setOutputTokens(50);
        r.setCostCents(costCents);
        r.setProviderCostCents(providerCostCents);
        r.setRegion(Region.LOCAL);
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setRequestId("req-" + UUID.randomUUID());
        r.setOutcome(CallOutcome.OK);
        r.setRecordedAt(at);
        usageRecords.save(r);
    }

    private String signUpOrLogIn(String email) {
        http.post().uri("/v1/auth/signup")
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("email", email,
                        "password", "correct-horse-battery-staple",
                        "fullName", "Console Test"))
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

    /**
     * Admin access needs the account's email verified. Password signup
     * leaves it unverified, so mark it directly instead of following the
     * emailed link.
     */
    private void markEmailVerified(String email) {
        User u = users.findByEmail(email).orElseThrow();
        u.setEmailVerified(true);
        users.save(u);
    }
}
