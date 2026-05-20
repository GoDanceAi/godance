package ai.godance.gateway;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Provider-agnostic video generation shapes. Modeled around the common
 * minimum: prompt + duration + resolution + clip count. Provider-specific
 * extensions (camera moves, seed, guidance scale) can land in a future
 * {@code extras} map field if/when needed.
 */
public final class VideoDtos {

    private VideoDtos() { }

    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VideoRequest(
            String model,
            String prompt,
            String size,            // e.g. "1280x720"
            Integer durationSeconds,
            Integer n               // clip count; null → 1
    ) { }

    /** What an adapter returns — raw bytes that the Service uploads to storage. */
    public record GeneratedVideo(byte[] bytes, String mimeType) { }
}
