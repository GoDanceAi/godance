package ai.godance.service;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.SavedRecipe;
import ai.godance.repository.SavedRecipeRepository;

/**
 * A user's saved gallery recipes: the ones they want to open in the Studio
 * again. {@code GET /v1/saved-recipes}, and {@code PUT} and
 * {@code DELETE /v1/saved-recipes/{slug}}.
 *
 * <p>Only the slug is stored. The gallery is static content on the
 * marketing site and this service never reads it, so a slug is checked for
 * its shape only: a recipe that has since left the gallery stays in the
 * list until its owner removes it. Nothing here is counted against the
 * saved-generations limit ({@link JobSaveService}): there are no files to
 * keep. The list has a cap of its own so that it cannot grow without bound.
 */
@Service
public class SavedRecipeService {

    /** The most recipes one account can save. */
    public static final int MAX_SAVED = 500;

    /** The code of the 409 past {@link #MAX_SAVED}. */
    public static final String CODE_LIMIT_REACHED = "saved_recipes_limit_reached";

    /** A gallery slug: lowercase letters, digits and hyphens, at most 80 characters. */
    private static final Pattern SLUG = Pattern.compile("[a-z0-9-]{1,80}");

    /** First key of the per-user advisory lock; the second is the user id's hash. */
    static final int SAVED_RECIPES_LOCK_SPACE = 7_000_028;

    private final SavedRecipeRepository recipes;

    public SavedRecipeService(SavedRecipeRepository recipes) {
        this.recipes = recipes;
    }

    /** The user's saved recipes, newest first. */
    public List<SavedRecipe> list(UUID userId) {
        return recipes.findByUserIdOrderBySavedAtDescIdDesc(userId);
    }

    /**
     * Saves a recipe for the user. Saving one already saved changes nothing.
     *
     * @throws ResponseStatusException 400 when the slug is not a gallery
     *         slug; 409 {@code saved_recipes_limit_reached} at the cap
     */
    @Transactional
    public void save(UUID userId, String slug) {
        requireSlug(slug);
        recipes.lockSavedRecipesOf(SAVED_RECIPES_LOCK_SPACE, userId.hashCode());
        if (recipes.existsByUserIdAndSlug(userId, slug)) return;
        if (recipes.countByUserId(userId) >= MAX_SAVED) {
            ResponseStatusException full = new ResponseStatusException(HttpStatus.CONFLICT,
                    "You have saved " + MAX_SAVED + " recipes, the most an account can keep. "
                            + "Remove one to save another.");
            full.getBody().setProperty("code", CODE_LIMIT_REACHED);
            throw full;
        }
        recipes.insertIfAbsent(userId, slug, Instant.now());
    }

    /**
     * Un-saves a recipe. Un-saving one that is not saved changes nothing.
     *
     * @throws ResponseStatusException 400 when the slug is not a gallery slug
     */
    @Transactional
    public void unsave(UUID userId, String slug) {
        requireSlug(slug);
        recipes.deleteByUserIdAndSlug(userId, slug);
    }

    private static void requireSlug(String slug) {
        if (slug == null || !SLUG.matcher(slug).matches()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "a recipe slug is lowercase letters, digits and hyphens, at most 80 characters");
        }
    }
}
