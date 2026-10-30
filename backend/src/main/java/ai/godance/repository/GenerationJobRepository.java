package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {
    List<GenerationJob> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<GenerationJob> findByStatus(JobStatus status);

    // Status changes are conditional updates, so a worker and the sweep can
    // race without either overwriting the other's outcome: whichever ends an
    // open job first wins, and the loser's update matches no row.

    /**
     * Open jobs whose worker can no longer be running: past their lease, or
     * (rows from before V16, which have none) created before {@code legacyCutoff}.
     */
    @Query("SELECT j FROM GenerationJob j "
         + "WHERE j.status IN (ai.godance.domain.JobStatus.QUEUED, ai.godance.domain.JobStatus.RUNNING) "
         + "AND ((j.leaseExpiresAt IS NOT NULL AND j.leaseExpiresAt < :now) "
         + "  OR (j.leaseExpiresAt IS NULL AND j.createdAt < :legacyCutoff))")
    List<GenerationJob> findAbandoned(@Param("now") Instant now, @Param("legacyCutoff") Instant legacyCutoff);

    /** QUEUED → RUNNING; 0 when the job is no longer queued. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE GenerationJob j SET j.status = ai.godance.domain.JobStatus.RUNNING, j.startedAt = :now "
         + "WHERE j.id = :id AND j.status = ai.godance.domain.JobStatus.QUEUED")
    int startIfQueued(@Param("id") UUID id, @Param("now") Instant now);

    /** Open → DONE; 0 when the job had already ended. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE GenerationJob j SET j.status = ai.godance.domain.JobStatus.DONE, "
         + "j.actualCostCents = :actualCostCents, j.outputUrl = :outputUrl, j.completedAt = :now "
         + "WHERE j.id = :id "
         + "AND j.status IN (ai.godance.domain.JobStatus.QUEUED, ai.godance.domain.JobStatus.RUNNING)")
    int completeIfOpen(@Param("id") UUID id, @Param("outputUrl") String outputUrl,
                       @Param("actualCostCents") long actualCostCents, @Param("now") Instant now);

    /** Open → FAILED; 0 when the job had already ended. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query("UPDATE GenerationJob j SET j.status = ai.godance.domain.JobStatus.FAILED, "
         + "j.errorMessage = :error, j.completedAt = :now "
         + "WHERE j.id = :id "
         + "AND j.status IN (ai.godance.domain.JobStatus.QUEUED, ai.godance.domain.JobStatus.RUNNING)")
    int failIfOpen(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);

    // ===== Data retention (V20) ==========================================
    // A job is purged once it has ended: its media objects are deleted, then
    // this redacts the row. Cost columns stay; they are billing records.

    /**
     * Ended, unpurged jobs created before {@code cutoff}, oldest first,
     * strictly after the keyset cursor ({@code afterCreatedAt}, {@code afterId}).
     */
    @Query(value = """
            SELECT * FROM generation_jobs j
             WHERE j.purged_at IS NULL
               AND j.status IN ('DONE', 'FAILED', 'CANCELLED')
               AND j.created_at < :cutoff
               AND (j.created_at, j.id) > (:afterCreatedAt, :afterId)
             ORDER BY j.created_at, j.id
             LIMIT :limit
            """, nativeQuery = true)
    List<GenerationJob> findPurgeCandidates(@Param("cutoff") Instant cutoff,
                                            @Param("afterCreatedAt") Instant afterCreatedAt,
                                            @Param("afterId") UUID afterId,
                                            @Param("limit") int limit);

    /** A user's ended, unpurged jobs (account closure). */
    @Query("SELECT j FROM GenerationJob j WHERE j.userId = :userId AND j.purgedAt IS NULL "
         + "AND j.status IN (ai.godance.domain.JobStatus.DONE, ai.godance.domain.JobStatus.FAILED, "
         + "ai.godance.domain.JobStatus.CANCELLED)")
    List<GenerationJob> findUnpurgedEndedByUserId(@Param("userId") UUID userId);

    List<GenerationJob> findByUserIdAndStatusIn(UUID userId, Collection<JobStatus> statuses);

    /**
     * Redacts an ended job whose media has been deleted: the prompt becomes
     * {@code {"purged": true}}, the output and webhook URLs are cleared and
     * {@code purged_at} is set. 0 when the job is still open or already purged.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE generation_jobs
               SET prompt_json = jsonb_build_object('purged', true),
                   output_url  = NULL,
                   webhook_url = NULL,
                   purged_at   = :now
             WHERE id = :id
               AND purged_at IS NULL
               AND status IN ('DONE', 'FAILED', 'CANCELLED')
            """, nativeQuery = true)
    int redactPurged(@Param("id") UUID id, @Param("now") Instant now);
}
