package ai.godance.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import ai.godance.domain.ScheduledTrigger;
import ai.godance.domain.TaskKind;

public interface ScheduledTriggerRepository extends JpaRepository<ScheduledTrigger, UUID> {
    List<ScheduledTrigger> findByUserId(UUID userId);
    List<ScheduledTrigger> findByTaskKindAndEnabledTrue(TaskKind taskKind);
}
