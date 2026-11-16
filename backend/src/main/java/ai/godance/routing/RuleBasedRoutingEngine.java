package ai.godance.routing;

import java.util.ArrayList;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;

/**
 * Deterministic, first-match-wins routing engine (design §4.2). Walks
 * the policy's rules in order; the first whose condition matches supplies
 * the candidate targets, otherwise the policy's {@code defaultModel} is
 * used. Candidates are then validated against the live catalog and the
 * policy limits: unknown / wrong-modality / provider-excluded models are
 * dropped (with a warn, never a 500), duplicates removed, and the list
 * truncated to {@code maxCandidates}. If nothing survives — not even the
 * default — the request fails with an RFC 7807 503.
 *
 * <p>A policy's {@code maxEstimateCentsCeiling} (design §3.3, enforced here
 * as §4.2 suggests) drops a candidate whose input estimate for this request
 * is above it; when that leaves nothing, the request is refused with a 400
 * that says so, before anything is charged. The presets set no ceiling.
 *
 * <p>Region is deliberately NOT considered here — a region-forbidden
 * model is a perfectly valid candidate; {@code ProviderGateway.dispatch()}
 * remains the single chokepoint that rejects it at attempt time
 * (design §11).
 */
@Component
public class RuleBasedRoutingEngine implements RoutingEngine {

    private static final Logger log = LoggerFactory.getLogger(RuleBasedRoutingEngine.class);

    private final ModelCatalog catalog;
    private final CostCalculator calculator;

    public RuleBasedRoutingEngine(ModelCatalog catalog, CostCalculator calculator) {
        this.catalog = catalog;
        this.calculator = calculator;
    }

    @Override
    public RoutingDecision resolve(RoutingContext ctx, RoutingPolicy policy) {
        String matched = "default";
        List<String> raw = List.of(policy.defaultModel());

        for (RoutingRule rule : policy.rules()) {
            if (rule.when().matches(ctx)) {
                matched = rule.name();
                raw = rule.targets();
                break;                       // first match wins
            }
        }

        boolean[] overCeiling = {false};
        List<String> candidates = applyLimitsAndValidate(raw, policy, ctx, overCeiling);
        if (candidates.isEmpty()) {
            // Every matched candidate was unknown/filtered — fall back to the
            // policy default, validated the same way.
            candidates = applyLimitsAndValidate(List.of(policy.defaultModel()), policy, ctx, overCeiling);
        }
        if (candidates.isEmpty() && overCeiling[0]) {
            // The caller's own budget ruled out everything the policy may use.
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "every model policy " + ctx.requestedModel() + " may use is estimated above its "
                            + "max_estimate_cents_ceiling of " + policy.limits().maxEstimateCentsCeiling()
                            + " cents for this request");
        }
        if (candidates.isEmpty()) {
            // Every target, default included, is unknown or filtered — a bad
            // defaultModel, or an admin DEPRECATED them all (find() only sees
            // ACTIVE rows). The caller can't fix that: 503, not a 500.
            log.warn("policy '{}' has no servable candidate (rule '{}' targets {}, default '{}')",
                    policy.id(), matched, raw, policy.defaultModel());
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                    "model " + policy.id() + " is temporarily unavailable");
        }

        log.info("Routed {} -> rule '{}' -> {} (task={}, inTok={})",
                ctx.requestedModel(), matched, candidates, ctx.task(), ctx.estimatedInputTokens());
        return new RoutingDecision(candidates, matched);
    }

    /**
     * Drop unknown/wrong-modality/provider-filtered models and those estimated
     * above the ceiling, dedupe, truncate to maxCandidates. Sets
     * {@code overCeiling[0]} when the ceiling dropped one.
     */
    private List<String> applyLimitsAndValidate(List<String> raw, RoutingPolicy policy, RoutingContext ctx,
                                                boolean[] overCeiling) {
        RoutingLimits limits = policy.limits();
        List<String> out = new ArrayList<>();
        for (String name : raw) {
            ModelInfo mi = catalog.find(name).orElse(null);
            if (mi == null) {
                log.warn("policy '{}' lists unknown model '{}'; dropping", policy.id(), name);
                continue;
            }
            if (mi.modality() != policy.modality()) continue;
            if (!limits.allowedProviders().isEmpty()
                    && !limits.allowedProviders().contains(mi.provider())) continue;
            if (limits.excludedProviders().contains(mi.provider())) continue;
            if (limits.maxEstimateCentsCeiling() != null
                    && calculator.estimateInputCents(mi, ctx.estimatedInputTokens()) > limits.maxEstimateCentsCeiling()) {
                overCeiling[0] = true;
                continue;
            }
            if (!out.contains(name)) out.add(name);
            if (out.size() >= limits.maxCandidates()) break;
        }
        return out;
    }
}
