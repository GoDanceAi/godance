package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused input: problem+json whose {@code code} extension member says
 * which kind, so a client can branch without reading {@code detail}. The
 * codes up to {@link #CODE_FILE_NOT_FOUND} are round 11's contract; the rest
 * are additions for cases it does not name.
 */
public final class InputRefusedException extends ResponseStatusException {

    /** Inputs are switched off ({@code godance.inputs.enabled}). 403. */
    public static final String CODE_DISABLED = "inputs_disabled";
    /** The account has not accepted the current input terms. 403. */
    public static final String CODE_TERMS_REQUIRED = "inputs_terms_required";
    /** The account is not an adult's. 403. */
    public static final String CODE_NOT_ALLOWED_FOR_ACCOUNT = "inputs_not_allowed_for_account";
    /** Not a PNG, JPEG or WebP still image, whatever the upload called itself. 415. */
    public static final String CODE_TYPE_UNSUPPORTED = "input_type_unsupported";
    /** More bytes, or more pixels, than an input may have. 413. */
    public static final String CODE_TOO_LARGE = "input_too_large";
    /** The image shows a person, and people are not allowed in inputs. 422. */
    public static final String CODE_PERSON_NOT_ALLOWED = "input_person_not_allowed";
    /** The person check could not be run, so the image cannot be accepted. 503. */
    public static final String CODE_CHECK_UNAVAILABLE = "input_check_unavailable";
    /** No such file for this caller. Another user's answers the same. 404. */
    public static final String CODE_FILE_NOT_FOUND = "file_not_found";

    /** No such job output for this caller: unknown, another user's, unfinished, deleted, or not an image. 404. */
    public static final String CODE_JOB_OUTPUT_NOT_FOUND = "job_output_not_found";
    /** An input reference that is not exactly one of {@code file_id} and {@code job_output}. 400. */
    public static final String CODE_INVALID_REFERENCE = "invalid_input_reference";
    /** The account already stores as many files as it may. 409. */
    public static final String CODE_LIMIT_REACHED = "input_limit_reached";
    /** A terms version that is not the current one. 400. */
    public static final String CODE_TERMS_VERSION = "inputs_terms_version_mismatch";

    private final String code;

    private InputRefusedException(HttpStatus status, String detail, String code) {
        super(status, detail);
        this.code = code;
        getBody().setProperty("code", code);
    }

    /** The problem's {@code code}. */
    public String code() {
        return code;
    }

    public static InputRefusedException disabled() {
        return new InputRefusedException(HttpStatus.FORBIDDEN,
                "Image inputs are not available.", CODE_DISABLED);
    }

    public static InputRefusedException termsRequired(int version) {
        InputRefusedException e = new InputRefusedException(HttpStatus.FORBIDDEN,
                "Accept the input terms before using your own images.", CODE_TERMS_REQUIRED);
        e.getBody().setProperty("terms_version", version);
        return e;
    }

    public static InputRefusedException notAllowedForAccount() {
        return new InputRefusedException(HttpStatus.FORBIDDEN,
                "Image inputs are not available for this account.", CODE_NOT_ALLOWED_FOR_ACCOUNT);
    }

    public static InputRefusedException typeUnsupported(String detail) {
        return new InputRefusedException(HttpStatus.UNSUPPORTED_MEDIA_TYPE, detail, CODE_TYPE_UNSUPPORTED);
    }

    public static InputRefusedException tooLarge(String detail) {
        return new InputRefusedException(HttpStatus.CONTENT_TOO_LARGE, detail, CODE_TOO_LARGE);
    }

    public static InputRefusedException personNotAllowed() {
        return new InputRefusedException(HttpStatus.UNPROCESSABLE_CONTENT,
                "Images of people are not accepted yet.", CODE_PERSON_NOT_ALLOWED);
    }

    public static InputRefusedException checkUnavailable() {
        return new InputRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "The image could not be checked, so it was not accepted. Try again later.",
                CODE_CHECK_UNAVAILABLE);
    }

    public static InputRefusedException fileNotFound() {
        return new InputRefusedException(HttpStatus.NOT_FOUND, "file not found", CODE_FILE_NOT_FOUND);
    }

    public static InputRefusedException jobOutputNotFound() {
        return new InputRefusedException(HttpStatus.NOT_FOUND,
                "job output not found: it must be an image output of one of your finished jobs whose files are still kept",
                CODE_JOB_OUTPUT_NOT_FOUND);
    }

    public static InputRefusedException invalidReference(String detail) {
        return new InputRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_INVALID_REFERENCE);
    }

    public static InputRefusedException limitReached(int limit) {
        InputRefusedException e = new InputRefusedException(HttpStatus.CONFLICT,
                "You have " + limit + " stored input files, the most an account can keep. Delete one to upload another.",
                CODE_LIMIT_REACHED);
        e.getBody().setProperty("limit", limit);
        return e;
    }

    public static InputRefusedException termsVersion(int current) {
        InputRefusedException e = new InputRefusedException(HttpStatus.BAD_REQUEST,
                "That is not the current version of the input terms.", CODE_TERMS_VERSION);
        e.getBody().setProperty("terms_version", current);
        return e;
    }
}
