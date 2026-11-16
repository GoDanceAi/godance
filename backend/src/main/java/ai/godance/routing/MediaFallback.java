package ai.godance.routing;

import java.util.List;
import java.util.Set;

/**
 * Round 14's addition to round 4's design: where a batch row's image or video
 * job goes when it fails on {@code primary}. Used only by batches created with
 * {@code "fallbacks": true}; a row is retried once, on the first alternative
 * whose triggers cover the failure. A content refusal is never a trigger.
 */
public record MediaFallback(String primary, List<Alternative> alternatives) {

    public MediaFallback {
        alternatives = List.copyOf(alternatives);
    }

    /** The failures an alternative takes over from. */
    public enum Trigger {
        /** The provider answered an error, or the job failed for a reason that is not a refusal or a timeout. */
        PROVIDER_ERROR,
        /** The job ran out of time. */
        TIMEOUT,
        /** The job failed while the primary's recent success rate was under the alternative's threshold. */
        HEALTH_BELOW
    }

    /** One alternative, the failures that send a row to it, and its health threshold (0–1) for {@code HEALTH_BELOW}. */
    public record Alternative(String model, Set<Trigger> on, Double healthBelow) {
        public Alternative {
            on = Set.copyOf(on);
        }
    }
}
