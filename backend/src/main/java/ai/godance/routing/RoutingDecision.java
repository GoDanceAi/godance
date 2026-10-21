package ai.godance.routing;

import java.util.List;

/**
 * Engine output before {@link RouteResolver} appends the chosen
 * primary's static fallback chain (design §4.2).
 */
public record RoutingDecision(List<String> orderedCandidates, String matchedRule) {
    public RoutingDecision {
        orderedCandidates = List.copyOf(orderedCandidates);
    }
}
