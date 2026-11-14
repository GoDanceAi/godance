package ai.godance.domain;

import java.util.Locale;

/**
 * What an input is to the job that uses it (V31). {@link #wire()} is the
 * name the API and the database use.
 */
public enum InputRole {
    /** An image an edit works from. */
    IMAGE,
    /** The mask of an edit: where the image may change. */
    MASK,
    /** The frame a video starts from. */
    FIRST_FRAME,
    /** The frame a video ends on. */
    LAST_FRAME,
    /** An image a video follows, by its position in the request. */
    REFERENCE_IMAGE;

    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    public static InputRole fromWire(String wire) {
        return valueOf(wire.toUpperCase(Locale.ROOT));
    }
}
