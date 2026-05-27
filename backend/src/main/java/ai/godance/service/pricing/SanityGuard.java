package ai.godance.service.pricing;

import ai.godance.config.PricingProperties;

/**
 * Bounds-check on an {@link ObservedPrice} against the prior catalog
 * value. Any failure flips the proposal to {@link
 * ai.godance.domain.ProposalStatus#ANOMALY}: never a silent drop, never
 * a direct write.
 *
 * <p>Rules: floor ({@code min-microcents}), non-finite-as-MIN_VALUE
 * sentinel emitted by the parsers, and ceiling (multiplier of prior).
 * A new model with no prior value skips the multiplier check (no prior
 * to compare to) but still respects the floor.
 */
public class SanityGuard {

    private final long minMicrocents;
    private final double maxMultiplier;

    public SanityGuard(PricingProperties props) {
        this.minMicrocents = props.getMinMicrocents();
        this.maxMultiplier = props.getMaxMultiplier();
    }

    public Result check(ObservedPrice obs, Long priorMicrocents) {
        if (obs.microcents() == Long.MIN_VALUE) return Result.NON_FINITE;
        if (obs.microcents() <= 0)              return Result.NON_POSITIVE;
        if (obs.microcents() < minMicrocents)   return Result.BELOW_FLOOR;
        if (priorMicrocents == null || priorMicrocents <= 0) return Result.OK;
        double ratio = (double) obs.microcents() / (double) priorMicrocents;
        if (ratio > maxMultiplier) return Result.ABOVE_CEILING;
        return Result.OK;
    }

    public enum Result {
        OK,
        NON_FINITE,
        NON_POSITIVE,
        BELOW_FLOOR,
        ABOVE_CEILING;

        public boolean isAnomaly() { return this != OK; }
    }
}
