package ai.godance.mcp;

import java.util.Map;
import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Modality;
import ai.godance.service.GenerationQuotes;
import ai.godance.service.GenerationQuotes.Quote;
import ai.godance.service.RecipeRefusedException;
import ai.godance.service.RecipeService;
import ai.godance.web.dto.RecipeDtos.RecipeVersionView;
import ai.godance.web.dto.RecipeDtos.RecipeView;

/**
 * A run of one of the caller's recipes, as {@code run_recipe} makes it and
 * {@code estimate_cost} prices it. The model and settings default to the
 * recipe version's, as a batch's do; a tool argument replaces one setting.
 * The price comes from {@link GenerationQuotes}, which every job's pre-debit
 * uses: an image recipe pays for each image its slots place, as an edit does.
 */
@Component
class RecipePlans {

    /** What a run of the recipe will be made of. */
    record Plan(RecipeView recipe, RecipeVersionView version, Modality modality, String model, Integer n,
                Integer seconds, String size, String style, String quality, int inputImages) { }

    private final RecipeService recipes;
    private final GenerationQuotes quotes;

    RecipePlans(RecipeService recipes, GenerationQuotes quotes) {
        this.recipes = recipes;
        this.quotes = quotes;
    }

    /**
     * The caller's recipe and version (its latest when {@code versionId} is
     * null), with the arguments' {@code model}, {@code n}, {@code seconds},
     * {@code size}, {@code style} and {@code quality} over the version's own.
     * Another user's recipe or version answers 404 {@code recipe_not_found},
     * as one that does not exist.
     */
    Plan plan(UUID userId, UUID recipeId, UUID versionId, ToolArgs args) {
        RecipeView recipe = recipes.get(userId, recipeId);
        RecipeVersionView version = versionId == null ? recipe.latestVersion()
                : recipes.versions(userId, recipeId).stream()
                        .filter(v -> v.id().equals(versionId))
                        .findFirst()
                        .orElseThrow(RecipeRefusedException::notFound);
        Map<String, Object> params = version.params() == null ? Map.of() : version.params();
        String model = args.text("model") != null ? args.text("model") : version.model();
        Integer n = args.has("n") ? args.integerAtLeast("n", 1) : whole(params, "n");
        Integer seconds = args.has("seconds") ? args.integerAtLeast("seconds", 1) : whole(params, "seconds");
        String size = args.has("size") ? args.text("size") : text(params, "size");
        String style = args.has("style") ? args.text("style") : text(params, "style");
        String quality = args.has("quality") ? args.text("quality") : text(params, "quality");
        int inputImages = (int) version.inputs().stream().filter(slot -> "image".equals(slot.role())).count();
        return new Plan(recipe, version, recipe.modality(), model, n, seconds, size, style, quality, inputImages);
    }

    /** The price of one run, exactly the pre-debit its job will take. */
    Quote quote(Plan plan) {
        return plan.modality() == Modality.VIDEO
                ? quotes.video(plan.model(), plan.n(), plan.seconds())
                : quotes.image(plan.model(), plan.n(), plan.inputImages());
    }

    private static Integer whole(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null) return null;
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())
                && number.longValue() >= 1 && number.longValue() <= Integer.MAX_VALUE) {
            return number.intValue();
        }
        throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "the recipe version's params." + name + " is not a whole number of 1 or more");
    }

    private static String text(Map<String, Object> params, String name) {
        Object value = params.get(name);
        return value instanceof String text && !text.isBlank() ? text : null;
    }
}
