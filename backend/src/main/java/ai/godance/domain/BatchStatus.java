package ai.godance.domain;

/**
 * Where a recipe batch is (V36).
 *
 * <p>A batch starts QUEUED, is RUNNING once a row has started, and is DONE
 * when no row is pending or running. A refused pre-debit makes it PAUSED
 * until its owner resumes it. CANCELLED skips the rows that had not started;
 * the rows that were running finish.
 */
public enum BatchStatus {
    QUEUED,
    RUNNING,
    PAUSED,
    DONE,
    CANCELLED;

    /** Whether rows of a batch in this status may still be started. */
    public boolean startsRows() {
        return this == QUEUED || this == RUNNING;
    }

    /** Whether the batch still counts against its owner's limit on unfinished batches. */
    public boolean unfinished() {
        return this == QUEUED || this == RUNNING || this == PAUSED;
    }
}
