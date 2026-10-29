package ai.godance.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.AuditLogEntry;

/**
 * The one way audit rows are ever deleted: the 365-day retention purge in
 * {@link ai.godance.service.DataRetentionService}. Kept apart from
 * {@link AuditLogEntryRepository} so that interface stays insert + read
 * and nothing else can delete or rewrite an audit row.
 */
public interface AuditLogRetentionRepository extends Repository<AuditLogEntry, UUID> {

    /** Deletes up to {@code limit} audit rows created before {@code cutoff}, oldest first. */
    @Transactional
    @Modifying
    @Query(value = """
            DELETE FROM audit_logs
             WHERE id IN (SELECT id FROM audit_logs
                           WHERE created_at < :cutoff
                           ORDER BY created_at
                           LIMIT :limit)
            """, nativeQuery = true)
    int deleteCreatedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);
}
