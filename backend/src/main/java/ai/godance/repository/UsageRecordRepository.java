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

    // ===== Admin overview aggregates (one query per series — no N+1) =====
    // Native SQL for date_trunc; aliases are quoted so Postgres preserves
    // the camelCase that Spring Data's projection matching expects.

    /**
     * One row per calendar day (UTC) in [from, to): revenue vs provider cost.
     * Sums the exact microcent columns (V15) and rounds once at the end, so
     * fractions of a cent are no longer lost per row; rows written before V15
     * fall back to their whole-cent values.
     */
    interface DailyUsageRow {
        java.time.LocalDate getDay();
        long getRevenueCents();
        long getCostCents();
        long getRequests();
    }

    @Query(value = """
            SELECT CAST(date_trunc('day', recorded_at AT TIME ZONE 'UTC') AS date) AS "day",
                   CAST(COALESCE(ROUND(SUM(COALESCE(cost_microcents, cost_cents * 10000)) / 10000.0), 0) AS BIGINT) AS "revenueCents",
                   CAST(COALESCE(ROUND(SUM(COALESCE(provider_cost_microcents, COALESCE(provider_cost_cents, 0) * 10000)) / 10000.0), 0) AS BIGINT) AS "costCents",
                   COUNT(*)                                        AS "requests"
            FROM usage_records
            WHERE recorded_at >= :from AND recorded_at < :to
            GROUP BY 1 ORDER BY 1
            """, nativeQuery = true)
    List<DailyUsageRow> aggregateDailyUsage(@Param("from") Instant from, @Param("to") Instant to);

    /** Top models by charged revenue in [from, to). */
    interface TopModelRow {
        String getModelName();
        long getRevenueCents();
        long getCostCents();
        long getRequests();
    }

    @Query(value = """
            SELECT model_name                                      AS "modelName",
                   CAST(COALESCE(ROUND(SUM(COALESCE(cost_microcents, cost_cents * 10000)) / 10000.0), 0) AS BIGINT) AS "revenueCents",
                   CAST(COALESCE(ROUND(SUM(COALESCE(provider_cost_microcents, COALESCE(provider_cost_cents, 0) * 10000)) / 10000.0), 0) AS BIGINT) AS "costCents",
                   COUNT(*)                                        AS "requests"
            FROM usage_records
            WHERE recorded_at >= :from AND recorded_at < :to
            GROUP BY model_name
            ORDER BY 2 DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TopModelRow> topModelsBySpend(@Param("from") Instant from, @Param("to") Instant to,
                                       @Param("limit") int limit);

    /** Top users by charged revenue in [from, to). */
    interface TopUserRow {
        UUID getUserId();
        String getEmail();
        long getRevenueCents();
        long getRequests();
    }

    @Query(value = """
            SELECT u.user_id                    AS "userId",
                   us.email                     AS "email",
                   CAST(COALESCE(ROUND(SUM(COALESCE(u.cost_microcents, u.cost_cents * 10000)) / 10000.0), 0) AS BIGINT) AS "revenueCents",
                   COUNT(*)                     AS "requests"
            FROM usage_records u
            JOIN users us ON us.id = u.user_id
            WHERE u.recorded_at >= :from AND u.recorded_at < :to
            GROUP BY 1, 2
            ORDER BY 3 DESC
            LIMIT :limit
            """, nativeQuery = true)
    List<TopUserRow> topUsersBySpend(@Param("from") Instant from, @Param("to") Instant to,
                                     @Param("limit") int limit);
}
