package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.domain.UserRole;
import ai.godance.repository.UserRepository;
import ai.godance.service.CreditLedgerService.TopUpResult;

@SpringBootTest
@Testcontainers
class CreditLedgerServiceTest {

    @Container
    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:16");

    @Autowired CreditLedgerService ledger;
    @Autowired UserRepository users;

    private User user;

    @BeforeEach
    void seedUser() {
        user = new User();
        user.setEmail("ledger-" + UUID.randomUUID() + "@godance.test");
        user.setFullName("Ledger Tester");
        user.setRole(UserRole.USER);
        user.setPasswordHash("not-used-in-this-test");
        users.save(user);
    }

    @Test
    void topUp_givenFreshIdempotencyKey_appliesCreditAndUpdatesBalanceCache() {
        TopUpResult r = ledger.topUp(user.getId(), 5_00L, CreditSource.STRIPE,
                "ch_test_1", "evt_1", "first top-up");
        assertThat(r).isEqualTo(TopUpResult.APPLIED);

        User refreshed = users.findById(user.getId()).orElseThrow();
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(5_00L);
        assertThat(ledger.authoritativeBalance(user.getId())).isEqualTo(5_00L);
    }

    @Test
    void topUp_replayedWithSameKey_returnsDuplicate_doesNotDoubleCredit() {
        ledger.topUp(user.getId(), 10_00L, CreditSource.STRIPE, "ch_x", "evt_dup", "first");
        TopUpResult replay = ledger.topUp(user.getId(), 10_00L, CreditSource.STRIPE,
                "ch_x", "evt_dup", "replay");

        assertThat(replay).isEqualTo(TopUpResult.DUPLICATE);
        assertThat(users.findById(user.getId()).orElseThrow().getCreditBalanceCents())
                .isEqualTo(10_00L);
        assertThat(ledger.authoritativeBalance(user.getId())).isEqualTo(10_00L);
    }

    // ===== settle: fractions of a cent carry to the next request (R-06) =====

    @Test
    void settle_subCentRequests_carryUntilAFullCentThenBillOnce() {
        ledger.topUp(user.getId(), 10_00L, CreditSource.STRIPE, "ch_s", "evt_settle_1", null);
        // Each request pre-debits a 1¢ estimate and settles 4,000 µ¢ (0.4¢).
        assertThat(ledger.preDebit(user.getId(), 1L, "req_s1")).isTrue();
        assertThat(ledger.settle(user.getId(), 1L, 4_000L, "req_s1")).isZero();
        assertThat(ledger.preDebit(user.getId(), 1L, "req_s2")).isTrue();
        assertThat(ledger.settle(user.getId(), 1L, 4_000L, "req_s2")).isZero();
        assertThat(ledger.preDebit(user.getId(), 1L, "req_s3")).isTrue();
        assertThat(ledger.settle(user.getId(), 1L, 4_000L, "req_s3")).isEqualTo(1L);

        User refreshed = users.findById(user.getId()).orElseThrow();
        // 12,000 µ¢ of usage: 1¢ billed, 2,000 µ¢ carried; the two 1¢ estimates refunded.
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(10_00L - 1L);
        assertThat(refreshed.getPendingMicrocents()).isEqualTo(2_000L);
        assertThat(ledger.authoritativeBalance(user.getId())).isEqualTo(10_00L - 1L);
    }

    @Test
    void settle_multiCentRequest_billsWholeCentsAndCarriesTheRest() {
        ledger.topUp(user.getId(), 10_00L, CreditSource.STRIPE, "ch_s2", "evt_settle_2", null);
        assertThat(ledger.preDebit(user.getId(), 2L, "req_big")).isTrue();
        // 2.75¢ actual against a 2¢ estimate: bills 2¢ now, carries 7,500 µ¢.
        assertThat(ledger.settle(user.getId(), 2L, 27_500L, "req_big")).isEqualTo(2L);
        User refreshed = users.findById(user.getId()).orElseThrow();
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(10_00L - 2L);
        assertThat(refreshed.getPendingMicrocents()).isEqualTo(7_500L);

        // Next request of 0.3¢ tips the carry over: bills 1¢, carries 500 µ¢.
        assertThat(ledger.preDebit(user.getId(), 1L, "req_tip")).isTrue();
        assertThat(ledger.settle(user.getId(), 1L, 3_000L, "req_tip")).isEqualTo(1L);
        refreshed = users.findById(user.getId()).orElseThrow();
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(10_00L - 3L);
        assertThat(refreshed.getPendingMicrocents()).isEqualTo(500L);
        assertThat(ledger.authoritativeBalance(user.getId())).isEqualTo(10_00L - 3L);
    }

    @Test
    void settle_zeroCost_refundsTheWholeEstimate_andLeavesCarryUntouched() {
        ledger.topUp(user.getId(), 5_00L, CreditSource.STRIPE, "ch_s3", "evt_settle_3", null);
        assertThat(ledger.preDebit(user.getId(), 1L, "req_zero")).isTrue();
        assertThat(ledger.settle(user.getId(), 1L, 0L, "req_zero")).isZero();
        User refreshed = users.findById(user.getId()).orElseThrow();
        assertThat(refreshed.getCreditBalanceCents()).isEqualTo(5_00L);
        assertThat(refreshed.getPendingMicrocents()).isZero();
    }

    @Test
    void debit_givenSufficientBalance_succeedsAndWritesNegativeLedgerEntry() {
        ledger.topUp(user.getId(), 20_00L, CreditSource.STRIPE, "ch_a", "evt_dbg_1", null);

        boolean ok = ledger.debit(user.getId(), 7_00L, "req_abc");
        assertThat(ok).isTrue();
        assertThat(users.findById(user.getId()).orElseThrow().getCreditBalanceCents())
                .isEqualTo(13_00L);
        assertThat(ledger.authoritativeBalance(user.getId())).isEqualTo(13_00L);
    }

    @Test
    void debit_givenInsufficientBalance_returnsFalse_doesNotWriteLedger() {
        ledger.topUp(user.getId(), 1_00L, CreditSource.STRIPE, "ch_b", "evt_dbg_2", null);

        boolean ok = ledger.debit(user.getId(), 5_00L, "req_too_expensive");
        assertThat(ok).isFalse();
        assertThat(users.findById(user.getId()).orElseThrow().getCreditBalanceCents())
                .isEqualTo(1_00L);   // unchanged
        assertThat(ledger.authoritativeBalance(user.getId())).isEqualTo(1_00L);
    }
}
