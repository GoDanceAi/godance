package ai.godance.web.dto;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.GenerationJob;
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
     * OpenAI-compatible request shape. Two Godance extensions
     * ({@code async}, {@code webhook_url}) pure OpenAI clients ignore.
     */
    public record ImageGenerationRequest(
            @NotBlank String model,
            @NotBlank String prompt,
            String size,
            @Min(1) Integer n,
            String style,
            String quality,
            Boolean async,                                       // Godance extension
            @JsonProperty("webhook_url") String webhookUrl       // Godance extension
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
            Instant completedAt
    ) {
        public static JobView from(GenerationJob j) {
            return new JobView(
                    j.getId(), j.getStatus(), j.getModality(), j.getProvider(),
                    j.getModel(), j.getEstimatedCostCents(), j.getActualCostCents(),
                    j.getOutputUrl(), j.getErrorMessage(),
                    j.getCreatedAt(), j.getStartedAt(), j.getCompletedAt());
        }
    }
}
