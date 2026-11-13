package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.AuditLogEntry;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.AuditLogEntryRepository;
import ai.godance.repository.UserRepository;
import ai.godance.service.SignupCreditService.Outcome;

/**
 * The daily limit on signup credits: past it, an eligible account gets
 * nothing that UTC day, the skip is audited with its reason, and the
 * verification around it still succeeds. Concurrent grants never pass the
 * limit. Both tests top the day up to the limit themselves, so they hold
 * in either order.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK, properties = {
        "godance.promo.signup-credit-cents=100",
        "godance.promo.signup-credit-daily-limit=" + SignupCreditDailyLimitTest.LIMIT})
@Testcontainers
class SignupCreditDailyLimitTest {

    static final int LIMIT = 3;
    private static final String IP = "203.0.113.11";

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired SignupCreditService signupCredit;
    @Autowired AuthService auth;
    @Autowired UserRepository users;
    @Autowired AuditLogEntryRepository audits;
    @Autowired JdbcTemplate jdbc;

    @Test
    void verifyEmail_givenDailyLimitReached_expectsVerifiedWithoutCreditAndSkipAudited() {
        while (grantedToday() < LIMIT) {
            assertThat(signupCredit.grantNow(saveVerifiedUser().getId())).isEqualTo(Outcome.GRANTED);
        }
        User late = saveUnverifiedUser();

        auth.verifyEmail(late.getId(), late.getEmailVerificationToken(), IP);

        User after = users.findById(late.getId()).orElseThrow();
        assertThat(after.isEmailVerified()).isTrue();
        assertThat(after.getCreditBalanceCents()).isZero();
        assertThat(ledgerRows(late.getId())).isZero();
        assertThat(grantedToday()).isEqualTo(LIMIT);
        assertThat(skipsFor(late.getId())).singleElement().satisfies(e -> {
            assertThat(e.getOutcome()).isEqualTo(AuditOutcome.DENIED);
            assertThat(e.getEventDetail()).containsEntry("reason", "daily-limit");
            assertThat(((Number) e.getEventDetail().get("dailyLimit")).intValue()).isEqualTo(LIMIT);
        });
    }

    @Test
    void grantNow_givenConcurrentGrantsPastTheLimit_expectsExactlyTheLimitGranted() throws Exception {
        int contenders = LIMIT * 3;
        List<UUID> ids = new ArrayList<>();
        for (int i = 0; i < contenders; i++) {
            ids.add(saveVerifiedUser().getId());
        }
        long before = grantedToday();
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(contenders);
        List<Future<Outcome>> outcomes = new ArrayList<>();
        try {
            for (UUID id : ids) {
                outcomes.add(pool.submit(() -> {
                    start.await();
                    return signupCredit.grantNow(id);
                }));
            }
            start.countDown();
            List<Outcome> results = new ArrayList<>();
            for (Future<Outcome> f : outcomes) {
                results.add(f.get(60, TimeUnit.SECONDS));
            }

            long granted = results.stream().filter(o -> o == Outcome.GRANTED).count();
            assertThat(granted).isEqualTo(LIMIT - before);
            assertThat(results).filteredOn(o -> o != Outcome.GRANTED)
                    .containsOnly(Outcome.DAILY_LIMIT);
            assertThat(grantedToday()).isEqualTo(LIMIT);
        } finally {
            pool.shutdownNow();
        }
    }

    private long grantedToday() {
        Instant dayStart = LocalDate.now(ZoneOffset.UTC).atStartOfDay(ZoneOffset.UTC).toInstant();
        return jdbc.queryForObject(
                "SELECT count(*) FROM credit_ledger WHERE source = 'PROMO' AND occurred_at >= ?",
                Long.class, java.sql.Timestamp.from(dayStart));
    }

    private long ledgerRows(UUID userId) {
        return jdbc.queryForObject("SELECT count(*) FROM credit_ledger WHERE user_id = ?", Long.class, userId);
    }

    private List<AuditLogEntry> skipsFor(UUID userId) {
        return audits.findByEventType(AuditLogService.EVT_PROMO_SIGNUP_CREDIT_SKIPPED).stream()
                .filter(e -> userId.equals(e.getUserId()))
                .toList();
    }

    private User baseUser() {
        User u = new User();
        u.setEmail("limit-" + UUID.randomUUID() + "@godance.test");
        u.setFullName("Test User");
        u.setPasswordHash("$2a$12$placeholderplaceholderplaceholderplaceholderplaceh");
        u.setRole(UserRole.USER);
        u.setActive(true);
        return u;
    }

    private User saveVerifiedUser() {
        User u = baseUser();
        u.setEmailVerified(true);
        return users.save(u);
    }

    private User saveUnverifiedUser() {
        User u = baseUser();
        u.setEmailVerified(false);
        u.setEmailVerificationToken("verify-" + UUID.randomUUID());
        u.setEmailVerificationSentAt(Instant.now());
        return users.save(u);
    }
}
