package ai.godance.gateway;

import java.util.List;
import java.util.Set;

import ai.godance.domain.Provider;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import reactor.core.publisher.Mono;

/**
 * Contract for an upstream video-generation provider. All video
 * adapters wrap async upstream APIs (Sora, Volcengine Ark Seedance,
 * Veo) — internal polling presents a single {@link Mono} that emits
 * once the bytes are ready or errors on timeout / failure.
 */
public interface VideoProviderAdapter {

    /** The clip length an adapter generates by default unless it says otherwise. */
    int STANDARD_CLIP_SECONDS = 5;

    Provider type();

    /**
     * The clip length this adapter generates for a request that names none.
     * The service prices the request on this value and sends it in the
     * request, so billing and generation cannot disagree; it must be the
     * value {@link #generate} falls back to.
     */
    default int defaultDurationSeconds() {
        return STANDARD_CLIP_SECONDS;
    }

    /**
     * What this adapter's provider takes as an input image, by its own
     * documentation, so that a request it would refuse is refused here,
     * before anything is charged. Which inputs a model takes at all (a first
     * frame, a last frame, how many reference images) is the catalog's to
     * say, in the model's capabilities; these are the limits on each image
     * and on how the inputs combine.
     *
     * @param contentTypes               the image types accepted
     * @param minAspect                  the narrowest shape accepted, as width ÷ height
     * @param maxAspect                  the widest shape accepted, as width ÷ height
     * @param maxBytes                   the largest image accepted, in bytes
     * @param framesWithReferences       whether a first or last frame may be sent together with reference images
     */
    record InputRules(Set<String> contentTypes, double minAspect, double maxAspect, long maxBytes,
                      boolean framesWithReferences) {

        /** An adapter that sends no images: nothing is accepted. */
        public static final InputRules NONE = new InputRules(Set.of(), 0, 0, 0, false);
    }

    /** {@link InputRules#NONE} unless the adapter sends images. */
    default InputRules inputRules() {
        return InputRules.NONE;
    }

    Mono<List<GeneratedVideo>> generate(VideoRequest request);
}
