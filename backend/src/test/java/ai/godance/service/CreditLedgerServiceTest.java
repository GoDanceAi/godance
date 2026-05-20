package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

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
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16");

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
