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

    Provider type();

    Mono<List<GeneratedVideo>> generate(VideoRequest request);
}
