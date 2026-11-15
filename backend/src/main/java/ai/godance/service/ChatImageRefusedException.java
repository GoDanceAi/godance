package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A chat request refused for an image it carries (round 11 brief 02):
 * problem+json whose {@code code} extension member says which kind. Every
 * one is thrown before anything is charged. No message here ever repeats
 * the URL or the image.
 */
public final class ChatImageRefusedException extends ResponseStatusException {

    /** The model does not take images. 400. */
    public static final String CODE_MODEL_DOES_NOT_ACCEPT_IMAGES = "model_does_not_accept_images";
    /** The image is named in a way that is not a data URL, an https URL or one of the caller's files. 400. */
    public static final String CODE_INVALID_IMAGE_URL = "invalid_image_url";
    /** An https image could not be fetched: unreachable, not public, redirected, not an image, too slow. 400. */
    public static final String CODE_IMAGE_FETCH_FAILED = "image_fetch_failed";
    /** The image is larger than one image may be. 413. */
    public static final String CODE_IMAGE_TOO_LARGE = "image_too_large";
    /** More images than one request may carry. 400. */
    public static final String CODE_TOO_MANY_IMAGES = "too_many_images";
    /** Not a PNG, JPEG or WebP still image. 400. */
    public static final String CODE_IMAGE_TYPE_UNSUPPORTED = "image_type_unsupported";
    /** A content part that is neither text nor an image. 400. */
    public static final String CODE_UNSUPPORTED_CONTENT_PART = "unsupported_content_part";

    private final String code;

    private ChatImageRefusedException(HttpStatus status, String detail, String code) {
        super(status, detail);
        this.code = code;
        getBody().setProperty("code", code);
    }

    public String code() {
        return code;
    }

    public static ChatImageRefusedException modelDoesNotAcceptImages(String model) {
        return new ChatImageRefusedException(HttpStatus.BAD_REQUEST,
                "The model " + model + " does not accept images. GET /v1/models lists the ones that do.",
                CODE_MODEL_DOES_NOT_ACCEPT_IMAGES);
    }

    public static ChatImageRefusedException modelAcceptsFewerImages(String model, int max, int sent) {
        ChatImageRefusedException e = new ChatImageRefusedException(HttpStatus.BAD_REQUEST,
                "The model " + model + " accepts at most " + max + " images in a request; this one has " + sent + ".",
                CODE_MODEL_DOES_NOT_ACCEPT_IMAGES);
        e.getBody().setProperty("max_images", max);
        return e;
    }

    public static ChatImageRefusedException invalidUrl(String detail) {
        return new ChatImageRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_INVALID_IMAGE_URL);
    }

    public static ChatImageRefusedException fetchFailed(String detail) {
        return new ChatImageRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_IMAGE_FETCH_FAILED);
    }

    public static ChatImageRefusedException tooLarge(long maxMegabytes) {
        return new ChatImageRefusedException(HttpStatus.CONTENT_TOO_LARGE,
                "An image is larger than " + maxMegabytes + " MB, the most one image may be.",
                CODE_IMAGE_TOO_LARGE);
    }

    public static ChatImageRefusedException tooMany(int max) {
        ChatImageRefusedException e = new ChatImageRefusedException(HttpStatus.BAD_REQUEST,
                "A request may carry at most " + max + " images.", CODE_TOO_MANY_IMAGES);
        e.getBody().setProperty("max_images", max);
        return e;
    }

    public static ChatImageRefusedException typeUnsupported(String detail) {
        return new ChatImageRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_IMAGE_TYPE_UNSUPPORTED);
    }

    public static ChatImageRefusedException unsupportedPart(String detail) {
        return new ChatImageRefusedException(HttpStatus.BAD_REQUEST, detail, CODE_UNSUPPORTED_CONTENT_PART);
    }
}
