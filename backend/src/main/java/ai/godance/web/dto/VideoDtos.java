package ai.godance.web.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public final class VideoDtos {

    private VideoDtos() { }

    /**
     * OpenAI-compatible-ish video request. Mirrors {@code /v1/videos}
     * convention: {@code seconds} for duration, {@code size} for
     * resolution. Always async — see {@link AsyncJobAcknowledged} response.
     * {@code gallery_recipe} is the slug of the gallery recipe this request
     * is a run of; the job records it. A request carries {@code prompt} or
     * {@code recipe}, never both, as for images.
     *
     * <p>{@code first_frame}, {@code last_frame} and {@code reference_images}
     * are the images the clip starts from, ends on and follows. Each is
     * {@code {"file_id": …}} for an upload or {@code {"job_output": {"job_id":
     * …, "index": …}}} for an output of one of the caller's image jobs.
     */
    public record VideoGenerationRequest(
            @NotBlank String model,
            String prompt,
            String size,
            @Min(1) Integer seconds,
            @Min(1) Integer n,
            @JsonProperty("webhook_url") String webhookUrl,
            @JsonProperty("gallery_recipe") String galleryRecipe,
            RecipeRef recipe,
            @JsonProperty("first_frame") InputRef firstFrame,
            @JsonProperty("last_frame") InputRef lastFrame,
            @JsonProperty("reference_images") List<InputRef> referenceImages
    ) { }
}
