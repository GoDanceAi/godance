package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Limits on a user's own recipes ({@link ai.godance.service.RecipeService}).
 * A recipe costs nothing to keep, so the numbers only stop the tables
 * growing without bound.
 *
 * @param maxPerAccount  the most recipes one account can have
 * @param maxVersions    the most versions one recipe can have
 */
@ConfigurationProperties(prefix = "godance.recipes")
public record RecipesProperties(
        @DefaultValue("500") int maxPerAccount,
        @DefaultValue("200") int maxVersions
) {
    public RecipesProperties {
        if (maxPerAccount < 0) {
            throw new IllegalStateException("godance.recipes.max-per-account must be >= 0; got " + maxPerAccount);
        }
        if (maxVersions < 1) {
            throw new IllegalStateException("godance.recipes.max-versions must be >= 1; got " + maxVersions);
        }
    }
}
