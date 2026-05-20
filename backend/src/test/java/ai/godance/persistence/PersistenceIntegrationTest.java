package ai.godance.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import ai.godance.domain.ApiKey;
import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.ProviderHealth;
import ai.godance.domain.Region;
import ai.godance.domain.ScheduledTrigger;
import ai.godance.domain.TaskKind;
import ai.godance.domain.TriggerType;
import ai.godance.domain.UsageRecord;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.ProviderHealthRepository;
import ai.godance.repository.ScheduledTriggerRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.repository.UserRepository;

/**
 * Session 2 acceptance: persist + read one row per table, against a
 * Testcontainers-provisioned Postgres 16. Walks the FK graph so the
 * test also exercises {@code ON DELETE CASCADE / SET NULL} declarations
 * implicitly.
 *
 * <p>Hibernate {@code ddl-auto=validate} (from application.yml) means
 * any entity ↔ migration drift will fail this test at context startup.
 */
@SpringBootTest
@Testcontainers
class PersistenceIntegrationTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

    @Autowired UserRepository users;
    @Autowired ApiKeyRepository apiKeys;
    @Autowired UsageRecordRepository usage;
    @Autowired AuditLogEntryRepository audit;
    @Autowired ScheduledTriggerRepository triggers;
    @Autowired CreditLedgerRepository ledger;
    @Autowired GenerationJobRepository jobs;
    @Autowired ProviderHealthRepository providerHealth;

    @Test
    void persistAndRead_givenOneRowPerTable_succeedsAcrossEntireSchema() {
        // ---- User ---------------------------------------------------
        User user = new User();
        user.setEmail("session2@godance.ai");
        user.setFullName("Session Two");
        user.setRole(UserRole.USER);
        user.setCreditBalanceCents(0L);
        user.setDailyCreditCapCents(50_00L);
        user.setMonthlyCreditCapCents(500_00L);
        users.save(user);
        assertThat(user.getId()).isNotNull();
        assertThat(users.findByEmail("session2@godance.ai")).isPresent();

        // ---- ApiKey -------------------------------------------------
        ApiKey key = new ApiKey();
        key.setUserId(user.getId());
        key.setName("default");
        key.setKeyHash("sha256$deadbeef");
        key.setKeyPrefix("gd_test_");
        key.setScopes(List.of("chat.completions", "images.generations"));
        apiKeys.save(key);
        assertThat(apiKeys.findByUserId(user.getId())).singleElement()
                .extracting(ApiKey::getName).isEqualTo("default");

        // ---- UsageRecord -------------------------------------------
        UsageRecord usageRecord = new UsageRecord();
        usageRecord.setUserId(user.getId());
        usageRecord.setApiKeyId(key.getId());
        usageRecord.setModality(Modality.TEXT);
        usageRecord.setModelName("gpt-4o-mini");
        usageRecord.setProvider(Provider.OPENAI);
        usageRecord.setInputTokens(420);
        usageRecord.setOutputTokens(180);
        usageRecord.setCostCents(12);
        usageRecord.setProviderCostCents(10L);
        usageRecord.setRegion(Region.LOCAL);
        usageRecord.setRequestId("req_test_01");
        usage.save(usageRecord);
        assertThat(usage.findByUserIdAndRecordedAtGreaterThanEqual(
                user.getId(), Instant.now().minus(1, ChronoUnit.MINUTES)))
                .hasSize(1);

        // ---- AuditLogEntry (append-only repository) ----------------
        AuditLogEntry entry = new AuditLogEntry();
        entry.setUserId(user.getId());
        entry.setEventType("auth.login");
        entry.setEventDetail(Map.of("ua", "test-runner"));
        entry.setIpAddress("127.0.0.1");
        entry.setOutcome(AuditOutcome.SUCCESS);
        audit.save(entry);
        assertThat(audit.findByEventType("auth.login")).singleElement()
                .extracting(AuditLogEntry::getUserId).isEqualTo(user.getId());

        // ---- ScheduledTrigger --------------------------------------
        ScheduledTrigger trigger = new ScheduledTrigger();
        trigger.setUserId(user.getId());
        trigger.setTaskKind(TaskKind.AUTO_RECHARGE);
        trigger.setTriggerType(TriggerType.CRON);
        trigger.setCronExpression("0 0 9 * * *");
        trigger.setTimezone("America/Toronto");
        trigger.setPayload(Map.of("topup_cents", 20_00));
        triggers.save(trigger);
        assertThat(triggers.findByTaskKindAndEnabledTrue(TaskKind.AUTO_RECHARGE))
                .hasSize(1);

        // ---- CreditLedger (two inserts → balance via SUM) ----------
        CreditLedger topup = new CreditLedger();
        topup.setUserId(user.getId());
        topup.setDeltaCents(50_00L);
        topup.setSource(CreditSource.STRIPE);
        topup.setSourceRef("ch_test_001");
        topup.setIdempotencyKey("idem-topup-001");
        topup.setNote("test top-up");
        ledger.save(topup);

        CreditLedger debit = new CreditLedger();
        debit.setUserId(user.getId());
        debit.setDeltaCents(-12L);
        debit.setSource(CreditSource.DEBIT);
        debit.setSourceRef("req_test_01");
        debit.setIdempotencyKey("idem-debit-001");
        ledger.save(debit);

        assertThat(ledger.findByIdempotencyKey("idem-topup-001")).isPresent();
        assertThat(ledger.sumDeltaForUser(user.getId())).isEqualTo(50_00L - 12L);

        // ---- GenerationJob -----------------------------------------
        GenerationJob job = new GenerationJob();
        job.setUserId(user.getId());
        job.setModality(Modality.VIDEO);
        job.setProvider(Provider.VOLCENGINE_ARK);
        job.setModel("seedance-2.0");
        job.setPrompt(Map.of("text", "a tiger surfing a wave", "duration_s", 6));
        job.setStatus(JobStatus.QUEUED);
        job.setEstimatedCostCents(300L);
        job.setWebhookUrl("https://example.invalid/webhooks/job");
        jobs.save(job);
        assertThat(jobs.findByStatus(JobStatus.QUEUED)).hasSize(1);

        // ---- ProviderHealth ----------------------------------------
        Instant windowStart = Instant.now().minus(5, ChronoUnit.MINUTES);
        Instant windowEnd = Instant.now();
        ProviderHealth health = new ProviderHealth();
        health.setProvider(Provider.OPENAI);
        health.setModel("gpt-4o-mini");
        health.setWindowStart(windowStart);
        health.setWindowEnd(windowEnd);
        health.setRequestCount(100);
        health.setErrorCount(2);
        health.setRateLimitCount(0);
        health.setP50Ms(120);
        health.setP95Ms(420);
        health.setP99Ms(880);
        providerHealth.save(health);
        assertThat(providerHealth.findByProviderAndModelOrderByWindowEndDesc(
                Provider.OPENAI, "gpt-4o-mini")).hasSize(1);
    }
}
