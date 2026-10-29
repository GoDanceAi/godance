package ai.godance.repository;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.SpendHold;

public interface SpendHoldRepository extends JpaRepository<SpendHold, String> {

    // A hold is "open" while it is younger than the cutoff and its request
    // has no successful usage row yet; once that row exists, usage_records
    // counts the spend and the hold must not count it again.

    @Query(value = """
            SELECT COALESCE(SUM(h.cents), 0) FROM spend_holds h
            WHERE h.user_id = :userId AND h.created_at > :since
              AND NOT EXISTS (SELECT 1 FROM usage_records u
                              WHERE u.request_id = h.request_id
                                AND (u.outcome IS NULL OR u.outcome = 'OK'))
            """, nativeQuery = true)
    long sumOpenForUser(@Param("userId") UUID userId, @Param("since") Instant since);

    @Query(value = """
            SELECT COALESCE(SUM(h.cents), 0) FROM spend_holds h
            WHERE h.api_key_id = :apiKeyId AND h.created_at > :since
              AND NOT EXISTS (SELECT 1 FROM usage_records u
                              WHERE u.request_id = h.request_id
                                AND (u.outcome IS NULL OR u.outcome = 'OK'))
            """, nativeQuery = true)
    long sumOpenForApiKey(@Param("apiKeyId") UUID apiKeyId, @Param("since") Instant since);

    /** Drops a user's holds that no longer count: expired, or already in usage_records. */
    @Modifying
    @Query(value = """
            DELETE FROM spend_holds h
            WHERE h.user_id = :userId
              AND (h.created_at <= :since
                   OR EXISTS (SELECT 1 FROM usage_records u
                              WHERE u.request_id = h.request_id
                                AND (u.outcome IS NULL OR u.outcome = 'OK')))
            """, nativeQuery = true)
    int deleteClosedForUser(@Param("userId") UUID userId, @Param("since") Instant since);
}
