package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
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

    /**
     * One page of a user's jobs, newest first by {@code (created_at, id)}:
     * those strictly older than the cursor ({@code beforeCreatedAt},
     * {@code beforeId}), optionally of one modality and one status. An empty
     * {@code modality} or {@code status} matches any. {@code unsavedOnly}
     * leaves out the jobs the user has saved. Callers pass a cursor after
     * every job for the first page; nothing here is ever bound as null.
     */
    @Query(value = """
            SELECT * FROM generation_jobs j
             WHERE j.user_id = :userId
               AND (:modality = '' OR j.modality = :modality)
               AND (:status = '' OR j.status = :status)
               AND (:unsavedOnly = FALSE OR j.saved_at IS NULL)
               AND (j.created_at, j.id) < (:beforeCreatedAt, :beforeId)
             ORDER BY j.created_at DESC, j.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<GenerationJob> findPageForUser(@Param("userId") UUID userId,
                                        @Param("modality") String modality,
                                        @Param("status") String status,
                                        @Param("unsavedOnly") boolean unsavedOnly,
                                        @Param("beforeCreatedAt") Instant beforeCreatedAt,
                                        @Param("beforeId") UUID beforeId,
                                        @Param("limit") int limit);

    /**
     * {@link #findPageForUser} over the jobs the user has saved. Its own
     * query, so the saved index (V27) serves it.
     */
    @Query(value = """
            SELECT * FROM generation_jobs j
             WHERE j.user_id = :userId
               AND j.saved_at IS NOT NULL
               AND (:modality = '' OR j.modality = :modality)
               AND (:status = '' OR j.status = :status)
               AND (j.created_at, j.id) < (:beforeCreatedAt, :beforeId)
             ORDER BY j.created_at DESC, j.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<GenerationJob> findSavedPageForUser(@Param("userId") UUID userId,
                                             @Param("modality") String modality,
                                             @Param("status") String status,
                                             @Param("beforeCreatedAt") Instant beforeCreatedAt,
                                             @Param("beforeId") UUID beforeId,
                                             @Param("limit") int limit);
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
     * Ended, unpurged jobs created before {@code cutoff} that their owners
     * have not saved, oldest first, strictly after the keyset cursor
     * ({@code afterCreatedAt}, {@code afterId}). The nightly purge reads
     * these; a saved job is never among them (V27).
     */
    @Query(value = """
            SELECT * FROM generation_jobs j
             WHERE j.purged_at IS NULL
               AND j.saved_at IS NULL
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

    /** A user's ended, unpurged jobs, saved or not (account closure deletes them all). */
    @Query("SELECT j FROM GenerationJob j WHERE j.userId = :userId AND j.purgedAt IS NULL "
         + "AND j.status IN (ai.godance.domain.JobStatus.DONE, ai.godance.domain.JobStatus.FAILED, "
         + "ai.godance.domain.JobStatus.CANCELLED)")
    List<GenerationJob> findUnpurgedEndedByUserId(@Param("userId") UUID userId);

    List<GenerationJob> findByUserIdAndStatusIn(UUID userId, Collection<JobStatus> statuses);

    /**
     * Redacts an ended job whose media has been deleted: the prompt becomes
     * {@code {"purged": true}}, the output and webhook URLs are cleared,
     * {@code purged_at} is set and the job stops being a saved one. 0 when
     * the job is still open or already purged.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE generation_jobs
               SET prompt_json = jsonb_build_object('purged', true),
                   output_url  = NULL,
                   webhook_url = NULL,
                   saved_at    = NULL,
                   purged_at   = :now
             WHERE id = :id
               AND purged_at IS NULL
               AND status IN ('DONE', 'FAILED', 'CANCELLED')
            """, nativeQuery = true)
    int redactPurged(@Param("id") UUID id, @Param("now") Instant now);

    // ===== Saved generations (V27) =======================================
    // A saved job is kept past the retention period. Only a DONE, unpurged
    // job can be saved, and the purge clears the flag (redactPurged above).

    /** How many jobs the user has saved: what the limit counts. */
    @Query(value = "SELECT COUNT(*) FROM generation_jobs WHERE user_id = :userId AND saved_at IS NOT NULL",
           nativeQuery = true)
    long countSavedByUserId(@Param("userId") UUID userId);

    /**
     * Saves the user's DONE, unpurged job that is not saved yet. 0 when it
     * is not one, which includes a job that is already saved.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE generation_jobs
               SET saved_at = :now
             WHERE id = :id
               AND user_id = :userId
               AND status = 'DONE'
               AND purged_at IS NULL
               AND saved_at IS NULL
            """, nativeQuery = true)
    int markSaved(@Param("id") UUID id, @Param("userId") UUID userId, @Param("now") Instant now);

    /** Un-saves the user's job; 0 when it was not saved. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE generation_jobs
               SET saved_at = NULL
             WHERE id = :id
               AND user_id = :userId
               AND saved_at IS NOT NULL
            """, nativeQuery = true)
    int clearSaved(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Postgres advisory lock held until the caller's transaction ends, so
     * one user's saved jobs are counted against the limit and written one
     * request at a time. The two-key form, whose keys never collide with the
     * one-key locks used elsewhere. Must run inside a transaction.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:space, :key)) AS held", nativeQuery = true)
    int lockSavedJobsOf(@Param("space") int space, @Param("key") int key);

    /**
     * Locks the job's row until the caller's transaction ends, and returns
     * its id only while the nightly purge may still delete it: ended, not
     * purged and not saved. A save that is in flight is waited for, and its
     * job then comes back empty. Must run inside a transaction.
     */
    @Query(value = """
            SELECT j.id FROM generation_jobs j
             WHERE j.id = :id
               AND j.purged_at IS NULL
               AND j.saved_at IS NULL
               AND j.status IN ('DONE', 'FAILED', 'CANCELLED')
               FOR UPDATE
            """, nativeQuery = true)
    Optional<UUID> lockIfStillPurgeable(@Param("id") UUID id);
}
