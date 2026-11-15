package ai.godance.gateway;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * What a model accepts, from {@code model_catalog.capabilities} (V24). Every
 * field is optional and null means unknown, not unlimited.
 *
 * <p>Only {@code maxN} is enforced: a request for more outputs than it allows
 * is refused before any charge, and a model that does not state it allows one.
 * {@code sizes} and {@code durations} are published for clients and are not
 * checked. {@code defaultSeconds} restates the adapter's
 * {@code defaultDurationSeconds()}, which is what is priced and sent.
 *
 * <p>{@code inputs} (round 11) says which images a model can be given. It
 * is enforced: an input the model does not state is refused before any
 * charge. Absent means none, so a model takes no input until its row says
 * it does.
 */
public record ModelCapabilities(
        List<String> sizes,
        String defaultSize,
        List<Integer> durations,
        Integer defaultSeconds,
        Integer maxN,
        Inputs inputs
) {
    /** The output count allowed when a model states none. */
    public static final int DEFAULT_MAX_N = 1;

    public static final ModelCapabilities UNKNOWN = new ModelCapabilities(null, null, null, null, null, Inputs.NONE);

    public ModelCapabilities {
        sizes = sizes == null ? null : List.copyOf(sizes);
        durations = durations == null ? null : List.copyOf(durations);
        inputs = inputs == null ? Inputs.NONE : inputs;
    }

    /** A model that takes no inputs. */
    public ModelCapabilities(List<String> sizes, String defaultSize, List<Integer> durations,
                             Integer defaultSeconds, Integer maxN) {
        this(sizes, defaultSize, durations, defaultSeconds, maxN, Inputs.NONE);
    }

    /**
     * The images a model can be given, from {@code capabilities.inputs}:
     * <pre>
     * "inputs": { "images": { "max": 4, "mask": true } }            image models, chat models
     * "inputs": { "first_frame": true, "last_frame": false,
     *             "reference_images": { "max": 4 } }                video models
     * </pre>
     * Everything defaults to "no": {@code maxImages} and
     * {@code maxReferenceImages} are 0 and the flags false unless the stored
     * JSON says otherwise in exactly the right shape. {@code lastFrameStated}
     * only remembers that the row said {@code "last_frame": false}, so it
     * is published back as it was written.
     */
    public record Inputs(int maxImages, boolean mask, boolean firstFrame, boolean lastFrame,
                         boolean lastFrameStated, int maxReferenceImages) {

        public static final Inputs NONE = new Inputs(0, false, false, false, false, 0);

        /** Whether the model takes input images at all (an edit, or images in chat). */
        public boolean images() {
            return maxImages > 0;
        }

        /** Whether the model follows reference images. */
        public boolean referenceImages() {
            return maxReferenceImages > 0;
        }

        public boolean isEmpty() {
            return maxImages == 0 && !firstFrame && !lastFrame && !lastFrameStated && maxReferenceImages == 0;
        }

        static Inputs fromJson(Object raw) {
            if (!(raw instanceof Map<?, ?> map)) return NONE;
            int maxImages = 0;
            boolean mask = false;
            if (map.get("images") instanceof Map<?, ?> images) {
                Integer max = positiveInt(images.get("max"));
                if (max != null) {
                    maxImages = max;
                    mask = Boolean.TRUE.equals(images.get("mask"));
                }
            }
            boolean firstFrame = Boolean.TRUE.equals(map.get("first_frame"));
            boolean lastFrame = Boolean.TRUE.equals(map.get("last_frame"));
            boolean lastFrameStated = map.get("last_frame") instanceof Boolean;
            int maxReferenceImages = 0;
            if (map.get("reference_images") instanceof Map<?, ?> references) {
                Integer max = positiveInt(references.get("max"));
                if (max != null) maxReferenceImages = max;
            }
            Inputs inputs = new Inputs(maxImages, mask, firstFrame, lastFrame, lastFrameStated, maxReferenceImages);
            return inputs.isEmpty() ? NONE : inputs;
        }

        Map<String, Object> toJson() {
            Map<String, Object> out = new LinkedHashMap<>();
            if (maxImages > 0) {
                Map<String, Object> images = new LinkedHashMap<>();
                images.put("max", maxImages);
                if (mask) images.put("mask", true);
                out.put("images", images);
            }
            if (firstFrame) out.put("first_frame", true);
            if (lastFrame || lastFrameStated) out.put("last_frame", lastFrame);
            if (maxReferenceImages > 0) out.put("reference_images", Map.of("max", maxReferenceImages));
            return out;
        }
    }

    /** The most outputs one request may ask for. */
    public int maxNOrDefault() {
        return maxN == null ? DEFAULT_MAX_N : maxN;
    }

    public boolean isEmpty() {
        return sizes == null && defaultSize == null && durations == null
                && defaultSeconds == null && maxN == null && inputs.isEmpty();
    }

    /**
     * Reads the stored JSON object. A key of the wrong shape is treated as
     * absent (unknown), never as a looser limit; a null or empty object is
     * {@link #UNKNOWN}.
     */
    public static ModelCapabilities fromJson(Map<String, Object> raw) {
        if (raw == null || raw.isEmpty()) return UNKNOWN;
        return new ModelCapabilities(
                stringList(raw.get("sizes")),
                raw.get("default_size") instanceof String s ? s : null,
                positiveIntList(raw.get("durations")),
                positiveInt(raw.get("default_seconds")),
                positiveInt(raw.get("max_n")),
                Inputs.fromJson(raw.get("inputs")));
    }

    /** The published form: snake_case keys, absent fields left out. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sizes != null) out.put("sizes", sizes);
        if (defaultSize != null) out.put("default_size", defaultSize);
        if (durations != null) out.put("durations", durations);
        if (defaultSeconds != null) out.put("default_seconds", defaultSeconds);
        if (maxN != null) out.put("max_n", maxN);
        if (!inputs.isEmpty()) out.put("inputs", inputs.toJson());
        return out;
    }

    private static List<String> stringList(Object v) {
        if (!(v instanceof List<?> list)) return null;
        List<String> out = new ArrayList<>(list.size());
        for (Object o : list) {
            if (!(o instanceof String s)) return null;
            out.add(s);
        }
        return out;
    }

    private static List<Integer> positiveIntList(Object v) {
        if (!(v instanceof List<?> list)) return null;
        List<Integer> out = new ArrayList<>(list.size());
        for (Object o : list) {
            Integer i = positiveInt(o);
            if (i == null) return null;
            out.add(i);
        }
        return out;
    }

    private static Integer positiveInt(Object v) {
        if (v instanceof Integer i) return i > 0 ? i : null;
        if (v instanceof Long l) return l > 0 && l <= Integer.MAX_VALUE ? (int) (long) l : null;
        if (v instanceof Number n) {
            double d = n.doubleValue();
            return d == Math.rint(d) && d > 0 && d <= Integer.MAX_VALUE ? (int) d : null;
        }
        return null;
    }
}
