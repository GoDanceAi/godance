package ai.godance.service;

import java.util.Map;
import java.util.Set;

/**
 * The request fields a batch's jobs are made with, read from its
 * {@code params}: the names a generation request uses ({@code size},
 * {@code n}, {@code seconds}, {@code style}, {@code quality}), which is
 * also what a recipe version stores. Each row's job gets exactly these.
 *
 * @param n        how many outputs each row asks for, or null for the default of 1
 * @param seconds  a video's length, or null for the model's default; not used by an image job
 */
record BatchSettings(Integer n, Integer seconds, String size, String style, String quality) {

    private static final Set<String> NAMES = Set.of("size", "n", "seconds", "style", "quality");

    /**
     * @throws BatchRefusedException 400 {@code invalid_batch} for a name a
     *         generation request does not have, or a value of the wrong kind:
     *         a setting is never silently dropped
     */
    static BatchSettings of(Map<String, Object> params) {
        Map<String, Object> given = params == null ? Map.of() : params;
        for (String name : given.keySet()) {
            if (!NAMES.contains(name)) {
                throw BatchRefusedException.invalid("params has no setting called \"" + name
                        + "\"; a batch takes size, n, seconds, style and quality");
            }
        }
        return new BatchSettings(wholeNumber(given, "n"), wholeNumber(given, "seconds"),
                text(given, "size"), text(given, "style"), text(given, "quality"));
    }

    /** How many outputs each row asks for: {@code n}, or 1. */
    int outputsPerRow() {
        return n == null ? 1 : n;
    }

    private static Integer wholeNumber(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null) return null;
        Long whole = null;
        if (value instanceof Number number && number.doubleValue() == Math.rint(number.doubleValue())) {
            whole = number.longValue();
        } else if (value instanceof String text) {
            try {
                whole = Long.valueOf(text.trim());
            } catch (NumberFormatException ignored) {
                // falls through to the refusal
            }
        }
        if (whole == null || whole < 1 || whole > Integer.MAX_VALUE) {
            throw BatchRefusedException.invalid("params." + name + " must be a whole number of 1 or more");
        }
        return whole.intValue();
    }

    private static String text(Map<String, Object> params, String name) {
        Object value = params.get(name);
        if (value == null) return null;
        if (!(value instanceof String text)) {
            throw BatchRefusedException.invalid("params." + name + " must be text");
        }
        return text.isBlank() ? null : text;
    }
}
