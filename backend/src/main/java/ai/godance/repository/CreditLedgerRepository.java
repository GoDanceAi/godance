package ai.godance.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.CreditLedger;
import ai.godance.domain.CreditSource;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, UUID> {

    Optional<CreditLedger> findByIdempotencyKey(String idempotencyKey);

    /** Scoped by user so it runs on the user_id index, not a scan of the whole ledger. */
    boolean existsByUserIdAndSourceAndSourceRef(UUID userId, CreditSource source, String sourceRef);

    /** Whether the user holds any entry from one of {@code sources}, e.g. has ever paid. */
    boolean existsByUserIdAndSourceIn(UUID userId, java.util.Collection<CreditSource> sources);

    /**
     * Authoritative balance for a user (in cents). The
     * {@code User.creditBalanceCents} column is a denormalized cache;
     * this query is the source of truth.
     */
    @Query("SELECT COALESCE(SUM(c.deltaCents), 0) FROM CreditLedger c WHERE c.userId = :userId")
    long sumDeltaForUser(@Param("userId") UUID userId);

    /** Paged ledger history for the admin user-detail drawer, newest first. */
    org.springframework.data.domain.Page<CreditLedger> findByUserIdOrderByOccurredAtDesc(
            UUID userId, org.springframework.data.domain.Pageable pageable);

    /**
     * Entries from {@code source} whose idempotency key starts with
     * {@code keyPrefix}, written at or after {@code since}: how many of one
     * promotion's grants a day has seen. A scan of the ledger, so call it
     * only once a grant is otherwise due.
     */
    long countBySourceAndIdempotencyKeyStartingWithAndOccurredAtGreaterThanEqual(
            CreditSource source, String keyPrefix, java.time.Instant since);

    /**
     * Take a Postgres advisory lock held until the caller's transaction
     * ends, so grants under one cap are counted and written one at a time.
     * Must run inside a transaction.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:lockId)) AS held", nativeQuery = true)
    int lockForTransaction(@Param("lockId") long lockId);
}
