package ai.godance.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ai.godance.config.GatewayProperties;
import ai.godance.domain.Region;
import ai.godance.gateway.ChatDtos.ChatRequest;

/**
 * Single entry point the request path calls to turn a requested model
 * into an ordered list of concrete catalog candidates (design §4.1).
 *
 * <ul>
 *   <li><b>Direct (non-virtual) call:</b> when {@code request.model()} is
 *       not a registered virtual id, returns
 *       {@code [request.model()] + fallbacksFor(request.model())} —
 *       exactly today's behavior, so direct calls stay byte-for-byte
 *       identical.</li>
 *   <li><b>Virtual call:</b> runs the engine, then appends the chosen
 *       primary's static fallback chain as a deep safety net (any entry
 *       not already present).</li>
 * </ul>
 *
 * Routing only proposes candidates; region enforcement stays solely in
 * {@code ProviderGateway.dispatch()} (design §11).
 */
@Component
public class RouteResolver {

    private final RoutingPolicyRegistry registry;
    private final RoutingEngine engine;
    private final TaskClassifier classifier;
    private final GatewayProperties gatewayProps;

    public RouteResolver(RoutingPolicyRegistry registry, RoutingEngine engine,
                         TaskClassifier classifier, GatewayProperties gatewayProps) {
        this.registry = registry;
        this.engine = engine;
        this.classifier = classifier;
        this.gatewayProps = gatewayProps;
    }

    public ResolvedRoute resolve(ChatRequest request, int estimatedInputTokens, Region region) {
        Optional<RoutingPolicy> policy = registry.find(request.model());

        // Direct model call: preserve today's exact behavior.
        if (policy.isEmpty()) {
            List<String> attempts = new ArrayList<>();
            attempts.add(request.model());
            attempts.addAll(gatewayProps.fallbacksFor(request.model()));
            return new ResolvedRoute(attempts, null, null);
        }

        RoutingContext ctx = new RoutingContext(
                request.model(),
                policy.get().modality(),
                estimatedInputTokens,
                request.messages().size(),
                classifier.classify(request),
                routeHint(request),            // null until the hint field/header is wired (Phase 3)
                region);

        RoutingDecision decision = engine.resolve(ctx, policy.get());

        // Deep safety net: append the static fallback chain of the chosen primary.
        List<String> attempts = new ArrayList<>(decision.orderedCandidates());
        if (!attempts.isEmpty()) {
            for (String fb : gatewayProps.fallbacksFor(attempts.get(0))) {
                if (!attempts.contains(fb)) attempts.add(fb);
            }
        }
        return new ResolvedRoute(attempts, policy.get().id(), decision.matchedRule());
    }

    private static String routeHint(ChatRequest request) {
        return null; // X-Godance-Route hint wiring is Phase 3
    }
}
