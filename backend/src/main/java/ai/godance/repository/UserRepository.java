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
    Optional<User> findByParentalConsentTokenHash(String tokenHash);
    boolean existsByEmail(String email);

    /**
     * Grants parental consent to a child's account that is still waiting
     * for it, and ends the account's restricted sessions: the bumped
     * session version makes every earlier JWT (the {@code PENDING_CONSENT}
     * ones) fail, so the child signs in again with full access. One
     * conditional UPDATE, so of two paid consent sessions for the same
     * child only the first grants (returns 1); the other returns 0.
     *
     * <p>Flushes and clears the persistence context, so a {@code User}
     * read afterwards in the same transaction reflects this update.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE User u SET u.ageStatus = ai.godance.domain.AgeStatus.CONSENT_GRANTED, "
         + "u.parentalConsentAt = :now, u.parentalConsentTokenHash = NULL, "
         + "u.sessionVersion = u.sessionVersion + 1, u.updatedAt = :now "
         + "WHERE u.id = :id AND u.ageStatus = ai.godance.domain.AgeStatus.CONSENT_PENDING")
    int grantParentalConsent(@Param("id") UUID id, @Param("now") java.time.Instant now);

    /**
     * Moves an account from role {@code from} to {@code to} and ends its
     * sessions: the bumped session version makes every earlier JWT, each
     * carrying the old role in its {@code scope}, fail on its next request.
     * One conditional UPDATE, so it returns 0 if another change got there
     * first, and writing no other column can't overwrite a balance changed
     * in the meantime.
     *
     * <p>Flushes and clears the persistence context, so a {@code User}
     * read afterwards in the same transaction reflects this update.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE User u SET u.role = :to, u.sessionVersion = u.sessionVersion + 1, u.updatedAt = :now "
         + "WHERE u.id = :id AND u.role = :from")
    int changeRole(@Param("id") UUID id, @Param("from") ai.godance.domain.UserRole from,
                   @Param("to") ai.godance.domain.UserRole to, @Param("now") java.time.Instant now);

    /** Can anyone act as {@code role}? The same conditions the admin gate applies. */
    boolean existsByRoleAndActiveTrueAndEmailVerifiedTrue(ai.godance.domain.UserRole role);

    /** Accounts the admin gate would admit, by lower-cased email. */
    @Query("SELECT u FROM User u WHERE lower(u.email) IN :emails AND u.active = true AND u.emailVerified = true")
    List<User> findActiveVerifiedByLowerEmailIn(@Param("emails") List<String> emails);

    /** Where to reach the accounts that can act as {@code role}. */
    @Query("SELECT u.email FROM User u WHERE u.role = :role AND u.active = true AND u.emailVerified = true "
         + "ORDER BY u.email")
    List<String> findActiveVerifiedEmailsByRole(@Param("role") ai.godance.domain.UserRole role);

    /**
     * The per-request JWT check in {@code SessionVersionJwtDecoder} — one
     * column, by primary key. Empty for a disabled account as well as a
     * missing one, so disabling an account ends its sessions at once.
     */
    @Query("SELECT u.sessionVersion FROM User u WHERE u.id = :id AND u.active = true")
    Optional<Integer> findSessionVersionById(@Param("id") UUID id);

    /** The per-request API-key check in {@code ApiKeyAuthFilter}: does the key's owner still have access? */
    boolean existsByIdAndActiveTrue(UUID id);

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
