package ai.godance.repository;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.RecipeVersion;

public interface RecipeVersionRepository extends JpaRepository<RecipeVersion, UUID> {

    /** A recipe's versions, newest first. */
    List<RecipeVersion> findByRecipeIdOrderByNumberDesc(UUID recipeId);

    /** The latest version of a recipe. Every recipe has one. */
    Optional<RecipeVersion> findFirstByRecipeIdOrderByNumberDesc(UUID recipeId);

    /** How many versions a recipe has: what the limit counts. */
    long countByRecipeId(UUID recipeId);

    /** The latest version of each of these recipes. */
    @Query(value = """
            SELECT DISTINCT ON (v.recipe_id) v.*
              FROM recipe_versions v
             WHERE v.recipe_id IN (:recipeIds)
             ORDER BY v.recipe_id, v.number DESC
            """, nativeQuery = true)
    List<RecipeVersion> findLatestOf(@Param("recipeIds") Collection<UUID> recipeIds);
}
