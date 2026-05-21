package ai.godance.web;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.domain.Provider;
import ai.godance.domain.ProviderHealth;
import ai.godance.repository.ProviderHealthRepository;
import ai.godance.service.ModelHealthService;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Public {@code GET /v1/status} — feeds the marketing {@code /status} page.
 * One row per {@code (provider, model)}, latest 5-min window within the past
 * hour. Unauthenticated by design — see {@code SecurityConfig}.
 *
 * <p>Each row carries latency percentiles, a failure rate, and a coarse
 * {@code state}. The {@code degraded} state is the same signal the
 * outage-aware router uses to de-prefer a model ({@link ModelHealthService}),
 * so the status page and routing behaviour never disagree.
 */
@RestController
@RequestMapping("/v1")
public class StatusController {

    private static final Duration LOOKBACK = Duration.ofHours(1);

    private final ProviderHealthRepository health;
    private final ModelHealthService modelHealth;

    public StatusController(ProviderHealthRepository health, ModelHealthService modelHealth) {
        this.health = health;
        this.modelHealth = modelHealth;
    }

    @GetMapping("/status")
    public Mono<StatusResponse> status() {
        return Mono.fromCallable(() -> {
                    Instant since = Instant.now().minus(LOOKBACK);
                    List<ProviderHealth> recent = health
                            .findByWindowEndGreaterThanEqualOrderByWindowEndDesc(since);
                    // Keep only the newest window per (provider, model).
                    Map<String, ProviderHealth> latest = new HashMap<>();
                    for (ProviderHealth h : recent) {
                        latest.putIfAbsent(h.getProvider().name() + "|" + h.getModel(), h);
                    }
                    List<ProviderStatus> rows = new ArrayList<>();
                    for (ProviderHealth h : latest.values()) {
                        rows.add(toStatus(h));
                    }
                    rows.sort(Comparator
                            .comparing(ProviderStatus::provider)
                            .thenComparing(ProviderStatus::model));
                    return new StatusResponse(Instant.now(), rows);
                })
                .subscribeOn(Schedulers.boundedElastic());
    }

    private ProviderStatus toStatus(ProviderHealth h) {
        long failures = h.getErrorCount() + h.getRateLimitCount();
        double errorRate = h.getRequestCount() > 0
                ? (double) failures / h.getRequestCount()
                : 0.0;
        return new ProviderStatus(
                h.getProvider(), h.getModel(),
                h.getWindowStart(), h.getWindowEnd(),
                h.getRequestCount(), h.getErrorCount(), h.getRateLimitCount(),
                errorRate,
                h.getP50Ms(), h.getP95Ms(), h.getP99Ms(),
                deriveState(h));
    }

    // Coarse traffic-light derived from the latest window:
    //   down     → every request failed
    //   degraded → failure rate over the router's de-prefer threshold
    //   ok       → everything else
    // We don't gate on absolute latency yet; revisit once we have a baseline.
    private String deriveState(ProviderHealth h) {
        long failures = h.getErrorCount() + h.getRateLimitCount();
        if (h.getRequestCount() > 0 && failures >= h.getRequestCount()) return "down";
        if (modelHealth.isDegraded(h))                                  return "degraded";
        return "ok";
    }

    public record StatusResponse(Instant generatedAt, List<ProviderStatus> providers) { }

    public record ProviderStatus(
            Provider provider,
            String model,
            Instant windowStart,
            Instant windowEnd,
            long requestCount,
            long errorCount,
            long rateLimitCount,
            double errorRate,
            Integer p50Ms,
            Integer p95Ms,
            Integer p99Ms,
            String state
    ) { }
}
