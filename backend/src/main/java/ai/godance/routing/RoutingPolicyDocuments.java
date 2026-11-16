package ai.godance.routing;

import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;

/**
 * A routing policy as JSON (design §3 and §6): the body of
 * {@code PUT /v1/routing/policies/{id}}, what is stored, and what
 * {@code GET} shows. Enum values travel as strings so that
 * {@link RoutingPolicyValidator} can name a wrong one in its 400; a body is
 * normalized once it passes, and only then turned into the engine's
 * {@link RoutingPolicy} and the batches' {@link MediaFallback}s.
 */
public final class RoutingPolicyDocuments {

    /** The system's ceiling on {@code limits.max_candidates} (design §6). */
    public static final int MAX_CANDIDATES_CAP = 6;

    private RoutingPolicyDocuments() { }

    /**
     * A policy. {@code modality} is TEXT, the one modality routing serves
     * (design §3.1); image and video models appear only in {@code fallbacks}.
     */
    public record PolicyBody(
            @JsonProperty("modality")      String modality,
            @JsonProperty("rules")         List<RuleBody> rules,
            @JsonProperty("default_model") String defaultModel,
            @JsonProperty("limits")        LimitsBody limits,
            @JsonProperty("fallbacks")     List<FallbackBody> fallbacks,
            @JsonProperty("enabled")       Boolean enabled
    ) { }

    /** First match wins; {@code targets} are tried in order (design §3.2). */
    public record RuleBody(
            @JsonProperty("name")    String name,
            @JsonProperty("when")    ConditionBody when,
            @JsonProperty("targets") List<String> targets
    ) { }

    /** Every field present must hold; an absent field is ignored (design §3.2). */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record ConditionBody(
            @JsonProperty("max_input_tokens") Integer maxInputTokens,
            @JsonProperty("min_input_tokens") Integer minInputTokens,
            @JsonProperty("task_in")          List<String> taskIn,
            @JsonProperty("hint_equals")      String hintEquals,
            @JsonProperty("always")           Boolean always
    ) { }

    /**
     * Guardrails on every decision (design §3.3). {@code allowed_providers}
     * and {@code excluded_providers} are the compliance opt-out: a model from
     * a provider they rule out is never tried, the static fallback chain
     * included.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record LimitsBody(
            @JsonProperty("max_candidates")             Integer maxCandidates,
            @JsonProperty("max_estimate_cents_ceiling") Long maxEstimateCentsCeiling,
            @JsonProperty("allowed_providers")          List<String> allowedProviders,
            @JsonProperty("excluded_providers")         List<String> excludedProviders
    ) { }

    /**
     * Round 14's addition: where a batch row's image or video job goes when
     * {@code primary} fails, for batches that opt in.
     */
    public record FallbackBody(
            @JsonProperty("primary")      String primary,
            @JsonProperty("alternatives") List<AlternativeBody> alternatives
    ) { }

    /**
     * One alternative and the failures that send a row to it:
     * {@code provider_error}, {@code timeout}, and {@code health_below} (the
     * primary's recent success rate under {@code health_below}). A content
     * refusal never does.
     */
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public record AlternativeBody(
            @JsonProperty("model")        String model,
            @JsonProperty("on")           List<String> on,
            @JsonProperty("health_below") Double healthBelow
    ) { }

    /** A policy as {@code GET} shows it: a built-in preset, or one of the caller's own. */
    public record PolicyView(
            @JsonProperty("id")            String id,
            @JsonProperty("built_in")      boolean builtIn,
            @JsonProperty("enabled")       boolean enabled,
            @JsonProperty("modality")      String modality,
            @JsonProperty("rules")         List<RuleBody> rules,
            @JsonProperty("default_model") String defaultModel,
            @JsonProperty("limits")        LimitsBody limits,
            @JsonProperty("fallbacks")     List<FallbackBody> fallbacks,
            @JsonProperty("created_at")    Instant createdAt,
            @JsonProperty("updated_at")    Instant updatedAt
    ) { }

    public record PolicyList(@JsonProperty("data") List<PolicyView> data) { }

    // ===== Normalizing a valid body ====================================

