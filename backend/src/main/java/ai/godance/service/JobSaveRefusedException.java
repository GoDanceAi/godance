package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused save of a generation: a 409 problem+json whose {@code code}
 * extension member says which kind, so a client can branch without reading
 * {@code detail}.
 */
public final class JobSaveRefusedException extends ResponseStatusException {

    /** The job is not DONE, or its files have already been deleted. */
    public static final String CODE_NOT_SAVEABLE = "job_not_saveable";
    /** The account already has as many saved generations as it can keep. */
    public static final String CODE_LIMIT_REACHED = "saved_limit_reached";

    private JobSaveRefusedException(String detail, String code) {
        super(HttpStatus.CONFLICT, detail);
        getBody().setProperty("code", code);
    }

    static JobSaveRefusedException notFinished() {
        return new JobSaveRefusedException("Only a finished generation can be saved.", CODE_NOT_SAVEABLE);
    }

    static JobSaveRefusedException purged() {
        return new JobSaveRefusedException(
                "This generation's files have been deleted, so it can't be saved.", CODE_NOT_SAVEABLE);
    }

    /** Carries the limit as {@code limit}, for a client that shows it. */
    static JobSaveRefusedException limitReached(int limit) {
        JobSaveRefusedException e = new JobSaveRefusedException(
                "You have saved " + limit + " generations, the most an account can keep. "
                        + "Un-save one to save another.", CODE_LIMIT_REACHED);
        e.getBody().setProperty("limit", limit);
        return e;
    }
}
