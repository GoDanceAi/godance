package ai.godance.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;

public interface GenerationJobRepository extends JpaRepository<GenerationJob, UUID> {
    List<GenerationJob> findByUserIdOrderByCreatedAtDesc(UUID userId);
    List<GenerationJob> findByStatus(JobStatus status);

    org.springframework.data.domain.Page<GenerationJob> findAllByOrderByCreatedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<GenerationJob> findByStatusOrderByCreatedAtDesc(
            JobStatus status, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<GenerationJob> findByModalityOrderByCreatedAtDesc(
            ai.godance.domain.Modality modality, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<GenerationJob> findByStatusAndModalityOrderByCreatedAtDesc(
            JobStatus status, ai.godance.domain.Modality modality, org.springframework.data.domain.Pageable pageable);
}
