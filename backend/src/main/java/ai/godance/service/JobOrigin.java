package ai.godance.service;

import java.util.UUID;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobChannel;

/**
 * Where a generation job came from: everything about a request's origin
 * that job creation records on the {@link GenerationJob} row (V28). Both
 * generation services take it as one parameter, so what a later round
 * records about a job's origin is added here and not to their signatures.
 *
 * @param channel        how the job was made; decided here from the
 *                       caller's credentials, never read from the request
 * @param galleryRecipe  the slug of the gallery recipe the request named, or null
 */
public record JobOrigin(JobChannel channel, String galleryRecipe) {

    public JobOrigin {
        if (channel == null) throw new IllegalArgumentException("a job's channel is always known");
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
                GalleryRecipeSlug.requireValidOrNull(galleryRecipe));
    }

    /** Records this origin on a job that has not been saved yet. */
    public void applyTo(GenerationJob job) {
        job.setChannel(channel);
        job.setGalleryRecipe(galleryRecipe);
    }
}
