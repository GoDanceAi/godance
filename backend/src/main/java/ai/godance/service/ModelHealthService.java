package ai.godance.service;

import java.time.Duration;
import java.time.Instant;

import org.springframework.stereotype.Service;

import ai.godance.domain.Provider;
import ai.godance.domain.ProviderHealth;
import ai.godance.repository.ProviderHealthRepository;

/**
 * Outage-aware routing signal. A {@code (provider, model)} is "degraded" when
 * its failure rate — errors plus rate-limits — over the most recent health
 * window exceeds {@value #FAILURE_RATE_THRESHOLD}.
 *
 * <p>The router ({@code ChatCompletionService}) de-prefers degraded models so
 * the configured fallback chain is tried first; {@code GET /v1/status} reports
 * the same models as {@code degraded}. Both read {@link ProviderHealth} rows
 * folded by {@link ProviderHealthAggregator}, so router and status page share
 * one source of truth.
 *
 * <p>A model is de-preferred, never dropped — see the router for why the
 * degraded model still stays in the attempt list as a last resort.
 */
@Service
public class ModelHealthService {

    /** Brief 02: de-prefer a model once its failure rate climbs above 10%. */
    static final double FAILURE_RATE_THRESHOLD = 0.10;

    /**
     * Below this many requests a window carries too little signal to act on —
     * a single failed call should not de-prefer a model.
     */
    static final int MIN_SAMPLE = 20;

    /** A health window older than this no longer reflects current conditions. */
    static final Duration STALE_AFTER = Duration.ofMinutes(15);

    private final ProviderHealthRepository health;

    public ModelHealthService(ProviderHealthRepository health) {
        this.health = health;
    }

    /**
     * Whether a single health window is degraded: enough traffic to judge,
     * and a failure rate (errors + rate-limits) above the threshold. Pure
     * function of the row — {@code GET /v1/status} calls this on rows it has
     * already loaded.
     */
    public boolean isDegraded(ProviderHealth h) {
        if (h == null || h.getRequestCount() < MIN_SAMPLE) {
            return false;
        }
        long failures = h.getErrorCount() + h.getRateLimitCount();
        return failures > h.getRequestCount() * FAILURE_RATE_THRESHOLD;
    }

    /**
     * Whether a model's latest health window is both recent and degraded.
     * Used by the router; a model with no recent window is treated as healthy.
     */
    public boolean isModelDegraded(Provider provider, String model) {
        return health.findFirstByProviderAndModelOrderByWindowEndDesc(provider, model)
                .filter(h -> h.getWindowEnd().isAfter(Instant.now().minus(STALE_AFTER)))
                .map(this::isDegraded)
                .orElse(false);
    }
}
