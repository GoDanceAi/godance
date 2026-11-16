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

    /**
     * Whether this adapter sends a caller's own key when the call carries one
     * ({@link CallerKey}). One that says no always sends the platform's key.
     */
    default boolean takesCallerKeys() {
        return false;
    }

    /**
     * Whether this adapter sends a request's input images to its provider.
     * False unless it does: a model's capabilities say what it takes, and an
     * adapter that sends no images takes none, whatever its catalog row says.
     */
    default boolean editsImages() {
        return false;
    }

    /** Generates, or edits when the request {@linkplain ImageRequest#isEdit() carries input images}. */
    Mono<List<GeneratedImage>> generate(ImageRequest request);
}
