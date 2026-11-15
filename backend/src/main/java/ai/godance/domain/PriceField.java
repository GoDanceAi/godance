package ai.godance.domain;

/**
 * Which column on {@link ModelCatalogEntry} a price observation refers to.
 * Persisted as lowercase in {@code model_price_history.field} so it reads
 * naturally in operator queries.
 */
public enum PriceField {
    INPUT,
    OUTPUT,
    PER_IMAGE,
    PER_SECOND,
    /** What a provider charges for each input image of an edit (V33). */
    PER_INPUT_IMAGE;

    public String asColumnKey() {
        return switch (this) {
            case INPUT      -> "input";
            case OUTPUT     -> "output";
            case PER_IMAGE  -> "per_image";
            case PER_SECOND -> "per_second";
            case PER_INPUT_IMAGE -> "per_input_image";
        };
    }
}
