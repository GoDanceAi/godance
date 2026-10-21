package ai.godance.routing;

import java.util.Collection;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;

/**
 * Resolves a virtual model id to a {@link RoutingPolicy} (design §5).
 * Built-in presets are registered at startup. User-authored policies
 * (Phase 3) will be overlaid from the DB and kept current via
 * {@link #upsert}. Lookup is also how {@code /v1/models} decides which
 * virtual ids to advertise.
 */
@Component
public class RoutingPolicyRegistry {

    private final Map<String, RoutingPolicy> policies = new ConcurrentHashMap<>();

    public RoutingPolicyRegistry(RoutingPresets presets) {
        presets.all().forEach(p -> policies.put(p.id(), p));
        // Phase 3: overlay user-authored policies, e.g.
        // repo.findAllEnabled().forEach(e -> policies.put(e.id(), e.toPolicy()));
    }

    public Optional<RoutingPolicy> find(String modelId) {
        return Optional.ofNullable(policies.get(modelId));
    }

    public Collection<RoutingPolicy> all() {
        return policies.values();
    }

    /** Phase 3: called after a validated user PUT. */
    public void upsert(RoutingPolicy policy) {
        policies.put(policy.id(), policy);
    }
}
