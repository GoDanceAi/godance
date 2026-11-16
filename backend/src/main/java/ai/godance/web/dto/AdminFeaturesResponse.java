package ai.godance.web.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/** Fixed configuration-only allowlist. Never serialize bound property objects. */
public record AdminFeaturesResponse(
        Inputs inputs,
        Enabled community,
        Enabled byok,
        @JsonProperty("routing_policies") RoutingPolicies routingPolicies,
        Enabled referrals,
        Enabled mcp
) {
    public record Enabled(boolean enabled) { }
    public record Inputs(boolean enabled, @JsonProperty("allow_people") boolean allowPeople) { }
    public record RoutingPolicies(boolean enabled,
                                  @JsonProperty("allow_list_restricted") boolean allowListRestricted) { }
}
