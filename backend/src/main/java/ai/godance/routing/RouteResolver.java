package ai.godance.routing;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Component;

import ai.godance.config.GatewayProperties;
import ai.godance.domain.Region;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;

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
 * <p>A virtual id is a built-in preset, or (Phase 3, design §6) one of the
 * caller's own policies, by the name the caller gave it. A preset wins over a
 * name, and a catalog model always means a direct call, so a user's policy
 * can never change what a direct call does. The caller's
 * {@code X-Godance-Route} hint reaches the rules' {@code hint_equals}. A
 * policy's provider limits (the compliance opt-out) also hold for the
 * appended fallback chain, so an excluded provider is never tried.
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
    private final ModelCatalog catalog;
    private final UserPolicySource userPolicies;

    public RouteResolver(RoutingPolicyRegistry registry, RoutingEngine engine,
                         TaskClassifier classifier, GatewayProperties gatewayProps,
                         ModelCatalog catalog, UserPolicySource userPolicies) {
        this.registry = registry;
        this.engine = engine;
        this.classifier = classifier;
        this.gatewayProps = gatewayProps;
        this.catalog = catalog;
        this.userPolicies = userPolicies;
    }

    /** Round 4's form: presets only, no hint. */
    public ResolvedRoute resolve(ChatRequest request, int estimatedInputTokens, Region region) {
        return resolve(null, request, null, estimatedInputTokens, region);
    }

    /**
     * The route for {@code userId}'s request. {@code routeHint} is the
     * caller's {@code X-Godance-Route} header or {@code route_hint} field, or
     * null; one that a rule could never name is ignored.
     */
    public ResolvedRoute resolve(UUID userId, ChatRequest request, String routeHint,
                                 int estimatedInputTokens, Region region) {
        Optional<RoutingPolicy> policy = registry.find(request.model());
        if (policy.isEmpty() && userId != null && catalog.find(request.model()).isEmpty()) {
            policy = userPolicies.find(userId, request.model());
        }

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
                usableHint(routeHint),
                region);

        RoutingDecision decision = engine.resolve(ctx, policy.get());

        // Deep safety net: append the static fallback chain of the chosen primary,
        // less any model the policy's provider limits rule out.
        List<String> attempts = new ArrayList<>(decision.orderedCandidates());
        if (!attempts.isEmpty()) {
            for (String fb : gatewayProps.fallbacksFor(attempts.get(0))) {
                if (!attempts.contains(fb) && withinProviderLimits(fb, policy.get().limits())) attempts.add(fb);
            }
        }
        return new ResolvedRoute(attempts, policy.get().id(), decision.matchedRule());
    }

    /** A hint a rule's {@code hint_equals} could match, or null. */
    static String usableHint(String raw) {
        if (raw == null) return null;
        String hint = raw.trim();
        return RoutingPolicyValidator.HINT.matcher(hint).matches() ? hint : null;
    }

    /**
     * Whether a fallback model passes the policy's allowed and excluded
     * providers. With neither set (every preset) nothing is looked up, so a
     * preset's chain is appended exactly as before.
     */
    private boolean withinProviderLimits(String model, RoutingLimits limits) {
        if (limits.allowedProviders().isEmpty() && limits.excludedProviders().isEmpty()) return true;
        ModelInfo mi = catalog.find(model).orElse(null);
        if (mi == null) return false;
        if (limits.excludedProviders().contains(mi.provider())) return false;
        return limits.allowedProviders().isEmpty() || limits.allowedProviders().contains(mi.provider());
    }
}
