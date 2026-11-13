package ai.godance.gateway;

import java.util.List;

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

    Mono<List<GeneratedVideo>> generate(VideoRequest request);
}
