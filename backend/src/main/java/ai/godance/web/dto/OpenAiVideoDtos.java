package ai.godance.web.dto;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.web.dto.InputDtos.JobOutputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import tools.jackson.databind.JsonNode;

/**
 * OpenAI's Videos API shapes ({@code /v1/videos}), as its SDKs send and
 * read them. A video here is a Godance video job seen in that shape: the
 * same id, the same row, nothing stored twice.
 *
 * <p>Read from OpenAI's generated SDK (openai-python 3.51.0,
 * {@code types/video.py}, {@code types/video_create_params.py},
 * {@code types/video_delete_response.py}, {@code pagination.py}).
 */
public final class OpenAiVideoDtos {

    private OpenAiVideoDtos() { }

    /**
     * {@code POST /v1/videos} as JSON. OpenAI's fields are {@code model},
     * {@code prompt}, {@code seconds}, {@code size} and
     * {@code input_reference}; the rest are the Godance extensions
     * {@code POST /v1/videos/generations} takes.
     *
     * <p>{@code seconds} is a string in OpenAI's API ({@code "8"}); a number
     * is taken too. {@code model} is a Godance video model and has no
     * default.
     */
    public record CreateRequest(
            String model,
            String prompt,
            JsonNode seconds,
            String size,
            @JsonProperty("input_reference") InputReference inputReference,
            @JsonProperty("webhook_url") String webhookUrl,        // Godance extension
            @JsonProperty("gallery_recipe") String galleryRecipe,  // Godance extension
            RecipeRef recipe                                        // Godance extension, in place of prompt
    ) { }

    /**
     * The image a video starts from. OpenAI's object is {@code {"file_id"}}
     * or {@code {"image_url"}}; {@code job_output} is a Godance extension
     * naming an image one of the caller's own jobs made. An
     * {@code image_url} is refused: Godance does not fetch input images.
     */
    public record InputReference(
            @JsonProperty("file_id") String fileId,
            @JsonProperty("image_url") String imageUrl,
            @JsonProperty("job_output") JobOutputRef jobOutput
    ) { }

    /**
     * OpenAI's video object, mapped from a job. Times are seconds since the
     * epoch. {@code cost_cents} and {@code job_url} are Godance extensions.
     *
     * <ul>
     *   <li>{@code status}: {@code queued}, {@code in_progress},
     *       {@code completed} or {@code failed}.</li>
     *   <li>{@code progress}: 0 until the video is completed, then 100. A
     *       job's progress is not known in between.</li>
     *   <li>{@code expires_at}: when the files are due to be deleted; null
     *       while the job is saved.</li>
     *   <li>{@code seconds}: the clip's length as a string, as OpenAI has it.</li>
     *   <li>{@code size}: the size the request named, else the model's
     *       default size where the catalog states one, else null.</li>
     *   <li>{@code error}: set when {@code failed}.</li>
     *   <li>{@code cost_cents}: the amount held while the video is queued or
     *       in progress, the amount charged once it is completed, and 0 when
     *       it failed (the hold is returned).</li>
     *   <li>{@code job_url}: the same job at {@code /v1/jobs/{id}}.</li>
     * </ul>
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record Video(
            UUID id,
            String object,
            String model,
            String status,
            int progress,
            @JsonProperty("created_at") long createdAt,
            @JsonProperty("completed_at") Long completedAt,
            @JsonProperty("expires_at") Long expiresAt,
            String prompt,
            String seconds,
            String size,
            VideoError error,
            @JsonProperty("remixed_from_video_id") String remixedFromVideoId,
            @JsonProperty("cost_cents") long costCents,
            @JsonProperty("job_url") String jobUrl
    ) {
        public static final String OBJECT = "video";

        /** This video as it was when its job was accepted: queued, whatever its worker has done since. */
        public Video asAccepted() {
            return new Video(id, object, model, "queued", 0, createdAt, null, expiresAt, prompt, seconds, size,
                    null, remixedFromVideoId, costCents, jobUrl);
        }
    }

    /** Why a video failed. */
    public record VideoError(String code, String message) { }

    /**
     * {@code GET /v1/videos}: one page, as OpenAI pages. {@code last_id} is
     * what the next request passes as {@code after}; {@code has_more} says
     * whether there is a next page.
     */
    @JsonInclude(JsonInclude.Include.ALWAYS)
    public record VideoPage(
            String object,
            List<Video> data,
            @JsonProperty("first_id") UUID firstId,
            @JsonProperty("last_id") UUID lastId,
            @JsonProperty("has_more") boolean hasMore
    ) {
        public static VideoPage of(List<Video> data, boolean hasMore) {
            return new VideoPage("list", data,
                    data.isEmpty() ? null : data.get(0).id(),
                    data.isEmpty() ? null : data.get(data.size() - 1).id(),
                    hasMore);
        }
    }

    /** {@code DELETE /v1/videos/{id}}: OpenAI's confirmation. */
    public record Deleted(UUID id, String object, boolean deleted) {
        public static Deleted of(UUID id) {
            return new Deleted(id, "video.deleted", true);
        }
    }
}
