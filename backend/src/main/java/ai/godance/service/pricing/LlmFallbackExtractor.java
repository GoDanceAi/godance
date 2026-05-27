package ai.godance.service.pricing;

import java.util.List;

import reactor.core.publisher.Mono;

/**
 * Optional best-effort path for providers the structured aggregators
 * miss (Doubao, Qwen, Kimi, GLM, MiniMax, Seedream, Seedance, Kling,
 * Hailuo, Wan-on-Volcengine, ERNIE). Each implementation fetches an
 * allowlisted pricing page, asks a cheap catalog model for structured
 * JSON, and emits {@link ObservedPrice} records that always land in the
 * staging lane as proposals — never as direct catalog writes.
 *
 * <p>The default {@link NoopLlmFallbackExtractor} returns no
 * observations: the production system is fully functional with only
 * OpenRouter + LiteLLM. Operators opt into the LLM path by registering
 * a real implementation alongside fallback-page config.
 */
public interface LlmFallbackExtractor {

    Mono<List<ObservedPrice>> extractAll();
}
