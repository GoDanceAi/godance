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
}
