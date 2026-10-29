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
 *
 * <p>Rows are deleted only by the 365-day retention purge, through
 * {@link AuditLogRetentionRepository}.
 */
public interface AuditLogEntryRepository extends Repository<AuditLogEntry, UUID> {

    AuditLogEntry save(AuditLogEntry entry);

    Optional<AuditLogEntry> findById(UUID id);

    List<AuditLogEntry> findByEventType(String eventType);
}
