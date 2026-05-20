package ai.godance.gateway;

import java.util.List;

/**
 * Provider-agnostic image-generation shapes. Modeled after OpenAI's
 * {@code /v1/images/generations} so future adapters (Stability, Kling
 * for images, etc.) translate in/out at the edge.
 */
public final class ImageDtos {

    private ImageDtos() { }

    public record ImageRequest(
            String model,
            String prompt,
            String size,            // e.g. "1024x1024"; provider validates
            Integer n,              // image count; null → 1
            String style,           // optional (DALL-E: "vivid"/"natural")
            String quality          // optional (DALL-E: "standard"/"hd")
    ) { }

    /** What an adapter returns — raw bytes that the Service uploads to storage. */
    public record GeneratedImage(byte[] bytes, String mimeType, String revisedPrompt) { }
}
