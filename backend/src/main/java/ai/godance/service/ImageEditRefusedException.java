package ai.godance.service;

import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

/**
 * An image edit the model cannot take: problem+json whose {@code code} says
 * so, answered before anything is stored or charged.
 */
public final class ImageEditRefusedException extends ResponseStatusException {

    /** The model does not take input images, that many of them, or a mask. 400. */
    public static final String CODE_NOT_SUPPORTED = "input_not_supported";

    private final String code;

    private ImageEditRefusedException(String detail, String field) {
        super(HttpStatus.BAD_REQUEST, detail);
        this.code = CODE_NOT_SUPPORTED;
        getBody().setProperty("code", code);
        getBody().setProperty("field", field);
    }

    /** The problem's {@code code}. */
    public String code() {
        return code;
    }

    /** The model takes no input images. */
    public static ImageEditRefusedException imagesNotSupported(String model) {
        return new ImageEditRefusedException("The model " + model + " does not take input images. "
                + "GET /v1/models lists the ones that do, under godance.capabilities.inputs.images.", "image");
    }

    /** More input images than the model takes. */
    public static ImageEditRefusedException tooManyImages(String model, int max, int given) {
        ImageEditRefusedException e = new ImageEditRefusedException("The model " + model + " takes at most " + max
                + (max == 1 ? " input image" : " input images") + "; the request has " + given + ".", "image");
        e.getBody().setProperty("max", max);
        return e;
    }

    /** The model takes input images but no mask. */
    public static ImageEditRefusedException maskNotSupported(String model) {
        return new ImageEditRefusedException("The model " + model + " does not take a mask.", "mask");
    }
}
