package ai.godance.routing;

import java.util.List;

/**
 * One rule within a {@link RoutingPolicy} (design §3.2). When
 * {@link #when()} matches the request context, {@link #targets()} is the
 * ordered list of concrete catalog models to attempt. Rules are
 * evaluated first-match-wins by {@link RuleBasedRoutingEngine}.
 */
public record RoutingRule(
        String name,            // for logs + future UsageRecord audit
        RoutingCondition when,
        List<String> targets    // ordered concrete catalog model names
) {
    public RoutingRule {
        targets = targets == null ? List.of() : List.copyOf(targets);
    }
}
