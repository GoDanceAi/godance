package ai.godance.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.CreditLedger;

public interface CreditLedgerRepository extends JpaRepository<CreditLedger, UUID> {

    Optional<CreditLedger> findByIdempotencyKey(String idempotencyKey);

    /**
     * Authoritative balance for a user (in cents). The
     * {@code User.creditBalanceCents} column is a denormalized cache;
     * this query is the source of truth.
     */
    @Query("SELECT COALESCE(SUM(c.deltaCents), 0) FROM CreditLedger c WHERE c.userId = :userId")
    long sumDeltaForUser(@Param("userId") UUID userId);
}
