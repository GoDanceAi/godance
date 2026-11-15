package ai.godance.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Limits on recipe batches ({@link ai.godance.service.RecipeBatchService}).
 * A batch costs nothing itself; these bound how much one account can queue
 * and how hard a batch may drive the providers.
 *
 * @param maxRows                  the most rows one batch may have
 * @param defaultConcurrency       rows a batch runs at a time when the request names no number
 * @param maxConcurrency           the most rows a batch may run at a time
 * @param maxUnfinishedPerAccount  the most batches one account may have that are not finished
 *                                 (queued, running or paused)
 * @param resultsZipMax            the most a batch's outputs may total for {@code results.zip};
 *                                 past it the route answers 413 and points to the CSV
 */
@ConfigurationProperties(prefix = "godance.batches")
public record BatchesProperties(
        @DefaultValue("500") int maxRows,
        @DefaultValue("2") int defaultConcurrency,
        @DefaultValue("4") int maxConcurrency,
        @DefaultValue("2") int maxUnfinishedPerAccount,
        @DefaultValue("500MB") DataSize resultsZipMax
) {
    public BatchesProperties {
        if (maxRows < 1) {
            throw new IllegalStateException("godance.batches.max-rows must be >= 1; got " + maxRows);
        }
        if (maxConcurrency < 1) {
            throw new IllegalStateException("godance.batches.max-concurrency must be >= 1; got " + maxConcurrency);
        }
        if (defaultConcurrency < 1 || defaultConcurrency > maxConcurrency) {
            throw new IllegalStateException("godance.batches.default-concurrency must be between 1 and "
                    + "max-concurrency (" + maxConcurrency + "); got " + defaultConcurrency);
        }
        if (maxUnfinishedPerAccount < 0) {
            throw new IllegalStateException("godance.batches.max-unfinished-per-account must be >= 0; got "
                    + maxUnfinishedPerAccount);
        }
        if (resultsZipMax == null || resultsZipMax.isNegative()) {
            throw new IllegalStateException("godance.batches.results-zip-max must be 0 or more");
        }
    }
}
