package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.User;

/**
 * Children's accounts whose parent never gave consent (COPPA): found and
 * deleted by {@link ai.godance.service.DataRetentionService}. Such an
 * account could only sign in and wait; it never paid or used the service.
 */
public interface UnapprovedChildRepository extends Repository<User, UUID> {

    /**
     * Locks and returns up to {@code limit} accounts still waiting for consent
     * that was last requested before {@code cutoff}. The row lock makes a
     * consent arriving at the same moment wait, so it can't be lost.
     */
    @Query(value = """
            SELECT id FROM users
             WHERE age_status = 'CONSENT_PENDING'
               AND parental_consent_requested_at < :cutoff
             ORDER BY parental_consent_requested_at
             LIMIT :limit
               FOR UPDATE SKIP LOCKED
            """, nativeQuery = true)
    List<UUID> lockUnapprovedBefore(@Param("cutoff") Instant cutoff, @Param("limit") int limit);

    /** Deletes the accounts; every table that hangs off a user cascades. */
    @Modifying
    @Query(value = "DELETE FROM users WHERE id IN (:ids) AND age_status = 'CONSENT_PENDING'", nativeQuery = true)
    int deleteUnapproved(@Param("ids") Collection<UUID> ids);
}
