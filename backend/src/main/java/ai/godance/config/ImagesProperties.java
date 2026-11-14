package ai.godance.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

import ai.godance.domain.ImageResponseMode;

/**
 * Image responses in OpenAI's shape (round 10 brief 04): an API key set to
 * {@code sync} has its image requests wait for the job.
 *
 * @param newKeyResponse    what a new key gets when its creation names
 *                          nothing: {@code async} (the default, today's
 *                          behaviour) or {@code sync}. Existing keys are
 *                          never changed by this
 * @param syncWait          the longest a request waits for its job before
 *                          it answers 202; at most {@link #MAX_SYNC_WAIT}
 * @param syncPollInterval  how often a waiting request reads its job
 * @param maxInlineBytes    the most image data one response carries as
 *                          {@code b64_json}; a larger result answers with
 *                          links instead
 */
@ConfigurationProperties(prefix = "godance.images")
public record ImagesProperties(
        @DefaultValue("async") ImageResponseMode newKeyResponse,
        @DefaultValue("90s") Duration syncWait,
        @DefaultValue("1s") Duration syncPollInterval,
        @DefaultValue("25MB") DataSize maxInlineBytes
) {
    /**
     * The proxy in front of the API closes a request after five minutes, so
     * a longer wait could never be answered.
     */
    public static final Duration MAX_SYNC_WAIT = Duration.ofSeconds(300);

    private static final String PREFIX = "godance.images.";

    public ImagesProperties {
        if (newKeyResponse == null) {
            throw new IllegalStateException(PREFIX + "new-key-response must be async or sync");
        }
        if (syncWait == null || syncWait.isNegative() || syncWait.isZero()) {
            throw new IllegalStateException(PREFIX + "sync-wait must be positive; got " + syncWait);
        }
        if (syncWait.compareTo(MAX_SYNC_WAIT) > 0) {
            throw new IllegalStateException(PREFIX + "sync-wait must be at most "
                    + MAX_SYNC_WAIT.toSeconds() + "s; got " + syncWait.toSeconds() + "s");
        }
        if (syncPollInterval == null || syncPollInterval.isNegative() || syncPollInterval.isZero()) {
            throw new IllegalStateException(PREFIX + "sync-poll-interval must be positive; got "
                    + syncPollInterval);
        }
        if (maxInlineBytes == null || maxInlineBytes.isNegative()) {
            throw new IllegalStateException(PREFIX + "max-inline-bytes must be >= 0; got " + maxInlineBytes);
        }
    }
}
