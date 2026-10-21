package ai.godance.routing;

import java.util.List;

/**
 * Output of {@link RouteResolver#resolve} (design §3.5): the ordered
 * concrete catalog models the service should attempt, plus audit
 * metadata. For a direct (non-virtual) model call {@code policyId} and
 * {@code matchedRule} are {@code null} and the candidate list is exactly
 * {@code [request.model()] + fallbacksFor(request.model())}.
 */
public record ResolvedRoute(
        List<String> orderedCandidates,  // concrete catalog model names, in attempt order
        String policyId,                 // the virtual id, or null for a direct call
        String matchedRule               // matched rule name / "default", or null for a direct call
) {
    public ResolvedRoute {
        orderedCandidates = List.copyOf(orderedCandidates);
    }

    /** True when this route came from a virtual policy (vs. a direct model call). */
    public boolean fromVirtual() {
        return policyId != null;
    }
}
