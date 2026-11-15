package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.RecipeBatchRow;

/**
 * The rows of recipe batches (V36).
 *
 * <p>A row moves PENDING → RUNNING → DONE or FAILED, or PENDING → SKIPPED,
 * and every step is a conditional update: it matches the row only in the
 * state the step starts from, so a step taken twice changes nothing the
 * second time. That, with the one-job-per-row index on
 * {@code generation_jobs}, is what keeps a row from being submitted twice.
 */
public interface RecipeBatchRowRepository extends JpaRepository<RecipeBatchRow, UUID> {

    /** A page of a batch's rows in row order: those whose index is above {@code after}. */
    @Query(value = """
            SELECT * FROM recipe_batch_rows r
             WHERE r.batch_id = :batchId AND r.row_index > :after
             ORDER BY r.row_index
             LIMIT :limit
            """, nativeQuery = true)
    List<RecipeBatchRow> findPage(@Param("batchId") UUID batchId, @Param("after") int after,
                                  @Param("limit") int limit);

    /** The batch's rows that are RUNNING, in row order. */
    @Query(value = """
            SELECT * FROM recipe_batch_rows r
             WHERE r.batch_id = :batchId AND r.status = 'RUNNING'
             ORDER BY r.row_index
            """, nativeQuery = true)
    List<RecipeBatchRow> findRunning(@Param("batchId") UUID batchId);

    /** The next rows to start: PENDING, lowest index first. */
    @Query(value = """
            SELECT * FROM recipe_batch_rows r
             WHERE r.batch_id = :batchId AND r.status = 'PENDING'
             ORDER BY r.row_index
             LIMIT :limit
            """, nativeQuery = true)
    List<RecipeBatchRow> findNextPending(@Param("batchId") UUID batchId, @Param("limit") int limit);

    /** How many of the batch's rows are RUNNING. */
    @Query(value = "SELECT COUNT(*) FROM recipe_batch_rows WHERE batch_id = :batchId AND status = 'RUNNING'",
           nativeQuery = true)
    int countRunning(@Param("batchId") UUID batchId);

    /**
     * Claims a row: PENDING → RUNNING, while its batch may still start rows.
     * 0 when the row is no longer pending or the batch was paused or
     * cancelled meanwhile; the caller then starts nothing.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batch_rows r
               SET status = 'RUNNING', started_at = :now
             WHERE r.id = :id
               AND r.status = 'PENDING'
               AND EXISTS (SELECT 1 FROM recipe_batches b
                            WHERE b.id = r.batch_id AND b.status IN ('QUEUED', 'RUNNING'))
            """, nativeQuery = true)
    int claim(@Param("id") UUID id, @Param("now") Instant now);

    /**
     * Writes the row's job. Runs in the transaction that creates the job
     * and takes its pre-debit: 0 means the row is not a claimed row waiting
     * for its job, and the caller must fail that transaction.
     */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batch_rows
               SET job_id = :jobId
             WHERE id = :id AND status = 'RUNNING' AND job_id IS NULL
            """, nativeQuery = true)
    int attachJob(@Param("id") UUID id, @Param("jobId") UUID jobId);

    /** A claimed row that never got a job goes back to PENDING. 0 when it has one, or is not claimed. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batch_rows
               SET status = 'PENDING', started_at = NULL
             WHERE id = :id AND status = 'RUNNING' AND job_id IS NULL
            """, nativeQuery = true)
    int release(@Param("id") UUID id);

    /** A claimed row that never got a job is SKIPPED: its batch was cancelled first. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batch_rows
               SET status = 'SKIPPED', completed_at = :now
             WHERE id = :id AND status = 'RUNNING' AND job_id IS NULL
            """, nativeQuery = true)
    int skipUnstarted(@Param("id") UUID id, @Param("now") Instant now);

    /** A claimed row that could not be given a job ends FAILED, with why. It was charged nothing. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batch_rows
               SET status = 'FAILED', error = :error, completed_at = :now
             WHERE id = :id AND status = 'RUNNING' AND job_id IS NULL
            """, nativeQuery = true)
    int failUnstarted(@Param("id") UUID id, @Param("error") String error, @Param("now") Instant now);

    /** A running row takes its job's outcome: DONE with what it cost, or FAILED with why. 0 when it already had. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batch_rows
               SET status = :status, cost_cents = :costCents, error = :error, completed_at = :now
             WHERE id = :id AND status = 'RUNNING' AND job_id IS NOT NULL
            """, nativeQuery = true)
    int finish(@Param("id") UUID id, @Param("status") String status, @Param("costCents") long costCents,
               @Param("error") String error, @Param("now") Instant now);

    /** Cancelling a batch: every row that had not started is SKIPPED. Running rows are left to finish. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE recipe_batch_rows
               SET status = 'SKIPPED', completed_at = :now
             WHERE batch_id = :batchId AND status = 'PENDING'
            """, nativeQuery = true)
    int skipPending(@Param("batchId") UUID batchId, @Param("now") Instant now);

    /** The purge of a job: the values its row held go with the job's prompt. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "UPDATE recipe_batch_rows SET variables = NULL WHERE job_id = :jobId", nativeQuery = true)
    int redactForJob(@Param("jobId") UUID jobId);

    /** How many rows of a batch are in each status, and what the DONE ones cost. */
    interface StatusCount {
        UUID getBatchId();
        String getStatus();
        long getRowCount();
        long getCostCents();
    }

    @Query(value = """
            SELECT r.batch_id AS batchId, r.status AS status, COUNT(*) AS rowCount,
                   COALESCE(SUM(r.cost_cents), 0) AS costCents
              FROM recipe_batch_rows r
             WHERE r.batch_id IN (:batchIds)
             GROUP BY r.batch_id, r.status
            """, nativeQuery = true)
    List<StatusCount> countByStatus(@Param("batchIds") Collection<UUID> batchIds);

    /** The total size in bytes of every stored output of the batch's rows: what the zip would hold. */
    @Query(value = """
            SELECT COALESCE(SUM(o.size_bytes), 0)
              FROM recipe_batch_rows r
              JOIN generation_job_outputs o ON o.job_id = r.job_id
             WHERE r.batch_id = :batchId
            """, nativeQuery = true)
    long sumOutputBytes(@Param("batchId") UUID batchId);
}
