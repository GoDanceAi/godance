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
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Public {@code GET /v1/status} — feeds the marketing {@code /status} page.
 * One row per {@code (provider, model)}, latest 5-min window within the past
 * hour. Unauthenticated by design — see {@code SecurityConfig}.
 */
@RestController
@RequestMapping("/v1")
public class StatusController {

    private static final Duration LOOKBACK = Duration.ofHours(1);

    private final ProviderHealthRepository health;

    public StatusController(ProviderHealthRepository health) {
        this.health = health;
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
                        rows.add(ProviderStatus.from(h));
                    }
                    rows.sort(Comparator
                            .comparing(ProviderStatus::provider)
                            .thenComparing(ProviderStatus::model));
                    return new StatusResponse(Instant.now(), rows);
                })
                .subscribeOn(Schedulers.boundedElastic());
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
            Integer p50Ms,
            Integer p95Ms,
            Integer p99Ms,
            String state
    ) {
        public static ProviderStatus from(ProviderHealth h) {
            return new ProviderStatus(
                    h.getProvider(), h.getModel(),
                    h.getWindowStart(), h.getWindowEnd(),
                    h.getRequestCount(), h.getErrorCount(), h.getRateLimitCount(),
                    h.getP50Ms(), h.getP95Ms(), h.getP99Ms(),
                    deriveState(h));
        }

        // Coarse traffic-light derived from the window:
        //   degraded → rate-limits seen
        //   down     → all requests errored
        //   ok       → everything else
        // We don't gate on absolute latency yet; revisit when percentiles land.
        private static String deriveState(ProviderHealth h) {
            if (h.getRateLimitCount() > 0)                                  return "degraded";
            if (h.getRequestCount() > 0 && h.getErrorCount() >= h.getRequestCount()) return "down";
            return "ok";
        }
    }
}
