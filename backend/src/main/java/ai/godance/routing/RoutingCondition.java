package ai.godance.routing;

import java.util.Set;

/**
 * Declarative match condition for a {@link RoutingRule} (design §3.2).
 * Conditions are modeled as <b>data, not code</b>, so user-authored
 * policies (Phase 3) can be validated and stored without arbitrary
 * expression evaluation. All non-null fields are AND-ed; a {@code null}
 * field means "ignore this dimension".
 */
public record RoutingCondition(
        Integer maxInputTokens,   // match when ctx.estimatedInputTokens() <= this
        Integer minInputTokens,   // match when ctx.estimatedInputTokens() >= this
        Set<TaskType> taskIn,     // match when the detected/hinted task is in this set
        String hintEquals,        // match when the X-Godance-Route hint equals this (case-insensitive)
        Boolean always            // catch-all; use as the last rule
) {
    public boolean matches(RoutingContext ctx) {
        if (Boolean.TRUE.equals(always)) return true;
        if (maxInputTokens != null && ctx.estimatedInputTokens() > maxInputTokens) return false;
        if (minInputTokens != null && ctx.estimatedInputTokens() < minInputTokens) return false;
        if (taskIn != null && !taskIn.isEmpty() && !taskIn.contains(ctx.task())) return false;
        if (hintEquals != null && !hintEquals.equalsIgnoreCase(ctx.routeHint())) return false;
        return true;   // all present constraints satisfied
    }
}
