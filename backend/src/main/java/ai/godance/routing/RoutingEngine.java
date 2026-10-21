package ai.godance.routing;

/**
 * Resolves a {@link RoutingContext} against a {@link RoutingPolicy} into
 * an ordered candidate list (design §4.2). An interface so a learned
 * router can replace the rules-based default without touching the
 * service (design §9).
 */
public interface RoutingEngine {
    RoutingDecision resolve(RoutingContext ctx, RoutingPolicy policy);
}
