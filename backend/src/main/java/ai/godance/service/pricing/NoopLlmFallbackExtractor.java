package ai.godance.service.pricing;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Default {@link LlmFallbackExtractor} — emits nothing. Production runs
 * fine with this until operators wire a real extractor against the
 * configured fallback-page allowlist. Registered as a {@code @Bean}
 * in {@link ai.godance.config.PricingConfig} with
 * {@code @ConditionalOnMissingBean} so an integration test or a future
 * production replacement can substitute its own implementation by
 * declaring a competing {@code LlmFallbackExtractor} bean.
 */
public class NoopLlmFallbackExtractor implements LlmFallbackExtractor {

    @Override
    public Mono<List<ObservedPrice>> extractAll() {
        return Mono.just(List.of());
    }
}
