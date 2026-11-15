package ai.godance.gateway;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Provider-agnostic video generation shapes. Modeled around the common
 * minimum: prompt + duration + resolution + clip count, and the images a
 * clip may start from, end on or follow. Provider-specific extensions
 * (camera moves, seed, guidance scale) can land in a future {@code extras}
 * map field if/when needed.
 */
public final class VideoDtos {

    private VideoDtos() { }

    /**
     * An image a video is made from: its bytes and its type. An adapter
     * sends it in whatever form its provider takes; the request never
     * carries an address.
     */
    public record VideoInput(byte[] bytes, String contentType) { }

    /**
     * {@code firstFrame} and {@code lastFrame} are null, and
     * {@code referenceImages} empty, for a request that starts from text
     * alone. Reference images keep the order the caller gave them: a
     * provider that refers to them by position counts from the first.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record VideoRequest(
            String model,
            String prompt,
            String size,            // e.g. "1280x720"
            Integer durationSeconds,
            Integer n,              // clip count; null → 1
            VideoInput firstFrame,
            VideoInput lastFrame,
            List<VideoInput> referenceImages
    ) {
        public VideoRequest {
            referenceImages = referenceImages == null ? List.of() : List.copyOf(referenceImages);
        }

        /** A request from text alone. */
        public VideoRequest(String model, String prompt, String size, Integer durationSeconds, Integer n) {
            this(model, prompt, size, durationSeconds, n, null, null, List.of());
        }

        /** Whether the request carries any image. */
        public boolean hasInputs() {
            return firstFrame != null || lastFrame != null || !referenceImages.isEmpty();
        }

        /** This request with another prompt and length, and the same images. */
        public VideoRequest with(String prompt, Integer durationSeconds) {
            return new VideoRequest(model, prompt, size, durationSeconds, n, firstFrame, lastFrame, referenceImages);
        }

        /** This request with these images. */
        public VideoRequest withInputs(VideoInput firstFrame, VideoInput lastFrame, List<VideoInput> referenceImages) {
            return new VideoRequest(model, prompt, size, durationSeconds, n, firstFrame, lastFrame, referenceImages);
        }
    }

    /** What an adapter returns — raw bytes that the Service uploads to storage. */
    public record GeneratedVideo(byte[] bytes, String mimeType) { }
}
