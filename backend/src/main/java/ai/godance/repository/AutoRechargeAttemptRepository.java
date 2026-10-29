package ai.godance.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.AutoRechargeAttempt;

public interface AutoRechargeAttemptRepository extends JpaRepository<AutoRechargeAttempt, UUID> {
    Optional<AutoRechargeAttempt> findByUserIdAndStatus(UUID userId, AutoRechargeAttempt.Status status);
    List<AutoRechargeAttempt> findByUserIdOrderByCreatedAtDesc(UUID userId);
}
