package ai.godance.routing;

import java.util.List;

import ai.godance.domain.Modality;

/**
 * A named routing policy (design §3.1). Maps a virtual model id (e.g.
 * {@code "godance-auto"}) to an ordered set of rules. First matching
 * rule wins; if none match, {@code defaultModel} is used. The same
 * record backs built-in presets (Spring beans, this phase) and, later,
 * user-authored policies loaded from the DB (Phase 3).
 */
public record RoutingPolicy(
        String id,                 // virtual model id callers request
        Modality modality,         // TEXT today; one policy = one modality
        List<RoutingRule> rules,   // evaluated in order
        String defaultModel,       // concrete catalog model; required
        RoutingLimits limits       // guardrails; never null
) {
    public RoutingPolicy {
        rules  = rules  == null ? List.of() : List.copyOf(rules);
        limits = limits == null ? RoutingLimits.DEFAULTS : limits;
    }
}
