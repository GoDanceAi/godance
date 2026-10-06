package ai.godance.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.GalleryDtos.ShareRewardResponse;

@ExtendWith(MockitoExtension.class)
class ShareRewardServiceTest {

    @Mock
    private CreditLedgerRepository ledger;

    @Mock
    private CreditLedgerService ledgerService;

    @Mock
    private UserRepository users;

    private ShareRewardService service;

    @BeforeEach
    void setUp() {
        service = new ShareRewardService(ledger, ledgerService, users);
    }

    @Test
    void claimShareReward_underLimit_deposits10Cents() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setCreditBalanceCents(5000L);

        when(ledger.countByUserIdAndSourceRefSince(eq(userId), eq("SHARE_REWARD"), any(Instant.class)))
                .thenReturn(2L);
        when(users.findById(userId)).thenReturn(Optional.of(user));

        ShareRewardResponse res = service.claimShareReward(userId);

        assertThat(res.success()).isTrue();
        assertThat(res.rewardCents()).isEqualTo(10L);
        assertThat(res.claimsToday()).isEqualTo(3L);
        assertThat(res.maxDailyClaims()).isEqualTo(5L);

        verify(ledgerService).topUp(
                eq(userId),
                eq(10L),
                eq(CreditSource.PROMO),
                eq("SHARE_REWARD"),
                any(),
                any()
        );
    }

    @Test
    void claimShareReward_atLimit_declinesReward() {
        UUID userId = UUID.randomUUID();
        User user = new User();
        user.setId(userId);
        user.setCreditBalanceCents(5000L);

        when(ledger.countByUserIdAndSourceRefSince(eq(userId), eq("SHARE_REWARD"), any(Instant.class)))
                .thenReturn(5L);
        when(users.findById(userId)).thenReturn(Optional.of(user));

        ShareRewardResponse res = service.claimShareReward(userId);

        assertThat(res.success()).isFalse();
        assertThat(res.rewardCents()).isEqualTo(0L);
        assertThat(res.claimsToday()).isEqualTo(5L);

        verify(ledgerService, never()).topUp(any(), any(Long.class), any(), any(), any(), any());
    }
}
