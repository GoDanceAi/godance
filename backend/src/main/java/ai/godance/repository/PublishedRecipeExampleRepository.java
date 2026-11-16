package ai.godance.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import ai.godance.domain.PublishedRecipeExample;

public interface PublishedRecipeExampleRepository extends JpaRepository<PublishedRecipeExample, UUID> {

    List<PublishedRecipeExample> findByPublishedRecipeIdOrderByPositionAsc(UUID publishedRecipeId);

    List<PublishedRecipeExample> findByPublishedRecipeIdInOrderByPositionAsc(Collection<UUID> publishedRecipeIds);

    @Modifying(flushAutomatically = true, clearAutomatically = true)
    @Query(value = "DELETE FROM published_recipe_examples WHERE published_recipe_id = :id", nativeQuery = true)
    int deleteByPublishedRecipeId(@Param("id") UUID publishedRecipeId);
}
