package ai.godance.gateway;

import ai.godance.domain.Provider;
import ai.godance.gateway.ChatDtos.ChatChunk;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Contract for an upstream LLM provider. Implementations live under
 * {@link ai.godance.gateway.providers} and are discovered by the
 * {@link ProviderGateway} via {@link #type()}.
 *
 * <p>Named "adapter" rather than "Provider" to avoid shadowing
 * {@link ai.godance.domain.Provider} (the enum).
 */
public interface ProviderAdapter {

    Provider type();

    Mono<ChatResponse> chat(ChatRequest request);

    /**
     * Streamed completion. The terminal chunk carries usage; intermediate
     * chunks carry incremental content via {@code choices[i].delta.content}.
     * Default implementation errors with 501 so providers that don't
     * support SSE fail loudly rather than silently degrading.
     */
    default Flux<ChatChunk> chatStream(ChatRequest request) {
        return Flux.error(new UnsupportedOperationException(
                type() + " does not support streaming"));
    }
}
