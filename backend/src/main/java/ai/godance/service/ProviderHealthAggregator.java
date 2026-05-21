package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.CallOutcome;
import ai.godance.domain.Provider;
import ai.godance.domain.ProviderHealth;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.ProviderHealthRepository;
import ai.godance.repository.UsageRecordRepository;

/**
 * Folds {@link UsageRecord} rows into per-{@code (provider, model, 5-min window)}
 * {@link ProviderHealth} rows. Drives {@code GET /v1/status} and the
 * outage-aware router via {@link ModelHealthService}.
 *
 * <p>Each window row carries {@code request_count}, {@code error_count} and
 * {@code rate_limit_count} (from {@code UsageRecord.outcome}) plus p50/p95/p99
 * latency percentiles (from {@code UsageRecord.latency_ms}, V10). A record with
 * a {@code null} outcome — written before instrumentation — counts as a
 * successful request; a record with a {@code null} latency is omitted from the
 * percentile computation.
 */
@Service
public class ProviderHealthAggregator {

    private static final Logger log = LoggerFactory.getLogger(ProviderHealthAggregator.class);

    /** Window granularity. 5 min matches typical "status page" cadence. */
    static final Duration WINDOW = Duration.ofMinutes(5);

    private final UsageRecordRepository usage;
    private final ProviderHealthRepository health;

    public ProviderHealthAggregator(UsageRecordRepository usage, ProviderHealthRepository health) {
        this.usage = usage;
        this.health = health;
    }

    /**
     * Run once a minute. Re-folds the *just-closed* 5-min window each tick;
     * the UNIQUE (provider, model, window_start, window_end) constraint makes
     * re-aggregation idempotent — we look up the existing row and overwrite
     * counters instead of inserting a duplicate.
     *
     * <p>Why 1-min cadence over a 5-min window: late-arriving UsageRecords
     * (the writer commits seconds after the request finishes) get picked up
     * on the next sweep without waiting a full window.
     */
    @Scheduled(
            fixedDelayString    = "${godance.scheduling.providers-health-fixed-delay:PT1M}",
            initialDelayString  = "${godance.scheduling.providers-health-initial-delay:PT30S}")
    public void aggregate() {
        Instant now = Instant.now().truncatedTo(ChronoUnit.MINUTES);
        Instant windowEnd   = floorTo(now, WINDOW);
        Instant windowStart = windowEnd.minus(WINDOW);
        fold(windowStart, windowEnd);
    }

    /** Public so tests and manual backfills can replay an arbitrary window. */
    @Transactional
    public void fold(Instant windowStart, Instant windowEnd) {
        List<UsageRecord> records = usage.findByRecordedAtGreaterThanEqual(windowStart).stream()
                .filter(r -> r.getRecordedAt().isBefore(windowEnd))
                .toList();
        if (records.isEmpty()) return;

        Map<Key, List<UsageRecord>> byKey = records.stream().collect(Collectors.groupingBy(
                r -> new Key(r.getProvider(), r.getModelName())));

        for (Map.Entry<Key, List<UsageRecord>> e : byKey.entrySet()) {
            Key k = e.getKey();
            List<UsageRecord> group = e.getValue();
            ProviderHealth row = health.findByProviderAndModelAndWindowStartAndWindowEnd(
                            k.provider(), k.model(), windowStart, windowEnd)
                    .orElseGet(() -> newRow(k, windowStart, windowEnd));

            row.setRequestCount(group.size());
            row.setErrorCount(countOutcome(group, CallOutcome.ERROR));
            row.setRateLimitCount(countOutcome(group, CallOutcome.RATE_LIMITED));

            int[] latencies = group.stream()
                    .map(UsageRecord::getLatencyMs)
                    .filter(Objects::nonNull)
                    .mapToInt(Integer::intValue)
                    .sorted()
                    .toArray();
            row.setP50Ms(percentile(latencies, 50));
            row.setP95Ms(percentile(latencies, 95));
            row.setP99Ms(percentile(latencies, 99));

            health.save(row);
        }
        log.debug("provider-health: folded {} records into {} rows for window {} .. {}",
                records.size(), byKey.size(), windowStart, windowEnd);
    }

    private static long countOutcome(List<UsageRecord> group, CallOutcome outcome) {
        return group.stream().filter(r -> r.getOutcome() == outcome).count();
    }

    /**
     * Nearest-rank percentile over an ascending-sorted array; {@code null}
     * when no latencies were captured for the window.
     */
    static Integer percentile(int[] sortedAsc, int p) {
        if (sortedAsc.length == 0) return null;
        int rank = (int) Math.ceil(p / 100.0 * sortedAsc.length);
        int idx = Math.min(sortedAsc.length - 1, Math.max(0, rank - 1));
        return sortedAsc[idx];
    }

    private static ProviderHealth newRow(Key k, Instant start, Instant end) {
        ProviderHealth h = new ProviderHealth();
        h.setProvider(k.provider());
        h.setModel(k.model());
        h.setWindowStart(start);
        h.setWindowEnd(end);
        h.setRequestCount(0);
        h.setErrorCount(0);
        h.setRateLimitCount(0);
        return h;
    }

    /** Floor {@code t} to the previous boundary at multiples of {@code step}. */
    private static Instant floorTo(Instant t, Duration step) {
        long stepMs = step.toMillis();
        long ms = t.toEpochMilli();
        return Instant.ofEpochMilli(ms - (ms % stepMs));
    }

    private record Key(Provider provider, String model) { }
}
