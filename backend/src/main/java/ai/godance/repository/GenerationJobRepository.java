package ai.godance.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {
    List<GenerationJob> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<GenerationJob> findByStatus(JobStatus status);
}
