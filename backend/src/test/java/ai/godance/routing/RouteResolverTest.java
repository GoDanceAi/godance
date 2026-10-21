package ai.godance.routing;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import ai.godance.config.GatewayProperties;
import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * The resolver's contract (design §4.1): a direct model call is an
 * unchanged passthrough (the no-regression guarantee), and a virtual id
 * yields the engine's candidates with the chosen primary's static
 * fallback chain appended.
 */
class RouteResolverTest {

    private final ModelCatalog catalog = TestCatalog.of(
            ModelInfo.text("deepseek-v4-flash", Provider.DEEPSEEK, Modality.TEXT, 140, 280, 4096, null),
            ModelInfo.text("glm-4.7-flash", Provider.ZHIPU, Modality.TEXT, 60, 400, 4096, null));

    private final GatewayProperties props = new GatewayProperties(Map.of(
            "gpt-4o", List.of("gpt-4o-mini"),
            "deepseek-v4-flash", List.of("qwen-3.6")));

    private final RouteResolver resolver = new RouteResolver(
            new RoutingPolicyRegistry(new RoutingPresets()),
            new RuleBasedRoutingEngine(catalog),
            new HeuristicTaskClassifier(),
            props);

    private static ChatRequest req(String model, String content) {
        return new ChatRequest(model, List.of(new ChatMessage("user", content)), null, null, null);
    }

    @Test
    void resolve_givenDirectCatalogModel_returnsModelPlusFallbacks_unchanged() {
        ResolvedRoute r = resolver.resolve(req("gpt-4o", "hello"), 5, Region.LOCAL);

        // Byte-for-byte today's behavior: [model] + fallbacksFor(model).
        assertThat(r.orderedCandidates()).containsExactly("gpt-4o", "gpt-4o-mini");
        assertThat(r.policyId()).isNull();
        assertThat(r.matchedRule()).isNull();
        assertThat(r.fromVirtual()).isFalse();
    }

    @Test
    void resolve_givenDirectModelWithNoFallbacks_returnsJustTheModel() {
        ResolvedRoute r = resolver.resolve(req("lonely-model", "hello"), 5, Region.LOCAL);

        assertThat(r.orderedCandidates()).containsExactly("lonely-model");
        assertThat(r.policyId()).isNull();
    }

    @Test
    void resolve_givenVirtualId_returnsEngineCandidates_plusPrimaryFallbackChain() {
        ResolvedRoute r = resolver.resolve(req("godance-fast", "hi there"), 5, Region.LOCAL);

        // godance-fast 'all-cheap' -> [deepseek-v4-flash, glm-4.7-flash];
        // resolver appends fallbacksFor(deepseek-v4-flash) = [qwen-3.6].
        assertThat(r.orderedCandidates()).containsExactly("deepseek-v4-flash", "glm-4.7-flash", "qwen-3.6");
        assertThat(r.policyId()).isEqualTo("godance-fast");
        assertThat(r.matchedRule()).isEqualTo("all-cheap");
        assertThat(r.fromVirtual()).isTrue();
    }
}
