package ai.godance.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.User;

public interface UserRepository extends JpaRepository<User, UUID> {
    Optional<User> findByEmail(String email);
    Optional<User> findByEmailVerificationToken(String token);
    Optional<User> findByPasswordResetTokenHash(String tokenHash);
    Optional<User> findByStripeCustomerId(String stripeCustomerId);
    boolean existsByEmail(String email);

    /** The per-request JWT check in {@code SessionVersionJwtDecoder} — one column, by primary key. */
    @Query("SELECT u.sessionVersion FROM User u WHERE u.id = :id")
    Optional<Integer> findSessionVersionById(@Param("id") UUID id);

    /** Admin console user search — case-insensitive email substring, newest first. */
    List<User> findByEmailContainingIgnoreCaseOrderByCreatedAtDesc(
            String emailFragment, org.springframework.data.domain.Pageable pageable);

    /**
     * Cron safety-net candidates: auto-recharge ON, threshold configured,
     * payment method on file, balance below threshold. Returns ids only —
     * the cron iterates and re-loads each user inside its own tx.
     */
    @Query("SELECT u.id FROM User u "
         + "WHERE u.autoRechargeEnabled = TRUE "
         + "  AND u.autoRechargeThresholdCents IS NOT NULL "
         + "  AND u.stripePaymentMethodId IS NOT NULL "
         + "  AND u.creditBalanceCents < u.autoRechargeThresholdCents")
    List<UUID> findAutoRechargeCandidates();

    /**
     * Atomic balance credit. Single UPDATE so concurrent top-ups can't
     * lose writes; pair with a CreditLedger insert inside the same
     * transaction.
     */
    @Modifying
    @Query("UPDATE User u SET u.creditBalanceCents = u.creditBalanceCents + :delta WHERE u.id = :id")
    int incrementBalance(@Param("id") UUID id, @Param("delta") long delta);

    /**
     * Atomic balance debit with affordability check. Returns 0 if the
     * user can't afford the debit — caller treats that as "insufficient
     * funds" and skips the ledger insert.
     */
    @Modifying
    @Query("UPDATE User u SET u.creditBalanceCents = u.creditBalanceCents - :cost "
         + "WHERE u.id = :id AND u.creditBalanceCents >= :cost")
    int decrementBalanceIfSufficient(@Param("id") UUID id, @Param("cost") long cost);

    /**
     * Adds a request's exact cost to the user's fraction-of-a-cent carry and
     * returns the new remainder (0..9999 microcents). One UPDATE, so two
     * concurrent settlements for the same user serialise on the row and
     * neither loses the other's fraction. The caller derives the cents to
     * bill from the remainder; see {@code CreditLedgerService.settle}.
     *
     * <p>Written as a data-modifying CTE so JPA runs it as a query and hands
     * back the RETURNING value; a plain {@code @Modifying} update could not.
     */
    @Query(value = """
            WITH carried AS (
                UPDATE users
                   SET pending_microcents = (pending_microcents + :addMicrocents) % 10000
                 WHERE id = :id
             RETURNING pending_microcents
            )
            SELECT pending_microcents FROM carried
            """, nativeQuery = true)
    long carryMicrocents(@Param("id") UUID id, @Param("addMicrocents") long addMicrocents);
}
