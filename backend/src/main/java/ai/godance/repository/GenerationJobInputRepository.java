package ai.godance.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.GenerationJobInput;

/**
 * Which job uses which input (V31). Rows are written with the job and
 * deleted when the job is purged; nothing updates one.
 */
public interface GenerationJobInputRepository extends Repository<GenerationJobInput, GenerationJobInput.Key> {

    List<GenerationJobInput> findByJobIdOrderByPositionAsc(UUID jobId);

    /** The inputs of several jobs at once (one query for a page of the job list). */
    List<GenerationJobInput> findByJobIdInOrderByJobIdAscPositionAsc(Collection<UUID> jobIds);

    /** Records an uploaded file as one input of a job. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO generation_job_inputs (job_id, position, role, file_id)
            VALUES (:jobId, :position, :role, :fileId)
            """, nativeQuery = true)
    int insertFile(@Param("jobId") UUID jobId, @Param("position") int position, @Param("role") String role,
                   @Param("fileId") UUID fileId);

    /** Records an output of another of the user's jobs as one input of a job. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO generation_job_inputs (job_id, position, role, source_job_id, source_index)
            VALUES (:jobId, :position, :role, :sourceJobId, :sourceIndex)
            """, nativeQuery = true)
    int insertJobOutput(@Param("jobId") UUID jobId, @Param("position") int position, @Param("role") String role,
                        @Param("sourceJobId") UUID sourceJobId, @Param("sourceIndex") int sourceIndex);

    /** The files a job uses. */
    @Query(value = "SELECT DISTINCT file_id FROM generation_job_inputs WHERE job_id = :jobId AND file_id IS NOT NULL",
           nativeQuery = true)
    List<UUID> findFileIdsByJobId(@Param("jobId") UUID jobId);

    /** Deletes a job's input rows; how many there were. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM generation_job_inputs WHERE job_id = :jobId", nativeQuery = true)
    int deleteByJobId(@Param("jobId") UUID jobId);
}
