package ai.godance.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.GenerationJobOutput;

public interface GenerationJobOutputRepository extends JpaRepository<GenerationJobOutput, Long> {

    List<GenerationJobOutput> findByJobIdOrderByOutputIndexAsc(UUID jobId);

    /** The outputs of several jobs at once (one query for a page of the job list). */
    List<GenerationJobOutput> findByJobIdInOrderByJobIdAscOutputIndexAsc(Collection<UUID> jobIds);

    /** Deletes a job's output rows; how many there were. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM generation_job_outputs WHERE job_id = :jobId", nativeQuery = true)
    int deleteByJobId(@Param("jobId") UUID jobId);

    /**
     * Records an output found in storage for a job finished before V23. A
     * row already there (another read got to it first) is left alone, and
     * nothing is written once the job is purged, so a read racing the purge
     * cannot leave rows behind. 1 when a row was written.
     */
    @Transactional
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO generation_job_outputs
                   (job_id, output_index, storage_key, content_type, size_bytes, revised_prompt, created_at)
            SELECT :jobId, :outputIndex, :storageKey, :contentType, :sizeBytes, NULL, now()
             WHERE EXISTS (SELECT 1 FROM generation_jobs j WHERE j.id = :jobId AND j.purged_at IS NULL)
            ON CONFLICT (job_id, output_index) DO NOTHING
            """, nativeQuery = true)
    int insertFoundInStorage(@Param("jobId") UUID jobId, @Param("outputIndex") int outputIndex,
                             @Param("storageKey") String storageKey, @Param("contentType") String contentType,
                             @Param("sizeBytes") long sizeBytes);
}
