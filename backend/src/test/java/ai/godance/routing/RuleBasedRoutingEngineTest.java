package ai.godance.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.domain.Region;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * First-match-wins evaluation + the {@code applyLimitsAndValidate}
 * guardrails (design §4.2). Uses a hand-written catalog stub (the repo
 * convention — see PricingRefreshCronJobTest — over a mocking framework).
 */
class RuleBasedRoutingEngineTest {

    private static final ModelInfo CHEAP_A = ModelInfo.text(
            "cheap-a", Provider.DEEPSEEK, Modality.TEXT, 140, 280, 4096, null);
    private static final ModelInfo CHEAP_B = ModelInfo.text(
            "cheap-b", Provider.ZHIPU, Modality.TEXT, 60, 400, 4096, null);
    private static final ModelInfo PREMIUM = ModelInfo.text(
            "premium", Provider.OPENAI, Modality.TEXT, 5000, 30000, 8192, null);
    private static final ModelInfo AN_IMAGE = ModelInfo.image(
            "an-image", Provider.OPENAI, 40_000, null);

    private final RuleBasedRoutingEngine engine =
            new RuleBasedRoutingEngine(TestCatalog.of(CHEAP_A, CHEAP_B, PREMIUM, AN_IMAGE));

    private static RoutingContext ctx(TaskType task, int inTokens) {
        return new RoutingContext("godance-test", Modality.TEXT, inTokens, 1, task, null, Region.LOCAL);
    }

    private static RoutingRule always(String name, String... targets) {
        return new RoutingRule(name, new RoutingCondition(null, null, null, null, true), List.of(targets));
    }

    @Test
    void resolve_givenMultipleMatchingRules_firstMatchWins() {
        RoutingPolicy policy = new RoutingPolicy("p", Modality.TEXT, List.of(
                new RoutingRule("hard",
                        new RoutingCondition(null, null, Set.of(TaskType.CODE), null, null),
                        List.of("premium")),
                always("catch", "cheap-a")),
                "cheap-a", RoutingLimits.DEFAULTS);

        RoutingDecision code = engine.resolve(ctx(TaskType.CODE, 100), policy);
        assertThat(code.matchedRule()).isEqualTo("hard");
        assertThat(code.orderedCandidates()).containsExactly("premium");

        RoutingDecision chat = engine.resolve(ctx(TaskType.CHAT, 100), policy);
        assertThat(chat.matchedRule()).isEqualTo("catch");
        assertThat(chat.orderedCandidates()).containsExactly("cheap-a");
    }

    @Test
    void resolve_givenNoRuleMatches_usesDefaultModel() {
        RoutingPolicy policy = new RoutingPolicy("p", Modality.TEXT, List.of(
                new RoutingRule("hard",
                        new RoutingCondition(null, null, Set.of(TaskType.CODE), null, null),
                        List.of("premium"))),
                "cheap-a", RoutingLimits.DEFAULTS);

        RoutingDecision d = engine.resolve(ctx(TaskType.CHAT, 100), policy);
        assertThat(d.matchedRule()).isEqualTo("default");
        assertThat(d.orderedCandidates()).containsExactly("cheap-a");
    }

    @Test
    void resolve_givenMoreTargetsThanMax_truncatesToMaxCandidates() {
        RoutingLimits twoMax = new RoutingLimits(2, null, Set.of(), Set.of());
        RoutingPolicy policy = new RoutingPolicy("p", Modality.TEXT,
                List.of(always("all", "cheap-a", "cheap-b", "premium")), "cheap-a", twoMax);

        assertThat(engine.resolve(ctx(TaskType.CHAT, 100), policy).orderedCandidates())
                .containsExactly("cheap-a", "cheap-b");
    }

    @Test
    void resolve_givenUnknownModel_dropsIt() {
        RoutingPolicy policy = new RoutingPolicy("p", Modality.TEXT,
                List.of(always("all", "ghost-model", "cheap-a")), "cheap-a", RoutingLimits.DEFAULTS);

        assertThat(engine.resolve(ctx(TaskType.CHAT, 100), policy).orderedCandidates())
                .containsExactly("cheap-a");
    }

    @Test
    void resolve_givenWrongModalityTarget_dropsIt() {
        RoutingPolicy policy = new RoutingPolicy("p", Modality.TEXT,
                List.of(always("all", "an-image", "cheap-a")), "cheap-a", RoutingLimits.DEFAULTS);

        assertThat(engine.resolve(ctx(TaskType.CHAT, 100), policy).orderedCandidates())
                .containsExactly("cheap-a");
    }

    @Test
    void resolve_givenExcludedProvider_dropsThatCandidate() {
        RoutingLimits noOpenAi = new RoutingLimits(4, null, Set.of(), Set.of(Provider.OPENAI));
        RoutingPolicy policy = new RoutingPolicy("p", Modality.TEXT,
                List.of(always("all", "premium", "cheap-a")), "cheap-a", noOpenAi);

        assertThat(engine.resolve(ctx(TaskType.CHAT, 100), policy).orderedCandidates())
                .containsExactly("cheap-a");
    }

    @Test
    void resolve_givenDuplicateTargets_dedupesPreservingOrder() {
        RoutingPolicy policy = new RoutingPolicy("p", Modality.TEXT,
                List.of(always("all", "cheap-a", "cheap-a", "cheap-b")), "cheap-a", RoutingLimits.DEFAULTS);

        assertThat(engine.resolve(ctx(TaskType.CHAT, 100), policy).orderedCandidates())
                .containsExactly("cheap-a", "cheap-b");
    }

    @Test
    void resolve_givenNoTargetOrDefaultInCatalog_throwsServiceUnavailable() {
        // e.g. an admin DEPRECATED every target and the default: find() only sees ACTIVE rows.
        RoutingPolicy policy = new RoutingPolicy("broken", Modality.TEXT,
                List.of(always("all", "gone-a", "gone-b")), "gone-a", RoutingLimits.DEFAULTS);

        assertThatThrownBy(() -> engine.resolve(ctx(TaskType.CHAT, 100), policy))
                .isInstanceOfSatisfying(ResponseStatusException.class, e -> {
                    assertThat(e.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
                    assertThat(e.getReason()).contains("broken");
                });
    }
}
