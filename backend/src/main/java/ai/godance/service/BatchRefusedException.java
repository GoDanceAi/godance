package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused recipe batch request: problem+json whose {@code code} extension
 * member says which kind, so a client can branch without reading
 * {@code detail}.
 */
public final class BatchRefusedException extends ResponseStatusException {

    /** More rows than a batch may have. 400. */
    public static final String CODE_TOO_LARGE = "batch_too_large";
    /** A batch request that is not acceptable for a reason that is not one row's. 400. */
    public static final String CODE_INVALID = "invalid_batch";
    /** A row refused for a reason that has no code of its own. 400, with {@code row}. */
    public static final String CODE_INVALID_ROW = "invalid_batch_row";
    /** No such batch for this caller. Another user's answers the same. 404. */
    public static final String CODE_NOT_FOUND = "batch_not_found";
    /** Resume asked of a batch that is not paused. 409. */
    public static final String CODE_NOT_RESUMABLE = "batch_not_resumable";
    /** The account already has as many unfinished batches as it may. 409. */
    public static final String CODE_LIMIT_REACHED = "batch_limit_reached";
    /** The batch's outputs total more than {@code results.zip} may hold. 413. */
    public static final String CODE_RESULTS_TOO_LARGE = "results_too_large";

    private BatchRefusedException(HttpStatus status, String detail, String code) {
        super(status, detail);
        getBody().setProperty("code", code);
    }

    public static BatchRefusedException tooLarge(int rows, int max) {
        return new BatchRefusedException(HttpStatus.BAD_REQUEST,
                "a batch has at most " + max + " rows; this one has " + rows, CODE_TOO_LARGE);
    }

    public static BatchRefusedException invalid(String detail) {
        return new BatchRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_INVALID);
    }

    public static BatchRefusedException notFound() {
        return new BatchRefusedException(HttpStatus.NOT_FOUND, "batch not found", CODE_NOT_FOUND);
    }

    public static BatchRefusedException notResumable(String status) {
        return new BatchRefusedException(HttpStatus.CONFLICT,
                "only a paused batch can be resumed; this one is " + status, CODE_NOT_RESUMABLE);
    }

    public static BatchRefusedException limitReached(int max) {
        return new BatchRefusedException(HttpStatus.CONFLICT,
                "You have " + max + (max == 1 ? " batch" : " batches") + " that are not finished, the most an "
                        + "account can have at once. Wait for one to finish, or cancel one.", CODE_LIMIT_REACHED);
    }

    public static BatchRefusedException resultsTooLarge(long bytes, long maxBytes, String csvPath) {
        return new BatchRefusedException(HttpStatus.CONTENT_TOO_LARGE,
                "this batch's outputs total " + megabytes(bytes) + " MB, more than the " + megabytes(maxBytes)
                        + " MB a zip may hold. Download " + csvPath + " for a link to each output.",
                CODE_RESULTS_TOO_LARGE);
    }

    /**
     * The refusal of one row. It answers 400 whatever the single-run request
     * would have answered, keeps that refusal's {@code code} and other
     * members, names the row in {@code row} (0-based), and starts
     * {@code detail} with it.
     */
    public static ResponseStatusException row(int index, ResponseStatusException cause) {
        ResponseStatusException e = new ResponseStatusException(HttpStatus.BAD_REQUEST,
                "row " + index + ": " + (cause.getReason() == null ? "this row cannot be run" : cause.getReason()));
        java.util.Map<String, Object> members = cause.getBody().getProperties();
        if (members != null) members.forEach((name, value) -> e.getBody().setProperty(name, value));
        if (members == null || !members.containsKey("code")) e.getBody().setProperty("code", CODE_INVALID_ROW);
        e.getBody().setProperty("row", index);
        return e;
    }

    private static String megabytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.0f", Math.ceil(bytes / (1024.0 * 1024.0)));
    }
}
