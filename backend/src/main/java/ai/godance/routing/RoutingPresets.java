package ai.godance.routing;

import java.util.List;
import java.util.Set;

import org.springframework.stereotype.Component;

import ai.godance.domain.Modality;

/**
 * Built-in routing presets — the A1 + A3 ladder (design §5.1). These are
 * the only policies this phase; user-authored policies arrive in Phase 3.
 *
 * <p>Targets are bound to ids that exist as {@code ACTIVE} rows in
 * {@code model_catalog} (verified against the V11 seed / {@code GET
 * /v1/models}); the names in the design doc were placeholders. The
 * engine drops any unknown id with a warn, so a future catalog change
 * degrades gracefully rather than 500ing.
 *
 * <p>Tier strategy:
 * <ul>
 *   <li><b>godance-fast</b> — cheapest viable catalog model, volume play.
 *       Default {@code deepseek-v4-flash} (140/280 microcents/1k, the
 *       lowest blended input+output among the cheap tier) with
 *       {@code glm-4.7-flash} (lowest input price) behind it. Both are
 *       China-hosted, so the cheap default never trips the Alicloud
 *       allowlist.</li>
 *   <li><b>godance-auto</b> — cheap for short/plain chat, premium for
 *       code/reasoning/long-context, with a China-hosted floor so a
 *       routed call always has a region-safe last resort.</li>
 *   <li><b>godance-quality</b> — premium-first, frontier models, with the
 *       same China-hosted floor. (Ensemble/Ultra is deferred — design §9.)</li>
 * </ul>
 */
@Component
public class RoutingPresets {

    public List<RoutingPolicy> all() {
        return List.of(fast(), balanced(), quality());
    }

    /** Godance Fast: cheapest single tier; high-volume, low-latency. */
    private RoutingPolicy fast() {
        return new RoutingPolicy("godance-fast", Modality.TEXT,
                List.of(new RoutingRule("all-cheap",
                        new RoutingCondition(null, null, null, null, true),
                        List.of("deepseek-v4-flash", "glm-4.7-flash"))),
                "deepseek-v4-flash", RoutingLimits.DEFAULTS);
    }

    /** Godance Auto / Balanced: cheap for short+chat, premium for hard tasks. */
    private RoutingPolicy balanced() {
        return new RoutingPolicy("godance-auto", Modality.TEXT,
                List.of(
                        new RoutingRule("hard-task",
                                new RoutingCondition(null, null,
                                        Set.of(TaskType.CODE, TaskType.REASONING, TaskType.LONG_CONTEXT),
                                        null, null),
                                List.of("gpt-4o", "claude-sonnet-4-6", "deepseek-v4-pro")),
                        new RoutingRule("short-chat",
                                new RoutingCondition(1500, null, null, null, null),
                                List.of("deepseek-v4-flash", "glm-4.7-flash")),
                        new RoutingRule("default-mid",
                                new RoutingCondition(null, null, null, null, true),
                                List.of("qwen-3.6", "deepseek-v4-flash"))),
                "deepseek-v4-flash", RoutingLimits.DEFAULTS);
    }

    /** Godance Quality: premium-first with a strong China-hosted fallback. */
    private RoutingPolicy quality() {
        return new RoutingPolicy("godance-quality", Modality.TEXT,
                List.of(new RoutingRule("premium-first",
                        new RoutingCondition(null, null, null, null, true),
                        List.of("gpt-5.5", "claude-opus-4-7", "deepseek-v4-pro"))),
                "gpt-5.5", RoutingLimits.DEFAULTS);
    }
}
