package ai.godance.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;

public final class VideoDtos {

    private VideoDtos() { }

    /**
     * OpenAI-compatible-ish video request. Mirrors {@code /v1/videos}
     * convention: {@code seconds} for duration, {@code size} for
     * resolution. Always async — see {@link AsyncJobAcknowledged} response.
     */
    public record VideoGenerationRequest(
            @NotBlank String model,
            @NotBlank String prompt,
            String size,
            @Min(1) Integer seconds,
            @Min(1) Integer n,
            @JsonProperty("webhook_url") String webhookUrl
    ) { }
}
