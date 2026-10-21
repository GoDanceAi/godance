package ai.godance.routing;

/**
 * Coarse task category inferred from a chat request by
 * {@link TaskClassifier}. Used only to choose a routing branch — it is
 * deliberately cheap and approximate (design §4.3). A learned classifier
 * can replace the heuristic without changing this enum or the engine.
 */
public enum TaskType {
    CHAT,
    CODE,
    REASONING,
    LONG_CONTEXT,
    UNKNOWN
}
