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
 * <p>Every request with a non-zero cost bills at least
 * {@link #MINIMUM_CHARGE_CENTS} (1¢). Without this floor a request whose
 * marked-up cost is under half a cent rounded to zero and was free — at seed
 * prices that was most short calls on the cheap models (spec risk R-06).
 * The floor over-bills tiny requests by up to a cent; the fairer fix is a
 * microcent ledger, tracked separately. Image and video already applied the
 * same floor in their services.
 *
 * <p>Markup is applied in integer arithmetic (basis points), so no
 * {@code double} touches the money path; the markup percent itself is a
 * {@code double} only because that is how it is configured and published.
 */
@Component
public class CostCalculator {

    private static final long MICROCENTS_PER_CENT = 10_000L;
    private static final long BASIS_POINTS = 10_000L;

    /** Smallest charge for a request that cost anything at all. */
    public static final long MINIMUM_CHARGE_CENTS = 1L;

    private final BillingProperties billing;

    public CostCalculator(BillingProperties billing) {
        this.billing = billing;
    }

    /** Conservative pre-debit estimate: input tokens only (the brief's pattern). */
    public long estimateInputCents(ModelInfo model, int inputTokens) {
        long microcents = withMarkup(model, model.inputMicrocentsPer1k() * inputTokens / 1000);
        return ceilDiv(microcents, MICROCENTS_PER_CENT);
    }

    /**
     * Reconciled actual cost — input + output tokens, HALF_UP cents rounding,
     * never below {@link #MINIMUM_CHARGE_CENTS} when the request cost anything.
     */
    public long actualCents(ModelInfo model, int inputTokens, int outputTokens) {
        long microcents = withMarkup(model,
                model.inputMicrocentsPer1k()  * inputTokens  / 1000
              + model.outputMicrocentsPer1k() * outputTokens / 1000);
        return withMinimumCharge(microcents, halfUpDiv(microcents, MICROCENTS_PER_CENT));
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

    /**
     * Applies the markup in integer arithmetic: the percent becomes basis
     * points (12.5% → 1250 bp) once, then {@code microcents × (10000 + bp) / 10000}
     * with HALF_UP rounding of the division. Equivalent to the former
     * {@code Math.round(microcents * (1 + pct/100))} for every configured value,
     * without a floating-point multiply on the amount.
     */
    private long withMarkup(ModelInfo model, long microcents) {
        if (microcents <= 0) return 0;
        long basisPoints = Math.round(markupPercent(model) * 100.0);
        return halfUpDiv(microcents * (BASIS_POINTS + basisPoints), BASIS_POINTS);
    }

    /** A request that cost anything bills at least one cent. */
    private static long withMinimumCharge(long microcents, long cents) {
        return microcents > 0 ? Math.max(MINIMUM_CHARGE_CENTS, cents) : 0;
    }

    /**
     * Markup percent billed on top of the list price: the model's override
     * when set, else its modality's default. Published on {@code GET /v1/models}
     * so displayed prices match what is charged.
     */
    public double markupPercent(ModelInfo model) {
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
