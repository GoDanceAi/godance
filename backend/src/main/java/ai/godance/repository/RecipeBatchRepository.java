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

import ai.godance.domain.RecipeBatch;

/**
 * Recipe batches (V36). A batch's state is changed only by the conditional
 * updates here: each matches the batch only in the state it must be in, so
 * a cancel, a pause and the runner can race and exactly one of them wins.
 */
public interface RecipeBatchRepository extends JpaRepository<RecipeBatch, UUID> {

    /** One of the user's batches. Another user's is not found. */
    Optional<RecipeBatch> findByIdAndUserId(UUID id, UUID userId);

    /**
     * One page of a user's batches, newest first by {@code (created_at, id)}:
     * those strictly older than the cursor, optionally of one recipe. An
     * empty {@code recipeId} matches any. Callers pass a cursor after every
     * batch for the first page; nothing here is bound as null.
     */
    @Query(value = """
            SELECT * FROM recipe_batches b
             WHERE b.user_id = :userId
               AND (:recipeId = '' OR b.recipe_id = CAST(NULLIF(:recipeId, '') AS uuid))
               AND (b.created_at, b.id) < (:beforeCreatedAt, :beforeId)
             ORDER BY b.created_at DESC, b.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<RecipeBatch> findPageForUser(@Param("userId") UUID userId,
                                      @Param("recipeId") String recipeId,
                                      @Param("beforeCreatedAt") Instant beforeCreatedAt,
                                      @Param("beforeId") UUID beforeId,
                                      @Param("limit") int limit);

    /** How many of the user's batches are not finished: QUEUED, RUNNING or PAUSED. What the limit counts. */
    @Query(value = """
            SELECT COUNT(*) FROM recipe_batches
             WHERE user_id = :userId AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
            """, nativeQuery = true)
    long countUnfinished(@Param("userId") UUID userId);

    /**
     * Postgres advisory lock held until the caller's transaction ends, so
     * one user's unfinished batches are counted against the limit and
     * written one request at a time. Must run inside a transaction.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:space, :key)) AS held", nativeQuery = true)
    int lockBatchesOf(@Param("space") int space, @Param("key") int key);

    /**
     * The batches that are not over, oldest first: what the runner looks at
     * on every pass. A CANCELLED batch stays here until its running rows
     * have finished.
     */
    @Query(value = """
            SELECT b.id FROM recipe_batches b
             WHERE b.completed_at IS NULL
             ORDER BY b.created_at, b.id
             LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findOpenIds(@Param("limit") int limit);

    /** Records when the first row started, and QUEUED → RUNNING. 0 when the batch may no longer start rows. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batches
               SET status = 'RUNNING', started_at = COALESCE(started_at, :now)
             WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
            """, nativeQuery = true)
    int markRunning(@Param("id") UUID id, @Param("now") Instant now);

    /** A batch that may start rows → PAUSED, with why. 0 when it was no longer one. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batches
               SET status = 'PAUSED', pause_reason = :reason, pause_detail = :detail
             WHERE id = :id AND status IN ('QUEUED', 'RUNNING')
            """, nativeQuery = true)
    int pause(@Param("id") UUID id, @Param("reason") String reason, @Param("detail") String detail);

    /** PAUSED → RUNNING. 0 when the batch is not paused. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batches
               SET status = 'RUNNING', pause_reason = NULL, pause_detail = NULL
             WHERE id = :id AND user_id = :userId AND status = 'PAUSED'
            """, nativeQuery = true)
    int resume(@Param("id") UUID id, @Param("userId") UUID userId);

    /** An unfinished batch → CANCELLED. 0 when it had already ended. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batches
               SET status = 'CANCELLED', pause_reason = NULL, pause_detail = NULL
             WHERE id = :id AND status IN ('QUEUED', 'RUNNING', 'PAUSED')
            """, nativeQuery = true)
    int cancel(@Param("id") UUID id);

    /**
     * Ends a batch none of whose rows is pending or running: a batch that
     * was starting rows becomes DONE, and a CANCELLED one gets the time its
     * last row ended. 0 when a row is still open, the batch is paused, or it
     * has already been ended: only one caller ever gets 1.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batches b
               SET status = CASE WHEN b.status = 'CANCELLED' THEN 'CANCELLED' ELSE 'DONE' END,
                   completed_at = :now
             WHERE b.id = :id
               AND b.completed_at IS NULL
               AND b.status IN ('QUEUED', 'RUNNING', 'CANCELLED')
               AND NOT EXISTS (SELECT 1 FROM recipe_batch_rows r
                                WHERE r.batch_id = b.id AND r.status IN ('PENDING', 'RUNNING'))
            """, nativeQuery = true)
    int endIfSettled(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Takes the right to send the batch's webhook: 1 for the one caller that
     * may send it, 0 for everyone after, and for a batch with no webhook or
     * one that has not ended.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batches
               SET webhook_sent_at = :now
             WHERE id = :id
               AND webhook_sent_at IS NULL
               AND completed_at IS NOT NULL
               AND webhook_url IS NOT NULL
            """, nativeQuery = true)
    int claimWebhook(@Param("id") UUID id, @Param("now") Instant now);

    /** Ended batches whose webhook nobody has sent yet (a restart between ending a batch and sending). */
    @Query(value = """
            SELECT b.id FROM recipe_batches b
             WHERE b.completed_at IS NOT NULL
               AND b.webhook_url IS NOT NULL
               AND b.webhook_sent_at IS NULL
             ORDER BY b.completed_at
             LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findEndedWithUnsentWebhook(@Param("limit") int limit);

    /** Deletes every batch of a user, and with them (ON DELETE CASCADE) their rows and input holds. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM recipe_batches WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * Ended batches created before {@code cutoff} none of whose rows has a
     * job that is still unpurged: nothing is left to show for them. What the
     * nightly run deletes.
     */
    @Query(value = """
            SELECT b.id FROM recipe_batches b
             WHERE b.completed_at IS NOT NULL
               AND b.created_at < :cutoff
               AND NOT EXISTS (SELECT 1 FROM recipe_batch_rows r
                                 JOIN generation_jobs j ON j.id = r.job_id
                                WHERE r.batch_id = b.id AND j.purged_at IS NULL)
             ORDER BY b.created_at, b.id
             LIMIT :limit
            """, nativeQuery = true)
    List<UUID> findDeletable(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Deletes these batches, with their rows. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM recipe_batches WHERE id IN (:ids)", nativeQuery = true)
    int deleteByIds(@Param("ids") Collection<UUID> ids);
}
