package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * A refused image edit ({@code POST /v1/images/edits}): problem+json whose
 * {@code code} says which kind, answered before anything is stored or
 * charged.
 */
public final class ImageEditRefusedException extends ResponseStatusException {

    /** The model does not take input images, that many of them, or a mask. {@code field} names which. 400. */
    public static final String CODE_NOT_SUPPORTED = "input_not_supported";
    /**
     * A request field the route does not read, or a value it cannot read;
     * {@code param} names the field, as OpenAI's error does. 400.
     */
    public static final String CODE_INVALID_PARAMETER = "invalid_parameter";

    private final String code;

    private ImageEditRefusedException(String detail, String code) {
        super(HttpStatus.BAD_REQUEST, detail);
        this.code = code;
        getBody().setProperty("code", code);
    }

    /** The problem's {@code code}. */
    public String code() {
        return code;
    }

    private static ImageEditRefusedException notSupported(String detail, String field) {
        ImageEditRefusedException e = new ImageEditRefusedException(detail, CODE_NOT_SUPPORTED);
        e.getBody().setProperty("field", field);
        return e;
    }

    /** The model takes no input images. */
    public static ImageEditRefusedException imagesNotSupported(String model) {
        return notSupported("The model " + model + " does not take input images. "
                + "GET /v1/models lists the ones that do, under godance.capabilities.inputs.images.", "image");
    }

    /** More input images than the model takes. */
    public static ImageEditRefusedException tooManyImages(String model, int max, int given) {
        ImageEditRefusedException e = notSupported("The model " + model + " takes at most " + max
                + (max == 1 ? " input image" : " input images") + "; the request has " + given + ".", "image");
        e.getBody().setProperty("max", max);
        return e;
    }

    /** The model takes input images but no mask. */
    public static ImageEditRefusedException maskNotSupported(String model) {
        return notSupported("The model " + model + " does not take a mask.", "mask");
    }

    /** A field the route does not read, or a value it cannot read; {@code param} is null for the request as a whole. */
    public static ImageEditRefusedException invalid(String param, String detail) {
        ImageEditRefusedException e = new ImageEditRefusedException(detail, CODE_INVALID_PARAMETER);
        if (param != null) e.getBody().setProperty("param", param);
        return e;
    }
}
