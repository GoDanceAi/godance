package ai.godance.domain;

import java.util.Locale;

/**
 * How image requests made with an API key answer (V30). On the wire it is
 * lower case: {@code "async"} or {@code "sync"}.
 */
public enum ImageResponseMode {
    /** 202 with a job to poll: what every key did before V30. */
    ASYNC,
    /** Wait for the job, then answer in OpenAI's shape. */
    SYNC;

    /** The value as requests and responses carry it. */
    public String wire() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** The mode a wire value names, in any case; null when it names none. */
    public static ImageResponseMode fromWire(String value) {
        if (value == null) return null;
        return switch (value.trim().toLowerCase(Locale.ROOT)) {
            case "async" -> ASYNC;
            case "sync" -> SYNC;
            default -> null;
        };
    }
}
