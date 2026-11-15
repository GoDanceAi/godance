package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refusal on OpenAI's video routes ({@code /v1/videos}): problem+json
 * whose {@code code} says which kind, and whose {@code param}, where there
 * is one, names the request field it is about. On those routes the same
 * two values also appear in OpenAI's {@code error} object
 * ({@link ai.godance.security.OpenAiError}), which is what its SDKs read.
 */
public final class OpenAiVideoRefusedException extends ResponseStatusException {

    /** No such video for this caller: unknown, another user's, not a video job, or its files deleted. 404. */
    public static final String CODE_NOT_FOUND = "video_not_found";
    /** The video has no such content: it failed, or it has no file of that kind. 404. */
    public static final String CODE_CONTENT_NOT_FOUND = "video_content_not_found";
    /** The file is recorded but storage could not hand it over just now. 503. */
    public static final String CODE_CONTENT_UNAVAILABLE = "video_content_unavailable";
    /** The video is still queued or in progress, so it has no content yet. 409. */
    public static final String CODE_NOT_READY = "video_not_ready";
    /** The video is still queued or in progress, so it cannot be deleted. 409. */
    public static final String CODE_NOT_DELETABLE = "video_not_deletable";
    /** The files could not be deleted; nothing was changed. 503. */
    public static final String CODE_DELETE_FAILED = "video_delete_failed";
    /** A {@code variant} Godance does not produce. 400. */
    public static final String CODE_VARIANT_NOT_SUPPORTED = "variant_not_supported";
    /** {@code input_reference.image_url}: Godance does not fetch input images. 400. */
    public static final String CODE_IMAGE_URL_NOT_SUPPORTED = "image_url_not_supported";
    /** A request field with a value it cannot have; {@code param} names the field. 400. */
    public static final String CODE_INVALID_PARAMETER = "invalid_parameter";
    /** Remixing a video. 404. */
    public static final String CODE_REMIX_NOT_SUPPORTED = "video_remix_not_supported";
    /** Editing a video. 404. */
    public static final String CODE_EDITS_NOT_SUPPORTED = "video_edits_not_supported";
    /** Extending a video. 404. */
    public static final String CODE_EXTENSIONS_NOT_SUPPORTED = "video_extensions_not_supported";
    /** Characters. 404. */
    public static final String CODE_CHARACTERS_NOT_SUPPORTED = "video_characters_not_supported";

    private final String code;

    private OpenAiVideoRefusedException(HttpStatus status, String detail, String code, String param) {
        super(status, detail);
        this.code = code;
        getBody().setProperty("code", code);
        if (param != null) getBody().setProperty("param", param);
    }

    /** The problem's {@code code}. */
    public String code() {
        return code;
    }

    public static OpenAiVideoRefusedException notFound() {
        return new OpenAiVideoRefusedException(HttpStatus.NOT_FOUND, "video not found", CODE_NOT_FOUND, null);
    }

    public static OpenAiVideoRefusedException contentNotFound(String detail) {
        return new OpenAiVideoRefusedException(HttpStatus.NOT_FOUND, detail, CODE_CONTENT_NOT_FOUND, null);
    }

    public static OpenAiVideoRefusedException contentUnavailable() {
        return new OpenAiVideoRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "The video's file could not be read just now. Try again in a moment.",
                CODE_CONTENT_UNAVAILABLE, null);
    }

    public static OpenAiVideoRefusedException notReady(String status) {
        return new OpenAiVideoRefusedException(HttpStatus.CONFLICT,
                "The video is " + status + ". Its content can be downloaded once its status is completed.",
                CODE_NOT_READY, null);
    }

    public static OpenAiVideoRefusedException notDeletable(String status) {
        return new OpenAiVideoRefusedException(HttpStatus.CONFLICT,
                "The video is " + status + ". It can be deleted once it is completed or failed.",
                CODE_NOT_DELETABLE, null);
    }

    public static OpenAiVideoRefusedException deleteFailed() {
        return new OpenAiVideoRefusedException(HttpStatus.SERVICE_UNAVAILABLE,
                "The video's files could not be deleted. Nothing was changed; try again later.",
                CODE_DELETE_FAILED, null);
    }

    public static OpenAiVideoRefusedException variantNotSupported(String variant) {
        return new OpenAiVideoRefusedException(HttpStatus.BAD_REQUEST,
                "variant " + variant + " is not available. variant is video (the default) or thumbnail.",
                CODE_VARIANT_NOT_SUPPORTED, "variant");
    }

    public static OpenAiVideoRefusedException imageUrlNotSupported() {
        return new OpenAiVideoRefusedException(HttpStatus.BAD_REQUEST,
                "input_reference.image_url is not supported: Godance does not fetch input images. "
                        + "Send the image as the input_reference file part, or upload it with POST /v1/files "
                        + "and send input_reference.file_id.",
                CODE_IMAGE_URL_NOT_SUPPORTED, "input_reference");
    }

    public static OpenAiVideoRefusedException invalid(String param, String detail) {
        return new OpenAiVideoRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_INVALID_PARAMETER, param);
    }

    /** A route of OpenAI's Videos API that Godance does not serve. {@code what} completes "… is not supported". */
    public static OpenAiVideoRefusedException notSupported(String code, String what) {
        return new OpenAiVideoRefusedException(HttpStatus.NOT_FOUND,
                what + " is not supported. Godance serves create, retrieve, list, content and delete "
                        + "of OpenAI's Videos API.",
                code, null);
    }
}
