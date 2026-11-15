package ai.godance.gateway;

import java.util.List;

/**
 * Provider-agnostic image-generation shapes. Modeled after OpenAI's
 * {@code /v1/images/generations} and {@code /v1/images/edits} so future
 * adapters (Stability, Kling for images, etc.) translate in/out at the edge.
 */
public final class ImageDtos {

    private ImageDtos() { }

    /**
     * An image an edit works from, or its mask: the bytes, their type, and a
     * short-lived signed address of the same stored file for a provider that
     * takes inputs by URL only. The address is never a public one.
     */
    public record ImageInput(byte[] bytes, String contentType, String url) { }

    /**
     * {@code images} is empty, and {@code mask} null, for a request that
     * generates from text alone. With images it is an edit: the images keep
     * the order the caller gave them, and the mask applies to the first.
     */
    public record ImageRequest(
            String model,
            String prompt,
            String size,            // e.g. "1024x1024"; provider validates
            Integer n,              // image count; null → 1
            String style,           // optional (DALL-E: "vivid"/"natural")
            String quality,         // optional (DALL-E: "standard"/"hd")
            List<ImageInput> images,
            ImageInput mask
    ) {
        public ImageRequest {
            images = images == null ? List.of() : List.copyOf(images);
        }

        /** A request from text alone. */
        public ImageRequest(String model, String prompt, String size, Integer n, String style, String quality) {
            this(model, prompt, size, n, style, quality, List.of(), null);
        }

        /** Whether this request works from input images. */
        public boolean isEdit() {
            return !images.isEmpty();
        }

        /** This request with another prompt, and the same images. */
        public ImageRequest withPrompt(String prompt) {
            return new ImageRequest(model, prompt, size, n, style, quality, images, mask);
        }

        /** This request with these input images and this mask. */
        public ImageRequest withInputs(List<ImageInput> images, ImageInput mask) {
            return new ImageRequest(model, prompt, size, n, style, quality, images, mask);
        }
    }

    /** What an adapter returns — raw bytes that the Service uploads to storage. */
    public record GeneratedImage(byte[] bytes, String mimeType, String revisedPrompt) { }
}
