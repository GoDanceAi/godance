package ai.godance.repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.Recipe;

public interface RecipeRepository extends JpaRepository<Recipe, UUID> {

    /**
     * One page of a user's recipes, newest first by {@code (created_at, id)}:
     * those strictly older than the cursor. Callers pass a cursor after
     * every recipe for the first page; nothing here is ever bound as null.
     */
    @Query(value = """
            SELECT * FROM recipes r
             WHERE r.user_id = :userId
               AND (r.created_at, r.id) < (:beforeCreatedAt, :beforeId)
             ORDER BY r.created_at DESC, r.id DESC
             LIMIT :limit
            """, nativeQuery = true)
    List<Recipe> findPageForUser(@Param("userId") UUID userId,
                                 @Param("beforeCreatedAt") Instant beforeCreatedAt,
                                 @Param("beforeId") UUID beforeId,
                                 @Param("limit") int limit);

    /** How many recipes the user has: what the limit counts. */
    long countByUserId(UUID userId);

    /** Every recipe of a user, with its versions (the foreign key cascades). Closing an account does this. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM recipes WHERE user_id = :userId", nativeQuery = true)
    int deleteByUserId(@Param("userId") UUID userId);

    /** Deletes one recipe with its versions; its jobs stay, pointing at nothing. */
    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM recipes WHERE id = :id AND user_id = :userId", nativeQuery = true)
    int deleteOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Locks the user's recipe row until the caller's transaction ends, so two
     * new versions of one recipe are numbered one after the other. Empty for
     * an unknown recipe or another user's. Must run inside a transaction.
     */
    @Query(value = "SELECT r.id FROM recipes r WHERE r.id = :id AND r.user_id = :userId FOR UPDATE", nativeQuery = true)
    Optional<UUID> lockOwned(@Param("id") UUID id, @Param("userId") UUID userId);

    /**
     * Postgres advisory lock held until the caller's transaction ends, so
     * one user's recipes are counted against the limit and written one
     * request at a time. Must run inside a transaction.
     */
    @Query(value = "SELECT 1 FROM (SELECT pg_advisory_xact_lock(:space, :key)) AS held", nativeQuery = true)
    int lockRecipesOf(@Param("space") int space, @Param("key") int key);

    /** A recipe's attempts: how many jobs ran any of its versions, and what the finished ones cost. */
    interface Attempts {
        UUID getRecipeId();
        long getAttempts();
        long getSpentCents();
    }

    @Query(value = """
            SELECT v.recipe_id AS recipeId,
                   count(*) AS attempts,
                   coalesce(sum(j.actual_cost_cents) FILTER (WHERE j.status = 'DONE'), 0) AS spentCents
              FROM generation_jobs j
              JOIN recipe_versions v ON v.id = j.recipe_version_id
             WHERE v.recipe_id IN (:recipeIds)
             GROUP BY v.recipe_id
            """, nativeQuery = true)
    List<Attempts> attemptsOf(@Param("recipeIds") Collection<UUID> recipeIds);
}
