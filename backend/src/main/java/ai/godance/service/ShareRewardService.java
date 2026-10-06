package ai.godance.service;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.CreditSource;
import ai.godance.domain.User;
import ai.godance.repository.CreditLedgerRepository;
import ai.godance.repository.UserRepository;
import ai.godance.web.dto.GalleryDtos.ShareRewardResponse;

@Service
public class ShareRewardService {

    public static final long REWARD_CENTS = 10L;
    public static final long MAX_DAILY_CLAIMS = 5L;
    public static final String SOURCE_REF = "SHARE_REWARD";

    private final CreditLedgerRepository ledger;
    private final CreditLedgerService ledgerService;
    private final UserRepository users;

    public ShareRewardService(CreditLedgerRepository ledger,
                              CreditLedgerService ledgerService,
                              UserRepository users) {
        this.ledger = ledger;
        this.ledgerService = ledgerService;
        this.users = users;
    }

    @Transactional
    public ShareRewardResponse claimShareReward(UUID userId) {
        LocalDate today = LocalDate.now(ZoneOffset.UTC);
        java.time.Instant startOfDay = today.atStartOfDay().toInstant(ZoneOffset.UTC);

        long claimsToday = ledger.countByUserIdAndSourceRefSince(userId, SOURCE_REF, startOfDay);
        long currentBalance = users.findById(userId)
                .map(User::getCreditBalanceCents)
                .orElse(0L);

        if (claimsToday >= MAX_DAILY_CLAIMS) {
            return new ShareRewardResponse(false, 0L, claimsToday, MAX_DAILY_CLAIMS, currentBalance);
        }

        String idempotencyKey = "share-reward:" + userId + ":" + today + ":" + (claimsToday + 1);
        ledgerService.topUp(
                userId,
                REWARD_CENTS,
                CreditSource.PROMO,
                SOURCE_REF,
                idempotencyKey,
                "Community gallery share reward (+10¢)"
        );

        long updatedBalance = users.findById(userId)
                .map(User::getCreditBalanceCents)
                .orElse(currentBalance + REWARD_CENTS);

        return new ShareRewardResponse(true, REWARD_CENTS, claimsToday + 1, MAX_DAILY_CLAIMS, updatedBalance);
    }
}
