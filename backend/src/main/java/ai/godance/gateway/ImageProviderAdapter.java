package ai.godance.gateway;

import java.util.List;

import ai.godance.domain.Provider;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import reactor.core.publisher.Mono;

/**
 * Contract for an upstream image-generation provider. Synchronous from
 * the caller's viewpoint — adapters that wrap async upstream APIs
 * (BFL Flux) internally poll until completion.
 */
public interface ImageProviderAdapter {

    Provider type();

    Mono<List<GeneratedImage>> generate(ImageRequest request);
}
