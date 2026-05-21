package ai.godance.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.UsageRecord;

public interface UsageRecordRepository extends JpaRepository<UsageRecord, UUID> {
    List<UsageRecord> findByUserIdAndRecordedAtGreaterThanEqual(UUID userId, Instant since);

    /** Recent usage, newest first; small-batch pagination via {@link org.springframework.data.domain.Pageable}. */
    List<UsageRecord> findByUserIdOrderByRecordedAtDesc(
            UUID userId, org.springframework.data.domain.Pageable pageable);

    /** All usage in a window, for the aggregation job. */
    List<UsageRecord> findByRecordedAtGreaterThanEqual(Instant since);

    /** Total charged cents for a user since {@code since} — drives per-user spending caps. */
    @Query("SELECT COALESCE(SUM(u.costCents), 0) FROM UsageRecord u "
         + "WHERE u.userId = :userId AND u.recordedAt >= :since")
    long sumCostCentsForUserSince(@Param("userId") UUID userId, @Param("since") Instant since);

    /** Total charged cents for one API key since {@code since} — drives per-key spending caps. */
    @Query("SELECT COALESCE(SUM(u.costCents), 0) FROM UsageRecord u "
         + "WHERE u.apiKeyId = :apiKeyId AND u.recordedAt >= :since")
    long sumCostCentsForApiKeySince(@Param("apiKeyId") UUID apiKeyId, @Param("since") Instant since);
}
