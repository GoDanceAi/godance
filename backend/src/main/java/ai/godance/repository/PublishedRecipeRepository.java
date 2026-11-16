package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.PublishedRecipe;
import ai.godance.domain.PublishedStatus;
import jakarta.persistence.LockModeType;

public interface PublishedRecipeRepository extends JpaRepository<PublishedRecipe, UUID> {

    Optional<PublishedRecipe> findByPublicId(String publicId);

    /**
     * Postgres advisory lock held until the caller's transaction ends, so one
     * account's submissions are counted against the limits and written one
     * request at a time. Must run inside a transaction.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:space, :key)) AS held", nativeQuery = true)
    int lockPublishingOf(@Param("space") int space, @Param("key") int key);

    boolean existsByPublicId(String publicId);

    List<PublishedRecipe> findByIdIn(Collection<UUID> ids);

    /** The recipe's latest submission, whatever became of it: what its author sees. */
    Optional<PublishedRecipe> findFirstByRecipeIdOrderBySubmittedAtDesc(UUID recipeId);

    /** The recipe's submission that is pending or public, if there is one. */
    Optional<PublishedRecipe> findFirstByRecipeIdAndStatusIn(UUID recipeId, Collection<PublishedStatus> statuses);

    List<PublishedRecipe> findByRecipeIdAndStatusIn(UUID recipeId, Collection<PublishedStatus> statuses);

    List<PublishedRecipe> findByUserIdAndStatusIn(UUID userId, Collection<PublishedStatus> statuses);

    long countByUserIdAndStatusIn(UUID userId, Collection<PublishedStatus> statuses);

    long countByUserIdAndSubmittedAtAfter(UUID userId, Instant after);

    /** The account's other submissions, newest first: what a moderator reads as its history. */
    List<PublishedRecipe> findByUserIdAndIdNotOrderBySubmittedAtDesc(UUID userId, UUID excluded);

    /** The pending submissions, oldest first: the first hundred. */
    List<PublishedRecipe> findTop100ByStatusOrderBySubmittedAtAscIdAsc(PublishedStatus status);

    /** The row, locked until the caller's transaction ends: one moderation step at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM PublishedRecipe r WHERE r.id = :id")
    Optional<PublishedRecipe> lockById(@Param("id") UUID id);

    /**
     * Public recipes, newest first, below the cursor; with a tag, only those
     * that carry it. The cursor is the (published_at, id) of the last row of
     * the previous page; the first page passes a cursor above every row.
     */
    @Query(value = """
            SELECT * FROM published_recipes r
             WHERE r.status = 'PUBLIC'
               AND (r.published_at, r.id) < (:beforeAt, :beforeId)
               AND (CAST(:tag AS text) IS NULL OR r.tags @> jsonb_build_array(CAST(:tag AS text)))
             ORDER BY r.published_at DESC, r.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<PublishedRecipe> findPublicPage(@Param("beforeAt") Instant beforeAt, @Param("beforeId") UUID beforeId,
                                         @Param("tag") String tag, @Param("limit") int limit);

    /** One author's public recipes, newest first, at most {@code limit}. */
    @Query(value = """
            SELECT * FROM published_recipes r
             WHERE r.status = 'PUBLIC' AND r.user_id = :userId
             ORDER BY r.published_at DESC, r.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<PublishedRecipe> findPublicByAuthor(@Param("userId") UUID userId, @Param("limit") int limit);

    /** The public recipes of the accounts {@code followerId} follows, newest first, below the cursor. */
    @Query(value = """
            SELECT r.* FROM published_recipes r
              JOIN follows f ON f.followee_id = r.user_id AND f.follower_id = :followerId
             WHERE r.status = 'PUBLIC'
               AND (r.published_at, r.id) < (:beforeAt, :beforeId)
             ORDER BY r.published_at DESC, r.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<PublishedRecipe> findFeedPage(@Param("followerId") UUID followerId, @Param("beforeAt") Instant beforeAt,
                                       @Param("beforeId") UUID beforeId, @Param("limit") int limit);

    /** Public recipes with an open report: back in the moderation queue, oldest report first. */
    @Query(value = """
            SELECT r.* FROM published_recipes r
             WHERE r.status = 'PUBLIC'
               AND EXISTS (SELECT 1 FROM reports p WHERE p.published_recipe_id = r.id AND p.status = 'OPEN')
             ORDER BY (SELECT min(p.created_at) FROM reports p
                        WHERE p.published_recipe_id = r.id AND p.status = 'OPEN'), r.id
            """, nativeQuery = true)
    List<PublishedRecipe> findReportedPublic();

    /**
     * Unpublished, rejected or removed submissions that still hold an example
     * copy: their copies are deleted, and a delete that failed is retried.
     */
    @Query(value = """
            SELECT r.* FROM published_recipes r
             WHERE r.status IN ('REJECTED', 'REMOVED')
               AND EXISTS (SELECT 1 FROM published_recipe_examples e WHERE e.published_recipe_id = r.id)
             ORDER BY r.updated_at, r.id
             LIMIT :limit
            """, nativeQuery = true)
    List<PublishedRecipe> findClosedWithExamples(@Param("limit") int limit);

    /** How often each recipe was run by anyone but its author: finished jobs, and the accounts that ran them. */
    interface RunCounts {
        UUID getPublishedRecipeId();
        long getRuns();
        long getRunners();
    }

    @Query(value = """
            SELECT j.published_recipe_id AS publishedRecipeId,
                   count(*) AS runs,
                   count(DISTINCT j.user_id) AS runners
              FROM generation_jobs j
              JOIN published_recipes r ON r.id = j.published_recipe_id
             WHERE j.published_recipe_id IN (:ids)
               AND j.user_id <> r.user_id
               AND j.status = 'DONE'
             GROUP BY j.published_recipe_id
            """, nativeQuery = true)
    List<RunCounts> runCounts(@Param("ids") Collection<UUID> ids);
}
