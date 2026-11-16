package ai.godance.routing;

import java.util.Optional;
import java.util.UUID;

/**
 * Where {@link RouteResolver} finds a user's own policy by the name a request
 * gives as its model (design §5: presets first, then user policies).
 */
@FunctionalInterface
public interface UserPolicySource {

    /** Nothing: no account has policies (round 4's Phase 1, and tests). */
    UserPolicySource NONE = (userId, name) -> Optional.empty();

    Optional<RoutingPolicy> find(UUID userId, String name);
}
