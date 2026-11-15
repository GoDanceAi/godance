package ai.godance.service;

import java.util.Locale;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A video request whose images the model cannot take: problem+json whose
 * {@code code} says which kind, answered before anything is charged. The two
 * codes are round 11's contract for {@code POST /v1/videos/generations}.
 */
public final class VideoInputRefusedException extends ResponseStatusException {

    /**
     * The model does not take this input: the field, that many reference
     * images, or the inputs together. 400.
     */
    public static final String CODE_NOT_SUPPORTED = "input_not_supported";
    /** The image's shape is outside what the model's provider accepts. 400. */
    public static final String CODE_ASPECT_MISMATCH = "input_aspect_mismatch";

    private final String code;

    private VideoInputRefusedException(String detail, String code) {
        super(HttpStatus.BAD_REQUEST, detail);
        this.code = code;
        getBody().setProperty("code", code);
    }

    /** The problem's {@code code}. */
    public String code() {
        return code;
    }

    /** The model takes no such field. */
    public static VideoInputRefusedException fieldNotSupported(String model, String field) {
        VideoInputRefusedException e = new VideoInputRefusedException(
                "The model " + model + " does not take " + field
                        + ". GET /v1/models lists what each model takes, under godance.capabilities.inputs.",
                CODE_NOT_SUPPORTED);
        e.getBody().setProperty("field", field);
        return e;
    }

    /** More reference images than the model takes. */
    public static VideoInputRefusedException tooManyReferenceImages(String model, int max, int given) {
        VideoInputRefusedException e = new VideoInputRefusedException(
                "The model " + model + " takes at most " + max + (max == 1 ? " reference image" : " reference images")
                        + "; the request has " + given + ".",
                CODE_NOT_SUPPORTED);
        e.getBody().setProperty("field", "reference_images");
        e.getBody().setProperty("max", max);
        return e;
    }

    /** Inputs the model takes one at a time, sent together. */
    public static VideoInputRefusedException combinationNotSupported(String detail, String field) {
        VideoInputRefusedException e = new VideoInputRefusedException(detail, CODE_NOT_SUPPORTED);
        e.getBody().setProperty("field", field);
        return e;
    }

    /**
     * An image whose shape the provider does not accept. The detail names
     * both: the image's own size, and the range it has to be in.
     */
    public static VideoInputRefusedException aspectMismatch(String model, String field, int width, int height,
                                                           double minAspect, double maxAspect) {
        VideoInputRefusedException e = new VideoInputRefusedException(
                field + " is " + width + "×" + height + ", a width-to-height ratio of " + ratio((double) width / height)
                        + ". The model " + model + " takes images whose ratio is between " + ratio(minAspect)
                        + " and " + ratio(maxAspect) + ". Crop the image and send it again; it is not cropped for you.",
                CODE_ASPECT_MISMATCH);
        e.getBody().setProperty("field", field);
        e.getBody().setProperty("width", width);
        e.getBody().setProperty("height", height);
        e.getBody().setProperty("min_aspect", minAspect);
        e.getBody().setProperty("max_aspect", maxAspect);
        return e;
    }

    private static String ratio(double value) {
        return String.format(Locale.ROOT, "%.2f", value);
    }
}
