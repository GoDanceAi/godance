package ai.godance.web.dto;

import java.time.Instant;

import ai.godance.domain.SavedRecipe;

public final class SavedRecipeDtos {
    private SavedRecipeDtos() { }

    /** One entry of {@code GET /v1/saved-recipes}: the gallery slug and when it was saved. */
    public record SavedRecipeView(String slug, Instant savedAt) {
        public static SavedRecipeView from(SavedRecipe recipe) {
            return new SavedRecipeView(recipe.getSlug(), recipe.getSavedAt());
        }
    }
}
