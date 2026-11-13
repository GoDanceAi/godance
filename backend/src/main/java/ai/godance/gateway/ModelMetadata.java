package ai.godance.gateway;

import java.util.Map;

/**
 * The descriptive half of a catalog row (V24): a name for people, a short
 * description and what the model accepts. Each part may be absent. Kept apart
 * from {@link ModelCatalog.ModelInfo}, which carries pricing and routing and is
 * built in many places; this is looked up by model name.
 */
public record ModelMetadata(String displayName, String description, ModelCapabilities capabilities) {

    public static final ModelMetadata NONE = new ModelMetadata(null, null, ModelCapabilities.UNKNOWN);

    public ModelMetadata {
        if (capabilities == null) capabilities = ModelCapabilities.UNKNOWN;
    }

    public static ModelMetadata of(String displayName, String description, Map<String, Object> capabilities) {
        return new ModelMetadata(blankToNull(displayName), blankToNull(description),
                ModelCapabilities.fromJson(capabilities));
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s;
    }
}
