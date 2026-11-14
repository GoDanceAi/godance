package ai.godance.web.dto;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobChannel;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.service.ImageGenerationService.ImageResult;
import ai.godance.service.ImageGenerationService.UploadedImage;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public final class ImageDtos {

    private ImageDtos() { }

    /**
     * OpenAI-compatible request shape. Three Godance extensions
     * ({@code async}, {@code webhook_url}, {@code gallery_recipe}) pure
     * OpenAI clients ignore. {@code gallery_recipe} is the slug of the
     * gallery recipe this request is a run of; the job records it.
     */
    public record ImageGenerationRequest(
            @NotBlank String model,
            @NotBlank String prompt,
            String size,
            @Min(1) Integer n,
            String style,
            String quality,
            Boolean async,                                       // Godance extension
            @JsonProperty("webhook_url") String webhookUrl,      // Godance extension
            @JsonProperty("gallery_recipe") String galleryRecipe // Godance extension
    ) { }

    /** Sync response — OpenAI-shaped. */
    public record ImageGenerationResponse(
            long created,
            List<ImageData> data,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("job_id") UUID jobId,
            @JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("cost_cents") Long costCents
    ) {
        public static ImageGenerationResponse from(ImageResult result) {
            List<ImageData> data = result.images().stream()
                    .map(ImageGenerationResponse::toData)
                    .toList();
            return new ImageGenerationResponse(result.createdAt(), data,
                    result.jobId(), result.costCents());
        }

        private static ImageData toData(UploadedImage u) {
            return new ImageData(u.url(), u.revisedPrompt());
        }
    }

    public record ImageData(
            String url,
            @JsonProperty("revised_prompt") String revisedPrompt
    ) { }

    /** Async response — 202-style, returned when {@code async=true}. */
    public record AsyncJobAcknowledged(
            @JsonProperty("job_id") UUID jobId,
            String status,
            @JsonProperty("poll_url") String pollUrl
    ) {
        public static AsyncJobAcknowledged forJob(UUID jobId) {
            return new AsyncJobAcknowledged(jobId, "QUEUED", "/v1/jobs/" + jobId);
        }
    }

    // ===== Job poll endpoint =========================================

    /**
     * A job as {@code GET /v1/jobs} and {@code GET /v1/jobs/{id}} return it.
     * The fields up to {@code completedAt} are the original shape; the rest
     * were added in rounds 7 and 9 and are additions only.
     *
     * <ul>
     *   <li>{@code prompt} and {@code params}: what was asked for, with the
     *       request's field names ({@code size}, {@code n}, {@code style},
     *       {@code quality} for images; {@code size}, {@code seconds},
     *       {@code n} for video). After the purge, null and {@code {}}.</li>
     *   <li>{@code outputs}: every stored file, each link signed when the job
     *       is read. Empty until the job is DONE, and again once purged.</li>
     *   <li>{@code outputUrl}: the first output, freshly signed.</li>
     *   <li>{@code mediaExpiresAt}: when the files and prompt are due to be
     *       deleted; null when they are not scheduled for deletion, which a
     *       saved job is not. A date in the past means the next nightly run
     *       deletes them.</li>
     *   <li>{@code saved}: the owner has saved the job, which keeps it past
     *       the retention period ({@code PUT /v1/jobs/{id}/saved}).</li>
     *   <li>{@code channel}: how the job was made, {@code STUDIO} for a
     *       signed-in session and {@code API} for an API key; null for a job
     *       from before this was recorded (round 10). Kept after the purge.</li>
     *   <li>{@code galleryRecipe}: the slug of the gallery recipe the request
     *       named, or null. Kept after the purge.</li>
     * </ul>
     */
    public record JobView(
            UUID id,
            JobStatus status,
            Modality modality,
            Provider provider,
            String model,
            Long estimatedCostCents,
            Long actualCostCents,
            String outputUrl,
            String errorMessage,
            Instant createdAt,
            Instant startedAt,
            Instant completedAt,
            String prompt,
            Map<String, Object> params,
            List<JobOutputView> outputs,
            boolean purged,
            Instant mediaExpiresAt,
            boolean saved,
            JobChannel channel,
            String galleryRecipe
    ) {
        /**
         * {@code outputs} carry links already signed; {@code outputUrl} is the
         * first of them, or the stored link for a job that has none recorded.
         */
        public static JobView from(GenerationJob j, List<JobOutputView> outputs, Instant mediaExpiresAt) {
            boolean purged = j.getPurgedAt() != null;
            List<JobOutputView> shown = purged ? List.of() : outputs;
            String outputUrl = shown.isEmpty() ? j.getOutputUrl() : shown.get(0).url();
            return new JobView(
                    j.getId(), j.getStatus(), j.getModality(), j.getProvider(),
                    j.getModel(), j.getEstimatedCostCents(), j.getActualCostCents(),
                    outputUrl, j.getErrorMessage(),
                    j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt(),
                    purged ? null : promptOf(j),
                    purged ? Map.of() : paramsOf(j),
                    shown, purged, purged ? null : mediaExpiresAt,
                    !purged && j.getSavedAt() != null,
                    j.getChannel(), j.getGalleryRecipe());
        }

        private static String promptOf(GenerationJob j) {
            Map<String, Object> stored = j.getPrompt();
            return stored != null && stored.get("prompt") instanceof String p ? p : null;
        }

        /** The stored request, keyed by the request's field names; video's length is {@code seconds}. */
        private static Map<String, Object> paramsOf(GenerationJob j) {
            Map<String, Object> stored = j.getPrompt() == null ? Map.of() : j.getPrompt();
            Map<String, Object> params = new LinkedHashMap<>();
            if (j.getModality() == Modality.VIDEO) {
                copy(stored, "size", params, "size");
                copy(stored, "duration_seconds", params, "seconds");
                copy(stored, "n", params, "n");
            } else {
                copy(stored, "size", params, "size");
                copy(stored, "n", params, "n");
                copy(stored, "style", params, "style");
                copy(stored, "quality", params, "quality");
            }
            return params;
        }

        private static void copy(Map<String, Object> from, String key, Map<String, Object> to, String as) {
            Object v = from.get(key);
            if (v != null) to.put(as, v);
        }
    }

    /**
     * {@code GET /v1/jobs/saved}: how many generations the caller has saved,
     * and the most the account can ({@code godance.retention.saved-limit}).
     */
    public record SavedJobsView(long count, int limit) { }

    /** One stored file of a job; {@code url} is signed when the job is read. */
    public record JobOutputView(
            int index,
            String url,
            String contentType,
            long sizeBytes,
            String revisedPrompt
    ) { }
}
