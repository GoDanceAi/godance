package ai.godance.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

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
}
