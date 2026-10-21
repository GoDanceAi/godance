package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.UUID;

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

import ai.godance.domain.ApiKey;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;
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

    @Autowired WebTestClient http;
    @Autowired UserRepository users;
    @Autowired CreditLedgerRepository ledger;
    @Autowired UsageRecordRepository usageRecords;
    @Autowired AuditLogEntryRepository audit;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired GenerationJobRepository generationJobs;
    @org.springframework.test.context.bean.override.mockito.MockitoBean
    ai.godance.storage.ObjectStorageClient objectStorageClient;

    private String adminBearer;
    private String userBearer;
    private String userEmail;
    private UUID userId;

    @BeforeEach
    void seedAccounts() {
        usageRecords.deleteAll();
        adminBearer = signUpOrLogIn("admin@godance.test");
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

    // ===== /v1/admin/users/{id}/usage ====================================

    @Test
    void userUsage_givenAdminCaller_returnsPagedUsage() {
        seedUsage(userId, "gpt-4o", 50, 15, Instant.now());
        seedUsage(userId, "claude-3-5-sonnet", 80, 25, Instant.now().minusSeconds(60));

        http.get().uri("/v1/admin/users/" + userId + "/usage?page=0&size=10")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.records.length()").isEqualTo(2)
                .jsonPath("$.totalRecords").isEqualTo(2)
                .jsonPath("$.records[0].modelName").isEqualTo("gpt-4o");
    }

    @Test
    void userUsage_givenNonAdmin_returns403() {
        http.get().uri("/v1/admin/users/" + userId + "/usage")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== /v1/admin/users/{id}/status ===================================

    @Test
    void updateUserStatus_freezesAndReactivatesUser() {
        http.patch().uri("/v1/admin/users/" + userId + "/status")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("active", false, "reason", "Suspicious activity"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active").isEqualTo(false);

        assertThat(users.findById(userId).orElseThrow().isActive()).isFalse();

        // Audit entry recorded
        assertThat(audit.findByEventType(AuditLogService.EVT_ADMIN_USER_STATUS_UPDATED))
                .isNotEmpty();

        // Reactivate
        http.patch().uri("/v1/admin/users/" + userId + "/status")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("active", true, "reason", "Appeal granted"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.active").isEqualTo(true);

        assertThat(users.findById(userId).orElseThrow().isActive()).isTrue();
    }

    @Test
    void updateUserStatus_givenNonAdmin_returns403() {
        http.patch().uri("/v1/admin/users/" + userId + "/status")
                .header("Authorization", userBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("active", false))
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== /v1/admin/users/{id}/caps =====================================

    @Test
    void updateUserCaps_updatesLimits() {
        http.patch().uri("/v1/admin/users/" + userId + "/caps")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("dailyCreditCapCents", 5000L, "monthlyCreditCapCents", 20000L, "reason", "VIP Tier upgrade"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.dailyCreditCapCents").isEqualTo(5000)
                .jsonPath("$.monthlyCreditCapCents").isEqualTo(20000);

        var updated = users.findById(userId).orElseThrow();
        assertThat(updated.getDailyCreditCapCents()).isEqualTo(5000L);
        assertThat(updated.getMonthlyCreditCapCents()).isEqualTo(20000L);

        assertThat(audit.findByEventType(AuditLogService.EVT_ADMIN_USER_CAPS_UPDATED))
                .isNotEmpty();
    }

    @Test
    void updateUserCaps_givenNonAdmin_returns403() {
        http.patch().uri("/v1/admin/users/" + userId + "/caps")
                .header("Authorization", userBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("dailyCreditCapCents", 5000L))
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== /v1/admin/users/{id}/keys & DELETE /v1/admin/keys/{id} ========

    @Test
    void userKeys_listsAndRevokesKey() {
        ApiKey k = new ApiKey();
        k.setUserId(userId);
        k.setName("Test Key");
        k.setKeyHash("hash-" + UUID.randomUUID());
        k.setKeyPrefix("sk-test");
        k.setActive(true);
        ApiKey saved = apiKeys.save(k);

        // List keys
        http.get().uri("/v1/admin/users/" + userId + "/keys")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.length()").isEqualTo(1)
                .jsonPath("$[0].name").isEqualTo("Test Key")
                .jsonPath("$[0].active").isEqualTo(true);

        // Revoke key
        http.delete().uri("/v1/admin/keys/" + saved.getId() + "?reason=Compromised")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isNoContent();

        ApiKey revoked = apiKeys.findById(saved.getId()).orElseThrow();
        assertThat(revoked.isActive()).isFalse();

        assertThat(audit.findByEventType(AuditLogService.EVT_ADMIN_API_KEY_REVOKED))
                .isNotEmpty();
    }

    @Test
    void userKeys_givenNonAdmin_returns403() {
        http.get().uri("/v1/admin/users/" + userId + "/keys")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();

        http.delete().uri("/v1/admin/keys/" + UUID.randomUUID())
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== /v1/admin/jobs ================================================

    @Test
    void jobs_listsAndFiltersJobs() {
        GenerationJob j1 = new GenerationJob();
        j1.setUserId(userId);
        j1.setModality(Modality.VIDEO);
        j1.setProvider(Provider.RUNWAY);
        j1.setModel("gen-3-alpha");
        j1.setPrompt(Map.of("prompt", "a cinematic shot of a drone over mountains"));
        j1.setStatus(JobStatus.DONE);
        j1.setActualCostCents(120L);
        generationJobs.save(j1);

        GenerationJob j2 = new GenerationJob();
        j2.setUserId(userId);
        j2.setModality(Modality.IMAGE);
        j2.setProvider(Provider.BFL);
        j2.setModel("flux-pro");
        j2.setPrompt(Map.of("prompt", "a sunset"));
        j2.setStatus(JobStatus.FAILED);
        j2.setErrorMessage("Provider timed out");
        generationJobs.save(j2);

        // List all
        http.get().uri("/v1/admin/jobs?page=0&size=10")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.jobs.length()").isNumber()
                .jsonPath("$.totalJobs").isNumber();

        // Filter by status=FAILED
        http.get().uri("/v1/admin/jobs?status=FAILED")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.jobs[0].status").isEqualTo("FAILED")
                .jsonPath("$.jobs[0].errorMessage").isEqualTo("Provider timed out");

        // Filter by modality=VIDEO
        http.get().uri("/v1/admin/jobs?modality=VIDEO")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.jobs[0].modality").isEqualTo("VIDEO")
                .jsonPath("$.jobs[0].model").isEqualTo("gen-3-alpha");
    }

    @Test
    void jobs_givenNonAdmin_returns403() {
        http.get().uri("/v1/admin/jobs")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== /v1/admin/audit-logs ==========================================

    @Test
    void auditLogs_listsAndFiltersLogs() {
        // Trigger an admin action to produce an audit log
        http.patch().uri("/v1/admin/users/" + userId + "/status")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("active", false, "reason", "Test audit trail"))
                .exchange()
                .expectStatus().isOk();

        http.get().uri("/v1/admin/audit-logs?page=0&size=10")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.logs.length()").isNumber()
                .jsonPath("$.totalLogs").isNumber();

        http.get().uri("/v1/admin/audit-logs?eventType=" + AuditLogService.EVT_ADMIN_USER_STATUS_UPDATED)
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.logs[0].eventType").isEqualTo(AuditLogService.EVT_ADMIN_USER_STATUS_UPDATED);
    }

    @Test
    void auditLogs_givenNonAdmin_returns403() {
        http.get().uri("/v1/admin/audit-logs")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();
    }

    // ===== /v1/admin/payments ============================================

    @Test
    void listPayments_returnsPagedTransactions() {
        http.get().uri("/v1/admin/payments")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.transactions").isArray()
                .jsonPath("$.page").isEqualTo(0);
    }

    @Test
    void reconcilePayment_creditsUserAndAudits() {
        String testRef = "cs_test_" + UUID.randomUUID();
        long balanceBefore = ledger.sumDeltaForUser(userId);

        http.post().uri("/v1/admin/payments/reconcile")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "userId", userId.toString(),
                        "amountCents", 2500,
                        "paymentRef", testRef,
                        "note", "Offline bank transfer reconciled"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deltaCents").isEqualTo(2500)
                .jsonPath("$.source").isEqualTo("STRIPE")
                .jsonPath("$.sourceRef").isEqualTo(testRef)
                .jsonPath("$.userEmail").isNotEmpty();

        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(balanceBefore + 2500);
        assertThat(audit.findByEventType(AuditLogService.EVT_ADMIN_PAYMENT_RECONCILED)).isNotEmpty();

        // Duplicate test: re-posting same ref should 409
        http.post().uri("/v1/admin/payments/reconcile")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "userId", userId.toString(),
                        "amountCents", 2500,
                        "paymentRef", testRef,
                        "note", "Duplicate attempt"
                ))
                .exchange()
                .expectStatus().isEqualTo(409);
    }

    @Test
    void refundPayment_debitsUserAndAudits() {
        String refundRef = "ref_test_" + UUID.randomUUID();
        long balanceBefore = ledger.sumDeltaForUser(userId);

        http.post().uri("/v1/admin/payments/refund")
                .header("Authorization", adminBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of(
                        "userId", userId.toString(),
                        "amountCents", 1000,
                        "paymentRef", refundRef,
                        "reason", "Customer requested cancellation"
                ))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.deltaCents").isEqualTo(-1000)
                .jsonPath("$.source").isEqualTo("REFUND")
                .jsonPath("$.note").isEqualTo("Customer requested cancellation");

        assertThat(ledger.sumDeltaForUser(userId)).isEqualTo(balanceBefore - 1000);
        assertThat(audit.findByEventType(AuditLogService.EVT_ADMIN_PAYMENT_REFUNDED)).isNotEmpty();
    }

    @Test
    void webhookHealth_aggregatesMetricsAndExceptions() {
        // Record a webhook rejection audit log
        ai.godance.domain.AuditLogEntry entry = new ai.godance.domain.AuditLogEntry();
        entry.setEventType(AuditLogService.EVT_BILLING_WEBHOOK_REJECTED);
        entry.setOutcome(ai.godance.domain.AuditOutcome.FAILURE);
        entry.setIpAddress("127.0.0.1");
        entry.setEventDetail(Map.of("reason", "Invalid signature header"));
        audit.save(entry);

        http.get().uri("/v1/admin/payments/webhook-health")
                .header("Authorization", adminBearer)
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.recentExceptions").isArray()
                .jsonPath("$.totalRejected").isNumber();
    }


    @Test
    void payments_givenNonAdmin_returns403() {
        http.get().uri("/v1/admin/payments")
                .header("Authorization", userBearer)
                .exchange()
                .expectStatus().isForbidden();

        http.post().uri("/v1/admin/payments/reconcile")
                .header("Authorization", userBearer)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue(Map.of("userId", userId.toString(), "amountCents", 1000, "paymentRef", "cs_123"))
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
}
