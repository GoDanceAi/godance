package ai.godance.domain;

/**
 * Where one row of a recipe batch is (V36). PENDING until the runner claims
 * it; RUNNING while its job is open; then DONE or FAILED as its job ended.
 * SKIPPED is a row that had not started when the batch was cancelled.
 */
public enum BatchRowStatus {
    PENDING,
    RUNNING,
    DONE,
    FAILED,
    SKIPPED
}
