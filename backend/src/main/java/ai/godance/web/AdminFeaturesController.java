package ai.godance.web;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import ai.godance.config.ByokProperties;
import ai.godance.config.CommunityProperties;
import ai.godance.config.InputsProperties;
import ai.godance.config.ReferralProperties;
import ai.godance.domain.UserRole;
import ai.godance.mcp.McpProperties;
import ai.godance.routing.UserRoutingPolicyProperties;
import ai.godance.security.AdminRoles;
import ai.godance.web.dto.AdminFeaturesResponse;
import reactor.core.publisher.Mono;

/** Read-only backend configuration, not account eligibility or provider/site health. */
@RestController
public class AdminFeaturesController {
    private final InputsProperties inputs;
    private final CommunityProperties community;
    private final ByokProperties byok;
    private final UserRoutingPolicyProperties routing;
    private final ReferralProperties referrals;
    private final McpProperties mcp;

    public AdminFeaturesController(InputsProperties inputs, CommunityProperties community,
                                   ByokProperties byok, UserRoutingPolicyProperties routing,
                                   ReferralProperties referrals, McpProperties mcp) {
        this.inputs = inputs;
        this.community = community;
        this.byok = byok;
        this.routing = routing;
        this.referrals = referrals;
        this.mcp = mcp;
    }

    @GetMapping("/v1/admin/features")
    @AdminRoles(UserRole.ADMIN)
    public Mono<AdminFeaturesResponse> features() {
        return Mono.just(new AdminFeaturesResponse(
                new AdminFeaturesResponse.Inputs(inputs.enabled(), inputs.allowPeople()),
                new AdminFeaturesResponse.Enabled(community.enabled()),
                new AdminFeaturesResponse.Enabled(!byok.providers().isEmpty()),
                new AdminFeaturesResponse.RoutingPolicies(routing.enabled(), !routing.allowList().isEmpty()),
                new AdminFeaturesResponse.Enabled(referrals.enabled()),
                new AdminFeaturesResponse.Enabled(mcp.enabled())));
    }
}
