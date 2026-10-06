package ai.godance.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.repository.Repository;

import ai.godance.domain.AuditLogEntry;

/**
 * Constrained repository — exposes only insert + read, never delete or
 * update. Append-only by API surface (HANDOFF.md: "no @Modifying queries
 * against this table"). Do not add update methods here; extend
 * {@code Repository<>} (not {@code JpaRepository<>}) intentionally so
 * delete/update can't be invoked through this interface.
 */
public interface AuditLogEntryRepository extends Repository<AuditLogEntry, UUID> {

    AuditLogEntry save(AuditLogEntry entry);

    Optional<AuditLogEntry> findById(UUID id);

    List<AuditLogEntry> findByEventType(String eventType);

    org.springframework.data.domain.Page<AuditLogEntry> findAllByOrderByCreatedAtDesc(
            org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<AuditLogEntry> findByEventTypeOrderByCreatedAtDesc(
            String eventType, org.springframework.data.domain.Pageable pageable);

    org.springframework.data.domain.Page<AuditLogEntry> findByEventTypeInOrderByCreatedAtDesc(
            java.util.Collection<String> eventTypes, org.springframework.data.domain.Pageable pageable);
}

