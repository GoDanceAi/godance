package ai.godance.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.Profile;

public interface ProfileRepository extends JpaRepository<Profile, UUID> {

    Optional<Profile> findByHandle(String handle);

    List<Profile> findByUserIdIn(Collection<UUID> userIds);

    /**
     * Approved profiles whose owner changed the display name or link since,
     * while the profile is public (its owner has a public recipe): the
     * changes waiting for a moderator, oldest change first.
     */
    @Query(value = """
            SELECT p.* FROM profiles p
             WHERE p.approved_at IS NOT NULL
               AND (p.display_name IS DISTINCT FROM p.approved_display_name
                    OR p.link IS DISTINCT FROM p.approved_link)
               AND p.review_reason IS NULL
               AND EXISTS (SELECT 1 FROM published_recipes r
                            WHERE r.user_id = p.user_id AND r.status = 'PUBLIC')
             ORDER BY p.updated_at, p.user_id
             LIMIT :limit
            """, nativeQuery = true)
    List<Profile> findChangesAwaitingReview(@Param("limit") int limit);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM profiles WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);
}
