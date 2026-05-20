package ai.godance.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.UsageRecord;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {
    List<UsageRecord> findByUserIdAndRecordedAtGreaterThanEqual(UUID userId, Instant since);

    /** Recent usage, newest first; small-batch pagination via {@link org.springframework.data.domain.Pageable}. */
    List<UsageRecord> findByUserIdOrderByRecordedAtDesc(
            UUID userId, org.springframework.data.domain.Pageable pageable);

    /** All usage in a window, for the aggregation job. */
    List<UsageRecord> findByRecordedAtGreaterThanEqual(Instant since);
}