    /** A body that passed {@link RoutingPolicyValidator}, with every default filled in and every enum in its one spelling. */
    public static PolicyBody normalized(PolicyBody b) {
        List<RuleBody> rules = new ArrayList<>();
        for (RuleBody r : orEmpty(b.rules())) {
            ConditionBody w = r.when() == null ? new ConditionBody(null, null, null, null, null) : r.when();
            rules.add(new RuleBody(r.name().trim(), new ConditionBody(
                    w.maxInputTokens(), w.minInputTokens(),
                    w.taskIn() == null || w.taskIn().isEmpty() ? null : upper(w.taskIn()),
                    blankToNull(w.hintEquals()), Boolean.TRUE.equals(w.always()) ? Boolean.TRUE : null),
                    trimmed(r.targets())));
        }
        LimitsBody l = b.limits() == null ? new LimitsBody(null, null, null, null) : b.limits();
        LimitsBody limits = new LimitsBody(
                l.maxCandidates() == null ? RoutingLimits.DEFAULTS.maxCandidates() : l.maxCandidates(),
                l.maxEstimateCentsCeiling(),
                upper(orEmpty(l.allowedProviders())),
                upper(orEmpty(l.excludedProviders())));
        List<FallbackBody> fallbacks = new ArrayList<>();
        for (FallbackBody f : orEmpty(b.fallbacks())) {
            List<AlternativeBody> alternatives = new ArrayList<>();
            for (AlternativeBody a : orEmpty(f.alternatives())) {
                List<String> on = orEmpty(a.on()).stream()
                        .map(t -> t.trim().toLowerCase(Locale.ROOT)).distinct().toList();
                alternatives.add(new AlternativeBody(a.model().trim(), on,
                        on.contains("health_below") ? a.healthBelow() : null));
            }
            fallbacks.add(new FallbackBody(f.primary().trim(), alternatives));
        }
        return new PolicyBody(Modality.TEXT.name(), rules, b.defaultModel().trim(), limits, fallbacks,
                b.enabled() == null || b.enabled());
    }

    // ===== Into the engine's types =====================================

    /** The engine's policy for a normalized body, under the id usage records will name. */
    public static RoutingPolicy toPolicy(String id, PolicyBody b) {
        List<RoutingRule> rules = new ArrayList<>();
        for (RuleBody r : b.rules()) {
            ConditionBody w = r.when();
            Set<TaskType> tasks = w.taskIn() == null ? null
                    : EnumSet.copyOf(w.taskIn().stream().map(TaskType::valueOf).toList());
            rules.add(new RoutingRule(r.name(),
                    new RoutingCondition(w.maxInputTokens(), w.minInputTokens(), tasks, w.hintEquals(), w.always()),
                    r.targets()));
        }
        LimitsBody l = b.limits();
        return new RoutingPolicy(id, Modality.TEXT, rules, b.defaultModel(), new RoutingLimits(
                l.maxCandidates(), l.maxEstimateCentsCeiling(),
                providers(l.allowedProviders()), providers(l.excludedProviders())));
    }

    /** The batches' fallbacks of a normalized body. */
    public static List<MediaFallback> toFallbacks(PolicyBody b) {
        List<MediaFallback> out = new ArrayList<>();
        for (FallbackBody f : b.fallbacks()) {
            List<MediaFallback.Alternative> alternatives = new ArrayList<>();
            for (AlternativeBody a : f.alternatives()) {
                Set<MediaFallback.Trigger> on = EnumSet.noneOf(MediaFallback.Trigger.class);
                for (String t : a.on()) on.add(MediaFallback.Trigger.valueOf(t.toUpperCase(Locale.ROOT)));
                alternatives.add(new MediaFallback.Alternative(a.model(), on, a.healthBelow()));
            }
            out.add(new MediaFallback(f.primary(), alternatives));
        }
        return out;
    }

    /** A built-in preset, as {@code GET} shows it. */
    public static PolicyView presetView(RoutingPolicy p) {
        List<RuleBody> rules = new ArrayList<>();
        for (RoutingRule r : p.rules()) {
            RoutingCondition w = r.when();
            rules.add(new RuleBody(r.name(), new ConditionBody(w.maxInputTokens(), w.minInputTokens(),
                    w.taskIn() == null || w.taskIn().isEmpty() ? null
                            : w.taskIn().stream().map(Enum::name).sorted().toList(),
                    w.hintEquals(), w.always()), r.targets()));
        }
        RoutingLimits l = p.limits();
        return new PolicyView(p.id(), true, true, p.modality().name(), rules, p.defaultModel(),
                new LimitsBody(l.maxCandidates(), l.maxEstimateCentsCeiling(),
                        l.allowedProviders().stream().map(Enum::name).sorted().toList(),
                        l.excludedProviders().stream().map(Enum::name).sorted().toList()),
                List.of(), null, null);
    }

    // ===== Helpers =====================================================

    static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    private static List<String> upper(List<String> values) {
        Set<String> out = new LinkedHashSet<>();
        for (String v : values) out.add(v.trim().toUpperCase(Locale.ROOT));
        return List.copyOf(out);
    }

    private static List<String> trimmed(List<String> values) {
        return orEmpty(values).stream().map(String::trim).toList();
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Set<Provider> providers(List<String> names) {
        Set<Provider> out = EnumSet.noneOf(Provider.class);
        for (String n : names) out.add(Provider.valueOf(n));
        return out;
    }
}
