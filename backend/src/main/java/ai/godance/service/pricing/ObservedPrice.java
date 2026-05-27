package ai.godance.service.pricing;

import java.math.BigDecimal;
import java.time.Instant;

import ai.godance.domain.Modality;
import ai.godance.domain.PriceField;
import ai.godance.domain.Provider;

/**
 * One price observation pulled from a single upstream feed (OpenRouter,
 * LiteLLM, or LLM-fallback). Multiple observations of the same model
 * across different fields ({@code input}, {@code output}, {@code
 * per_image}, etc.) are emitted as separate records — the diff loop
 * handles each independently.
 *
 * <p>{@code microcents} is the canonical integer cost (after
 * USD→microcents conversion). {@code rawUsd} is kept for the audit row
 * so operators can spot conversion bugs.
 *
 * @param sourceModelId  the upstream id verbatim — e.g. {@code
 *                       anthropic/claude-opus-4-7} on OpenRouter
 * @param godanceModelName resolved Godance {@code model_name} via the
 *                       alias map, or — for unseen ids — the bare id
 *                       with the vendor prefix stripped
 * @param provider       resolved Provider enum value, or {@code null} if
 *                       inference failed (proposal still lands, marked
 *                       for operator review)
 * @param modality       inferred modality, or {@code null} if unknown
 * @param field          which price column this observation moves
 * @param microcents     the integer cost to be stored in {@code
 *                       model_catalog} on approval
 * @param rawUsd         the upstream USD value as a BigDecimal — no
 *                       {@code double} round-tripping
 * @param source         {@code openrouter} / {@code litellm} /
 *                       {@code llm_fallback}
 * @param sourceUrl      provider-page URL when meaningful (LLM fallback
 *                       always sets this; aggregators may leave it null)
 * @param observedAt     timestamp the observation was made (refresh-job
 *                       wall clock, not feed-claimed timestamp)
 */
public record ObservedPrice(
        String sourceModelId,
        String godanceModelName,
        Provider provider,
        Modality modality,
        PriceField field,
        long microcents,
        BigDecimal rawUsd,
        String source,
        String sourceUrl,
        Instant observedAt
) { }
