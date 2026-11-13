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
 */
public record ModelCapabilities(
        List<String> sizes,
        String defaultSize,
        List<Integer> durations,
        Integer defaultSeconds,
        Integer maxN
) {
    /** The output count allowed when a model states none. */
    public static final int DEFAULT_MAX_N = 1;

    public static final ModelCapabilities UNKNOWN = new ModelCapabilities(null, null, null, null, null);

    public ModelCapabilities {
        sizes = sizes == null ? null : List.copyOf(sizes);
        durations = durations == null ? null : List.copyOf(durations);
    }

    /** The most outputs one request may ask for. */
    public int maxNOrDefault() {
        return maxN == null ? DEFAULT_MAX_N : maxN;
    }

    public boolean isEmpty() {
        return sizes == null && defaultSize == null && durations == null
                && defaultSeconds == null && maxN == null;
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
                positiveInt(raw.get("max_n")));
    }

    /** The published form: snake_case keys, absent fields left out. */
    public Map<String, Object> toJson() {
        Map<String, Object> out = new LinkedHashMap<>();
        if (sizes != null) out.put("sizes", sizes);
        if (defaultSize != null) out.put("default_size", defaultSize);
        if (durations != null) out.put("durations", durations);
        if (defaultSeconds != null) out.put("default_seconds", defaultSeconds);
        if (maxN != null) out.put("max_n", maxN);
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
