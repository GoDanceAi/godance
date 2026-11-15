package ai.godance.web.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.web.dto.InputDtos.InputRef;
import ai.godance.web.dto.RecipeDtos.RecipeRef;

public final class ImageEditDtos {

    private ImageEditDtos() { }

    /**
     * {@code POST /v1/images/edits} as JSON: the fields of a generation
     * request, and the inputs the edit works from. Each of {@code images}
     * and {@code mask} is {@code {"file_id": …}} for an upload or
     * {@code {"job_output": {"job_id": …, "index": …}}} for an output of one
     * of the caller's image jobs. The mask says where the first image may
     * change.
     *
     * <p>Nothing here is validated by annotation: while inputs are switched
     * off the route answers {@code inputs_disabled} whatever the body holds,
     * so every check is made in order by the controller.
     */
    public record ImageEditRequest(
            String model,
            String prompt,
            List<InputRef> images,
            InputRef mask,
            Integer n,
            String size,
            String quality,
            @JsonProperty("response_format") String responseFormat,
            Boolean async,                                       // Godance extension
            @JsonProperty("webhook_url") String webhookUrl,      // Godance extension
            @JsonProperty("gallery_recipe") String galleryRecipe, // Godance extension
            RecipeRef recipe                                      // Godance extension, in place of prompt
    ) { }
}
