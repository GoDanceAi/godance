package ai.godance.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.boot.context.properties.source.MapConfigurationPropertySource;

import ai.godance.config.ByokProperties;
import ai.godance.config.CommunityProperties;
import ai.godance.config.InputsProperties;
import ai.godance.config.ReferralProperties;
import ai.godance.mcp.McpProperties;
import ai.godance.routing.UserRoutingPolicyProperties;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Exact field allowlist: a property bean must never become an API response. */
class AdminFeaturesControllerTest {
    private static final JsonMapper JSON = JsonMapper.builderWithJackson2Defaults().build();

    private AdminFeaturesController controller(Map<String, String> settings) {
        Binder binder = new Binder(new MapConfigurationPropertySource(settings));
        return new AdminFeaturesController(
                binder.bindOrCreate("godance.inputs", InputsProperties.class),
                binder.bindOrCreate("godance.community", CommunityProperties.class),
                binder.bindOrCreate("godance.byok", ByokProperties.class),
                binder.bindOrCreate("godance.routing.user-policies", UserRoutingPolicyProperties.class),
                binder.bindOrCreate("godance.promo.referral", ReferralProperties.class),
                binder.bindOrCreate("godance.mcp", McpProperties.class));
    }

    @Test
    void features_givenDefaults_returnsOnlyTheFixedBooleanSchema() {
        assertThat(JSON.<JsonNode>valueToTree(controller(Map.of()).features().block())).isEqualTo(JSON.readTree("""
                {"inputs":{"enabled":false,"allow_people":false},
                 "community":{"enabled":false},"byok":{"enabled":false},
                 "routing_policies":{"enabled":false,"allow_list_restricted":false},
                 "referrals":{"enabled":false},"mcp":{"enabled":true}}
                """));
    }

    @Test
    void features_givenAlternateSettings_returnsBooleansWithoutPrivateConfiguration() {
        var endpoint = controller(Map.ofEntries(
                Map.entry("godance.inputs.enabled", "true"),
                Map.entry("godance.inputs.allow-people", "true"),
                Map.entry("godance.inputs.check-model", "synthetic-private-model"),
                Map.entry("godance.community.enabled", "true"),
                Map.entry("godance.community.media-base-url", "https://private.example.test/media/"),
                Map.entry("godance.byok.providers", "OPENAI"),
                Map.entry("godance.routing.user-policies.enabled", "true"),
                Map.entry("godance.routing.user-policies.allow-list", "synthetic-allowlist@example.test,other@example.test"),
                Map.entry("godance.promo.referral.referee-reward-cents", "100"),
                Map.entry("godance.mcp.enabled", "false"),
                Map.entry("godance.mcp.gallery-url", "https://private.example.test/gallery/"),
                Map.entry("godance.openai.api-key", "UNUSABLE_SYNTHETIC_SECRET"),
                Map.entry("godance.admin.emails", "synthetic-admin@example.test")));
        var result = JSON.valueToTree(endpoint.features().block());
        assertThat(result).isEqualTo(JSON.readTree("""
                {"inputs":{"enabled":true,"allow_people":true},
                 "community":{"enabled":true},"byok":{"enabled":true},
                 "routing_policies":{"enabled":true,"allow_list_restricted":true},
                 "referrals":{"enabled":true},"mcp":{"enabled":false}}
                """));
        assertThat(result.toString()).doesNotContain("example.test", "SYNTHETIC", "private-model", "OPENAI", "100");
        // Repeated reads are pure: no service/provider client is a controller dependency.
        assertThat(JSON.<JsonNode>valueToTree(endpoint.features().block())).isEqualTo(result);
    }

    @Test
    void features_givenReferrerRewardOnly_reportsReferralsEnabled() {
        assertThat(controller(Map.of("godance.promo.referral.referrer-reward-cents", "1"))
                .features().block().referrals().enabled()).isTrue();
    }

    @Test
    void features_givenDisabledRoutingWithAnAllowlist_reportsConfigurationIndependently() {
        var response = controller(Map.of("godance.routing.user-policies.allow-list", "synthetic@example.test"))
                .features().block();
        assertThat(response.routingPolicies().enabled()).isFalse();
        assertThat(response.routingPolicies().allowListRestricted()).isTrue();
    }
}
