package ai.godance.domain;

/**
 * Outcome of a single upstream provider call, recorded on {@link UsageRecord}.
 * Drives {@code ProviderHealthAggregator} (error / rate-limit counts) and the
 * outage-aware router.
 *
 * <p>A {@code null} outcome on a record means "uninstrumented" (written before
 * V10, or by a path that does not capture outcome) and is treated as
 * {@link #OK} by the aggregator.
 */
public enum CallOutcome {
    /** Upstream returned a usable response. */
    OK,
    /** Upstream failed — non-2xx, timeout, transport error, or empty body. */
    ERROR,
    /** Upstream rejected the call with HTTP 429. Tracked apart from {@link #ERROR}. */
    RATE_LIMITED
}
