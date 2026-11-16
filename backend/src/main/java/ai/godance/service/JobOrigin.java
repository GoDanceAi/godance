package ai.godance.service;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobChannel;

/**
 * Where a generation job came from: everything about a request's origin
 * that job creation records on the {@link GenerationJob} row (V28, V29, V36, V39).
 * Both generation services take it as one parameter, so what a later round
 * records about a job's origin is added here and not to their signatures.
 *
 * @param channel          how the job was made; decided here from the
 *                         caller's credentials or from the batch that makes
 *                         the job, never read from the request
 * @param galleryRecipe    the slug of the gallery recipe the request named, or null
 * @param recipeVersionId  the version of the caller's own recipe the job
 *                         runs, or null for a plain prompt
 * @param recipeVariables  the value each of that version's variables has in
 *                         this run, or null. Stored with the job's request,
 *                         so the purge redacts it with the prompt
 * @param batchRow         the batch row the job is made for (round 12), or
 *                         null; set exactly when the channel is BATCH
 * @param publishedRecipeId the published recipe the job runs (round 14), or
 *                         null. A job runs either the caller's own recipe
 *                         version or a published recipe, never both: a
 *                         published recipe's version is its author's, so
 *                         the job does not record it
 */
public record JobOrigin(JobChannel channel, String galleryRecipe, UUID recipeVersionId,
                        Map<String, String> recipeVariables, BatchRow batchRow, UUID publishedRecipeId) {

    /** Where a job's stored request keeps the values its recipe's variables had. */
    public static final String RECIPE_VARIABLES_KEY = "recipe_variables";

    /**
     * The row of a recipe batch a job is made for.
     *
     * @param rowId      the row; the job records it, and the database allows one job per row
     * @param createdBy  how the row's batch was made: STUDIO or API
     */
    public record BatchRow(UUID rowId, JobChannel createdBy) {
        public BatchRow {
            if (rowId == null) throw new IllegalArgumentException("a batch job names its row");
            if (createdBy != JobChannel.STUDIO && createdBy != JobChannel.API) {
                throw new IllegalArgumentException("a batch is made in the Studio or with an API key");
            }
        }
    }

    public JobOrigin {
        if (channel == null) throw new IllegalArgumentException("a job's channel is always known");
        if (recipeVersionId != null && publishedRecipeId != null) {
            throw new IllegalArgumentException("a job runs the caller's recipe or a published one, not both");
        }
        if ((recipeVersionId == null && publishedRecipeId == null) != (recipeVariables == null)) {
            throw new IllegalArgumentException("a recipe and its variables come together");
        }
        if ((channel == JobChannel.BATCH) != (batchRow != null)) {
            throw new IllegalArgumentException("a job has the BATCH channel exactly when it is made for a batch row");
        }
    }

    /** An origin that runs no published recipe. */
    public JobOrigin(JobChannel channel, String galleryRecipe, UUID recipeVersionId,
                     Map<String, String> recipeVariables, BatchRow batchRow) {
        this(channel, galleryRecipe, recipeVersionId, recipeVariables, batchRow, null);
    }

    /** An origin outside any batch. */
    public JobOrigin(JobChannel channel, String galleryRecipe, UUID recipeVersionId,
                     Map<String, String> recipeVariables) {
        this(channel, galleryRecipe, recipeVersionId, recipeVariables, null, null);
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
                GalleryRecipeSlug.requireValidOrNull(galleryRecipe), null, null, null, null);
    }

    /**
     * The origin of the job made for one row of a recipe batch.
     * {@code createdBy} is how the batch was made: STUDIO or API.
     */
    public static JobOrigin ofBatchRow(UUID rowId, JobChannel createdBy) {
        return new JobOrigin(JobChannel.BATCH, null, null, null, new BatchRow(rowId, createdBy), null);
    }

    /** This origin, for a job that runs a version of the caller's recipe with these values. */
    public JobOrigin withRecipe(UUID versionId, Map<String, String> variables) {
        return new JobOrigin(channel, galleryRecipe, versionId, new LinkedHashMap<>(variables), batchRow, null);
    }

    /** This origin, for a job that runs a published recipe with these values. */
    public JobOrigin withPublishedRecipe(UUID publishedId, Map<String, String> variables) {
        return new JobOrigin(channel, galleryRecipe, null, new LinkedHashMap<>(variables), batchRow, publishedId);
    }

    /** Records this origin on a job that has not been saved yet. */
    public void applyTo(GenerationJob job) {
        job.setChannel(channel);
        job.setGalleryRecipe(galleryRecipe);
        if (recipeVersionId != null) {
            job.setRecipeVersionId(recipeVersionId);
            job.getPrompt().put(RECIPE_VARIABLES_KEY, new LinkedHashMap<>(recipeVariables));
        }
        if (publishedRecipeId != null) {
            job.setPublishedRecipeId(publishedRecipeId);
            job.getPrompt().put(RECIPE_VARIABLES_KEY, new LinkedHashMap<>(recipeVariables));
        }
        if (batchRow != null) {
            job.setBatchRowId(batchRow.rowId());
            job.setBatchChannel(batchRow.createdBy());
        }
    }
}
