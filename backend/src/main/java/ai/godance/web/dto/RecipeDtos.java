package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.Modality;
import ai.godance.domain.Recipe;
import ai.godance.domain.RecipeVersion;

/** {@code /v1/recipes/**}, as the round-10 contract has them. Field names are snake_case. */
public final class RecipeDtos {

    private RecipeDtos() { }

    /** A template variable: its name and the value it has when none is given. */
    public record RecipeVariable(String name, @JsonProperty("default") String defaultValue) { }

    /** {@code POST /v1/recipes}: a recipe and its first version. */
    public record CreateRecipeRequest(
            String title,
            String modality,
            String template,
            List<RecipeVariable> variables,
            String model,
            Map<String, Object> params,
            @JsonProperty("source_gallery_recipe") String sourceGalleryRecipe
    ) { }

    /** {@code POST /v1/recipes/{id}/versions}: what the next version holds. */
    public record NewVersionRequest(
            String template,
            List<RecipeVariable> variables,
            String model,
            Map<String, Object> params
    ) { }

    /** {@code PATCH /v1/recipes/{id}}: the one thing about a recipe itself that can change. */
    public record RenameRecipeRequest(String title) { }

    /** {@code "recipe"} on a generation request: the version to run and values for its variables. */
    public record RecipeRef(
            @JsonProperty("version_id") UUID versionId,
            Map<String, String> variables
    ) { }

    public record RecipeVersionView(
            UUID id,
            int number,
            String template,
            List<RecipeVariable> variables,
            String model,
            Map<String, Object> params,
            @JsonProperty("created_at") Instant createdAt
    ) {
        public static RecipeVersionView from(RecipeVersion v) {
            List<RecipeVariable> variables = v.getVariables().stream()
                    .map(variable -> new RecipeVariable(variable.get("name"), variable.get("default")))
                    .toList();
            return new RecipeVersionView(v.getId(), v.getNumber(), v.getTemplate(), variables, v.getModel(),
                    v.getParams(), v.getCreatedAt());
        }
    }

    /**
     * A recipe with its latest version. {@code attempts} is how many jobs
     * ran any of its versions; {@code spent_cents} is what the finished ones
     * were charged, in whole cents.
     */
    public record RecipeView(
            UUID id,
            String title,
            Modality modality,
            @JsonInclude(JsonInclude.Include.ALWAYS)
            @JsonProperty("source_gallery_recipe") String sourceGalleryRecipe,
            @JsonProperty("created_at") Instant createdAt,
            @JsonProperty("updated_at") Instant updatedAt,
            @JsonProperty("latest_version") RecipeVersionView latestVersion,
            long attempts,
            @JsonProperty("spent_cents") long spentCents
    ) {
        public static RecipeView from(Recipe r, RecipeVersion latest, long attempts, long spentCents) {
            return new RecipeView(r.getId(), r.getTitle(), r.getModality(), r.getSourceGalleryRecipe(),
                    r.getCreatedAt(), r.getUpdatedAt(), RecipeVersionView.from(latest), attempts, spentCents);
        }
    }
}
