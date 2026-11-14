package ai.godance.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobChannel;

/**
 * Where a generation job came from: everything about a request's origin
 * that job creation records on the {@link GenerationJob} row (V28, V29).
 * Both generation services take it as one parameter, so what a later round
 * records about a job's origin is added here and not to their signatures.
 *
 * @param channel          how the job was made; decided here from the
 *                         caller's credentials, never read from the request
 * @param galleryRecipe    the slug of the gallery recipe the request named, or null
 * @param recipeVersionId  the version of the caller's own recipe the job
 *                         runs, or null for a plain prompt
 * @param recipeVariables  the value each of that version's variables has in
 *                         this run, or null. Stored with the job's request,
 *                         so the purge redacts it with the prompt
 */
public record JobOrigin(JobChannel channel, String galleryRecipe, UUID recipeVersionId,
                        Map<String, String> recipeVariables) {

    /** Where a job's stored request keeps the values its recipe's variables had. */
    public static final String RECIPE_VARIABLES_KEY = "recipe_variables";

    public JobOrigin {
        if (channel == null) throw new IllegalArgumentException("a job's channel is always known");
        if ((recipeVersionId == null) != (recipeVariables == null)) {
            throw new IllegalArgumentException("a recipe version and its variables come together");
        }
    }

    /**
     * The origin of a request by this caller. {@code apiKeyId} is the API
     * key it authenticated with, or null for a signed-in session.
     *
     * @throws org.springframework.web.server.ResponseStatusException 400
     *         {@code invalid_gallery_recipe} when {@code galleryRecipe} is
     *         given and is not a slug
     */
    public static JobOrigin of(UUID apiKeyId, String galleryRecipe) {
        return new JobOrigin(apiKeyId != null ? JobChannel.API : JobChannel.STUDIO,
                GalleryRecipeSlug.requireValidOrNull(galleryRecipe), null, null);
    }

    /** This origin, for a job that runs a version of the caller's recipe with these values. */
    public JobOrigin withRecipe(UUID versionId, Map<String, String> variables) {
        return new JobOrigin(channel, galleryRecipe, versionId, new LinkedHashMap<>(variables));
    }

    /** Records this origin on a job that has not been saved yet. */
    public void applyTo(GenerationJob job) {
        job.setChannel(channel);
        job.setGalleryRecipe(galleryRecipe);
        if (recipeVersionId != null) {
            job.setRecipeVersionId(recipeVersionId);
            job.getPrompt().put(RECIPE_VARIABLES_KEY, new LinkedHashMap<>(recipeVariables));
        }
    }
}
