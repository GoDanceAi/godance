package ai.godance.routing;

import java.time.Duration;
import java.time.Instant;
import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Chooses where a failed batch row goes next (round 14's addition to round
 * 4's design), for batches created with {@code "fallbacks": true}: the first
 * alternative, in the owner's policy, whose triggers cover the failure.
 *
 * <ul>
 *   <li>{@code provider_error} and {@code timeout} cover those failures,
 *       as {@link JobFailure} reads them;</li>
 *   <li>{@code health_below} covers either of them while the primary's
 *       recent success rate is under the alternative's threshold;</li>
 *   <li>any other failure, a content refusal included, has no alternative.</li>
 * </ul>
 *
 * <p>Health is the share of the primary's jobs created in the last
 * {@link #WINDOW} that finished DONE, among those that finished DONE or
 * FAILED, once there are at least {@link #MIN_FINISHED}; with fewer it is
 * unknown and {@code health_below} does not apply. Media jobs write no usage
 * record when they fail, so the provider-health windows that chat uses would
 * always look healthy for them.
 */
@Component
public class MediaFallbacks {

    private static final Logger log = LoggerFactory.getLogger(MediaFallbacks.class);

    static final Duration WINDOW = Duration.ofMinutes(30);
    static final int MIN_FINISHED = 5;

    private final UserRoutingPolicyService policies;
    private final JdbcTemplate jdbc;

    public MediaFallbacks(UserRoutingPolicyService policies, JdbcTemplate jdbc) {
        this.policies = policies;
        this.jdbc = jdbc;
    }

    /**
     * The model a row of {@code userId}'s batch should be retried on, after
     * its job on {@code primary} failed with {@code errorMessage}; empty when
     * nothing covers the failure.
     */
    public Optional<String> alternativeFor(UUID userId, String primary, String errorMessage) {
        JobFailure failure = JobFailure.of(errorMessage);
        if (failure == JobFailure.OTHER) return Optional.empty();
        Optional<MediaFallback> fallback = policies.fallbackFor(userId, primary);
        if (fallback.isEmpty()) return Optional.empty();

        Double health = null;
        boolean healthRead = false;
        for (MediaFallback.Alternative a : fallback.get().alternatives()) {
            if (failure == JobFailure.PROVIDER_ERROR && a.on().contains(MediaFallback.Trigger.PROVIDER_ERROR)) {
                return Optional.of(a.model());
            }
            if (failure == JobFailure.TIMEOUT && a.on().contains(MediaFallback.Trigger.TIMEOUT)) {
                return Optional.of(a.model());
            }
            if (a.on().contains(MediaFallback.Trigger.HEALTH_BELOW) && a.healthBelow() != null) {
                if (!healthRead) {
                    health = successRate(primary);
                    healthRead = true;
                }
                if (health != null && health < a.healthBelow()) return Optional.of(a.model());
            }
        }
        return Optional.empty();
    }

    /** The primary's recent success rate (0-1), or null with too few finished jobs to say. */
    Double successRate(String model) {
        Timestamp since = Timestamp.from(Instant.now().minus(WINDOW));
        return jdbc.query("""
                SELECT COUNT(*) FILTER (WHERE status = 'DONE')   AS done,
                       COUNT(*) FILTER (WHERE status = 'FAILED') AS failed
                  FROM generation_jobs
                 WHERE status IN ('DONE', 'FAILED') AND created_at >= ? AND model = ?
                """, rs -> {
            if (!rs.next()) return null;
            long done = rs.getLong("done");
            long failed = rs.getLong("failed");
            long finished = done + failed;
            if (finished < MIN_FINISHED) return null;
            double rate = (double) done / finished;
            log.debug("Health of {}: {} of {} recent jobs DONE", model, done, finished);
            return rate;
        }, since, model);
    }
}
