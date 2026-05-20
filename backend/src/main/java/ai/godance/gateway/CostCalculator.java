package ai.godance.gateway;

import org.springframework.stereotype.Component;

import ai.godance.config.BillingProperties;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Computes per-request cost in cents from token counts + markup.
 *
 * <p>Internal arithmetic is in <b>microcents</b> (1 microcent = 0.0001¢)
 * to preserve sub-cent precision; converted to cents only at the
 * ledger boundary. Two conversion modes:
 *
 * <ul>
 *   <li>{@link #estimateInputCents}: ceiling — pre-debit must not under-quote.</li>
 *   <li>{@link #actualCents}: HALF_UP — fair rounding at reconcile time.</li>
 * </ul>
 *
 * <p>Sub-cent requests (HALF_UP rounds to zero) are effectively free.
 * Documented as a v1 limitation in PROGRESS — the alternative is a
 * schema migration to store balances in microcents.
 */
@Component
public class CostCalculator {

    private static final long MICROCENTS_PER_CENT = 10_000L;

    private final BillingProperties billing;

    public CostCalculator(BillingProperties billing) {
        this.billing = billing;
    }

    /** Conservative pre-debit estimate: input tokens only (the brief's pattern). */
    public long estimateInputCents(ModelInfo model, int inputTokens) {
        long microcents = withMarkup(model, model.inputMicrocentsPer1k() * inputTokens / 1000);
        return ceilDiv(microcents, MICROCENTS_PER_CENT);
    }

    /** Reconciled actual cost — input + output tokens, HALF_UP cents rounding. */
    public long actualCents(ModelInfo model, int inputTokens, int outputTokens) {
        long microcents = withMarkup(model,
                model.inputMicrocentsPer1k()  * inputTokens  / 1000
              + model.outputMicrocentsPer1k() * outputTokens / 1000);
        return halfUpDiv(microcents, MICROCENTS_PER_CENT);
    }

    /** Image generation cost — per-image flat price × count + markup. */
    public long imageCostCents(ModelInfo model, int imageCount) {
        if (imageCount <= 0) return 0L;
        long microcents = withMarkup(model, model.perImageMicrocents() * imageCount);
        return halfUpDiv(microcents, MICROCENTS_PER_CENT);
    }

    /** Video generation cost — per-second flat price × duration × n-clips + markup. */
    public long videoCostCents(ModelInfo model, int durationSeconds, int clipCount) {
        if (durationSeconds <= 0 || clipCount <= 0) return 0L;
        long microcents = withMarkup(model,
                model.perSecondMicrocents() * durationSeconds * clipCount);
        return halfUpDiv(microcents, MICROCENTS_PER_CENT);
    }

    private long withMarkup(ModelInfo model, long microcents) {
        return Math.round(microcents * (1.0 + markupPercent(model) / 100.0));
    }

    private double markupPercent(ModelInfo model) {
        if (model.markupOverride() != null) {
            return model.markupOverride();
        }
        return switch (model.modality()) {
            case TEXT, EMBEDDING, AUDIO_STT, AUDIO_TTS -> billing.textMarkupPercent();
            case IMAGE -> billing.imageMarkupPercent();
            case VIDEO -> billing.videoMarkupPercent();
        };
    }

    private static long ceilDiv(long numerator, long denominator) {
        if (numerator <= 0) return 0;
        return (numerator + denominator - 1) / denominator;
    }

    private static long halfUpDiv(long numerator, long denominator) {
        if (numerator <= 0) return 0;
        return (numerator + denominator / 2) / denominator;
    }
}
