package ai.godance.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.Repository;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.SavedRecipe;

/** A user's saved gallery recipes (V27): a list of slugs. */
public interface SavedRecipeRepository extends Repository<SavedRecipe, Long> {

    /** The user's saved recipes, newest first. */
    List<SavedRecipe> findByUserIdOrderBySavedAtDescIdDesc(UUID userId);

    long countByUserId(UUID userId);

    boolean existsByUserIdAndSlug(UUID userId, String slug);

    /** Saves the slug for the user; 0 when it was already saved. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = """
            INSERT INTO saved_recipes (user_id, slug, saved_at)
            VALUES (:userId, :slug, :now)
            ON CONFLICT (user_id, slug) DO NOTHING
            """, nativeQuery = true)
    int insertIfAbsent(@Param("userId") UUID userId, @Param("slug") String slug, @Param("now") Instant now);

    /** Un-saves the slug; 0 when it was not saved. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM saved_recipes WHERE user_id = :userId AND slug = :slug", nativeQuery = true)
    int deleteByUserIdAndSlug(@Param("userId") UUID userId, @Param("slug") String slug);

    /** Every saved recipe of the user (account closure); how many there were. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM saved_recipes WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);

    /**
     * Postgres advisory lock held until the caller's transaction ends, so
     * one user's saved recipes are counted against the cap and written one
     * request at a time. Must run inside a transaction.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:space, :key)) AS held", nativeQuery = true)
    int lockSavedRecipesOf(@Param("space") int space, @Param("key") int key);
}
