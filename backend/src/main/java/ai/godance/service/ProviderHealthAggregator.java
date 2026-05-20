package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.Provider;
import ai.godance.domain.ProviderHealth;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.ProviderHealthRepository;
import ai.godance.repository.UsageRecordRepository;

/**
 * Folds {@link UsageRecord} rows into per-{@code (provider, model, 5-min window)}
 * {@link ProviderHealth} rows. Drives {@code GET /v1/status}.
 *
 * <p>What's tracked today: {@code request_count}. Errors and latency are not
 * yet captured on {@code UsageRecord}, so {@code error_count} stays 0 and the
 * latency percentiles stay {@code null}. Both columns already exist in the
 * schema — when the gateways emit timing + outcome we wire it through here
 * without a migration.
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

        Map<Key, Long> counts = records.stream().collect(Collectors.groupingBy(
                r -> new Key(r.getProvider(), r.getModelName()), Collectors.counting()));

        for (Map.Entry<Key, Long> e : counts.entrySet()) {
            Key k = e.getKey();
            ProviderHealth row = health.findByProviderAndModelAndWindowStartAndWindowEnd(
                            k.provider(), k.model(), windowStart, windowEnd)
                    .orElseGet(() -> newRow(k, windowStart, windowEnd));
            row.setRequestCount(e.getValue());
            health.save(row);
        }
        log.debug("provider-health: folded {} records into {} rows for window {} .. {}",
                records.size(), counts.size(), windowStart, windowEnd);
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
