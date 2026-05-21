package ai.godance.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.Provider;
import ai.godance.domain.ProviderHealth;

public interface ProviderHealthRepository extends JpaRepository<ProviderHealth, UUID> {

    List<ProviderHealth> findByProviderAndModelOrderByWindowEndDesc(Provider provider, String model);

    /** Idempotency for the aggregator: same window key → update, not insert. */
    Optional<ProviderHealth> findByProviderAndModelAndWindowStartAndWindowEnd(
            Provider provider, String model, Instant windowStart, Instant windowEnd);

    /** Most recent window for a model — the outage-aware router's health signal. */
    Optional<ProviderHealth> findFirstByProviderAndModelOrderByWindowEndDesc(
            Provider provider, String model);

    /** All windows ending at-or-after {@code since}, newest first — backs /v1/status. */
    List<ProviderHealth> findByWindowEndGreaterThanEqualOrderByWindowEndDesc(Instant since);
}
