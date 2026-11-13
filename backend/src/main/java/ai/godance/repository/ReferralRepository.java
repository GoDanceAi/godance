package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.Referral;
import jakarta.persistence.LockModeType;

public interface ReferralRepository extends JpaRepository<Referral, UUID> {

    /**
     * The predicate for a top-up a referral reward can be paid on, over
     * {@code credit_ledger c}: from a qualifying rail, at least the minimum,
     * and for Stripe a Checkout Session credit ({@code cs_…}), which
     * leaves out an automatic recharge (a PaymentIntent, {@code pi_…}).
     */
    String QUALIFYING_TOP_UP = """
            c.source IN (:sources)
               AND c.delta_cents >= :minCents
               AND (c.source <> 'STRIPE' OR starts_with(c.source_ref, 'cs_'))
            """;

    Optional<Referral> findByInvitedUserId(UUID invitedUserId);

    boolean existsByInvitedUserId(UUID invitedUserId);

    boolean existsByReferrerIdAndInvitedUserId(UUID referrerId, UUID invitedUserId);

    /**
     * The invited user's referral, locked until the caller's transaction
     * ends, so the top-up path and the sweep never reward it twice at once.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Referral r WHERE r.invitedUserId = :invitedUserId")
    Optional<Referral> findByInvitedUserIdForUpdate(@Param("invitedUserId") UUID invitedUserId);

    long countByReferrerId(UUID referrerId);

    long countByReferrerIdAndStatus(UUID referrerId, Referral.Status status);

    @Query("SELECT COALESCE(SUM(r.referrerRewardCents), 0) FROM Referral r "
         + "WHERE r.referrerId = :referrerId AND r.status = :status")
    long sumReferrerRewardCents(@Param("referrerId") UUID referrerId,
                                @Param("status") Referral.Status status);

    /** Rewards actually paid to {@code referrerId} since {@code since}: what the monthly limit counts. */
    @Query("SELECT COUNT(r) FROM Referral r WHERE r.referrerId = :referrerId AND r.status = :status "
         + "AND r.referrerRewardCents > 0 AND r.rewardedAt >= :since")
    long countPaidReferrerRewardsSince(@Param("referrerId") UUID referrerId,
                                       @Param("status") Referral.Status status,
                                       @Param("since") Instant since);

    /**
     * Postgres advisory lock held until the caller's transaction ends, so
     * one referrer's rewards are counted against the monthly limit and
     * written one at a time. The two-key form, whose keys never collide with
     * the one-key locks used elsewhere. Must run inside a transaction.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:space, :key)) AS held", nativeQuery = true)
    int lockForReferrer(@Param("space") int space, @Param("key") int key);

    /** The user's earliest top-up that a reward can be paid on ({@link #QUALIFYING_TOP_UP}). */
    @Query(value = "SELECT c.id FROM credit_ledger c WHERE c.user_id = :userId AND "
            + QUALIFYING_TOP_UP + " ORDER BY c.occurred_at, c.id LIMIT 1", nativeQuery = true)
    Optional<UUID> findFirstQualifyingTopUp(@Param("userId") UUID userId,
                                            @Param("sources") Collection<String> sources,
                                            @Param("minCents") long minCents);

    /**
     * Invited users with a pending referral and a qualifying top-up already
     * on the ledger, oldest claim first: what the sweep pays.
     */
    @Query(value = "SELECT r.invited_user_id FROM referrals r WHERE r.status = 'PENDING' "
            + "AND EXISTS (SELECT 1 FROM credit_ledger c WHERE c.user_id = r.invited_user_id AND "
            + QUALIFYING_TOP_UP + ") ORDER BY r.claimed_at LIMIT :limit", nativeQuery = true)
    List<UUID> findInvitedUsersDue(@Param("sources") Collection<String> sources,
                                   @Param("minCents") long minCents,
                                   @Param("limit") int limit);
}
