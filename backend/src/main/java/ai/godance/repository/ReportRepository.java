package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.Report;

public interface ReportRepository extends JpaRepository<Report, UUID> {

    /** The open reports, oldest first: the first two hundred. */
    List<Report> findTop200ByStatusOrderByCreatedAtAscIdAsc(String status);

    /** Reports the account made since then: what the hourly limit counts. */
    long countByReporterIdAndCreatedAtAfter(UUID reporterId, Instant after);

    List<Report> findByPublishedRecipeIdInAndStatus(Collection<UUID> publishedRecipeIds, String status);

    List<Report> findByProfileUserIdAndStatus(UUID profileUserId, String status);

    boolean existsByReporterIdAndPublishedRecipeIdAndStatus(UUID reporterId, UUID publishedRecipeId, String status);

    boolean existsByReporterIdAndProfileUserIdAndStatus(UUID reporterId, UUID profileUserId, String status);

    /** Closes the open reports on a published recipe: a moderator decided. Returns how many. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reports SET status = 'RESOLVED', resolved_by = :moderatorId, resolved_at = :now
             WHERE published_recipe_id = :id AND status = 'OPEN'
            """, nativeQuery = true)
    int resolveForRecipe(@Param("id") UUID publishedRecipeId, @Param("moderatorId") UUID moderatorId,
                         @Param("now") Instant now);

    /** Closes the open reports on a profile. Returns how many. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            UPDATE reports SET status = 'RESOLVED', resolved_by = :moderatorId, resolved_at = :now
             WHERE profile_user_id = :userId AND status = 'OPEN'
            """, nativeQuery = true)
    int resolveForProfile(@Param("userId") UUID profileUserId, @Param("moderatorId") UUID moderatorId,
                          @Param("now") Instant now);

    /** The reports an account made: closing it deletes them. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM reports WHERE reporter_id = :userId", nativeQuery = true)
    int deleteByReporterId(@Param("userId") UUID reporterId);
}
