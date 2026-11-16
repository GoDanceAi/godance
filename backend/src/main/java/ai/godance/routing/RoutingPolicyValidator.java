package ai.godance.routing;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.routing.RoutingPolicyDocuments.AlternativeBody;
import ai.godance.routing.RoutingPolicyDocuments.ConditionBody;
import ai.godance.routing.RoutingPolicyDocuments.FallbackBody;
import ai.godance.routing.RoutingPolicyDocuments.LimitsBody;
import ai.godance.routing.RoutingPolicyDocuments.PolicyBody;
import ai.godance.routing.RoutingPolicyDocuments.RuleBody;

/**
 * Checks a user's policy before it is stored (design §6): every problem is
 * collected, so one 400 names them all, and nothing invalid ever reaches the
 * engine. The design's rules:
 * <ul>
 *   <li>every target and the default model exist in the catalog and are TEXT models;</li>
 *   <li>at most {@value #MAX_RULES} rules, each with no more targets than
 *       {@code limits.max_candidates}, which is at most
 *       {@value RoutingPolicyDocuments#MAX_CANDIDATES_CAP};</li>
 *   <li>a default model is required, so a request always has somewhere to go;</li>
 *   <li>allowed and excluded providers name real providers.</li>
 * </ul>
 * Added here: the name's form, and that it is not a preset's or a catalog
 * model's (so a direct call is never redirected); rule names, conditions and
 * hints that the engine and the usage records can carry; targets the policy's
 * own provider limits would drop; and the batch fallbacks (image or video
 * models of one modality, each primary in one policy of the account).
 */
@Component
public class RoutingPolicyValidator {

    public static final int MAX_RULES = 16;
    public static final int MAX_FALLBACKS = 16;
    public static final int MAX_ALTERNATIVES = 4;

    /** A policy's name, as callers request it and as {@code usr_<user>:<name>} stores it. */
    public static final Pattern NAME = Pattern.compile("^[a-z0-9][a-z0-9._-]{0,62}$");
    /** A rule's name: it goes on usage records ({@code routed_rule}) and in logs. */
    private static final Pattern RULE_NAME = Pattern.compile("^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$");
    /** A route hint, as {@code X-Godance-Route} or {@code route_hint} may carry it. */
    public static final Pattern HINT = Pattern.compile("^[A-Za-z0-9._-]{1,64}$");
    /** Names the built-in presets use; a user's policy may not start with it. */
    public static final String RESERVED_PREFIX = "godance-";

    private static final Set<String> TRIGGERS = Set.of("provider_error", "timeout", "health_below");

    private final ModelCatalog catalog;
    private final RoutingPolicyRegistry presets;

    public RoutingPolicyValidator(ModelCatalog catalog, RoutingPolicyRegistry presets) {
        this.catalog = catalog;
        this.presets = presets;
    }

    /**
     * The problems with {@code body} as policy {@code name}; empty when it is
     * valid. {@code otherFallbacks} maps each primary model with fallbacks in
     * the account's other policies to the policy that has them.
     */
    public List<String> problems(String name, PolicyBody body, Map<String, String> otherFallbacks) {
        List<String> problems = new ArrayList<>();
        checkName(name, problems);
        if (body == null) {
            problems.add("the body must be a routing policy");
            return problems;
        }
        if (body.modality() != null && !Modality.TEXT.name().equalsIgnoreCase(body.modality().trim())) {
            problems.add("modality must be TEXT: routing chooses chat models, and image and video models "
                    + "go in fallbacks");
        }

        Set<Provider> allowed = new HashSet<>();
        Set<Provider> excluded = new HashSet<>();
        int maxCandidates = checkLimits(body.limits(), allowed, excluded, problems);

        if (body.defaultModel() == null || body.defaultModel().isBlank()) {
            problems.add("default_model is required");
        } else {
            checkTextModel("default_model", body.defaultModel().trim(), allowed, excluded, problems);
        }

        List<RuleBody> rules = RoutingPolicyDocuments.orEmpty(body.rules());
        if (rules.size() > MAX_RULES) {
            problems.add("a policy has at most " + MAX_RULES + " rules; this one has " + rules.size());
        }
        Set<String> ruleNames = new HashSet<>();
        for (int i = 0; i < rules.size(); i++) {
            checkRule(i, rules.get(i), maxCandidates, allowed, excluded, ruleNames, problems);
        }

        checkFallbacks(RoutingPolicyDocuments.orEmpty(body.fallbacks()), allowed, excluded, otherFallbacks, problems);
        return problems;
    }

    private void checkName(String name, List<String> problems) {
        if (name == null || !NAME.matcher(name).matches()) {
            problems.add("the policy id must be 1-63 lowercase letters, digits, '.', '_' or '-', "
                    + "starting with a letter or digit");
            return;
        }
        if (name.startsWith(RESERVED_PREFIX) || presets.find(name).isPresent()) {
            problems.add("'" + name + "' is reserved for Godance's built-in policies");
        }
        if (catalog.find(name).isPresent()) {
            problems.add("'" + name + "' is a model; a policy needs a name of its own");
        }
    }

    /** The limits' problems; returns {@code max_candidates} as it will apply. */
    private int checkLimits(LimitsBody limits, Set<Provider> allowed, Set<Provider> excluded,
                            List<String> problems) {
        if (limits == null) return RoutingLimits.DEFAULTS.maxCandidates();
        int maxCandidates = RoutingLimits.DEFAULTS.maxCandidates();
        if (limits.maxCandidates() != null) {
            if (limits.maxCandidates() < 1 || limits.maxCandidates() > RoutingPolicyDocuments.MAX_CANDIDATES_CAP) {
                problems.add("limits.max_candidates must be between 1 and "
                        + RoutingPolicyDocuments.MAX_CANDIDATES_CAP);
            } else {
                maxCandidates = limits.maxCandidates();
            }
        }
        if (limits.maxEstimateCentsCeiling() != null && limits.maxEstimateCentsCeiling() < 1) {
            problems.add("limits.max_estimate_cents_ceiling must be at least 1 cent");
        }
        providers("limits.allowed_providers", limits.allowedProviders(), allowed, problems);
        providers("limits.excluded_providers", limits.excludedProviders(), excluded, problems);
        for (Provider p : allowed) {
            if (excluded.contains(p)) {
                problems.add("limits: " + p + " is both allowed and excluded");
            }
        }
        return maxCandidates;
    }

    private static void providers(String field, List<String> names, Set<Provider> into, List<String> problems) {
        for (String raw : RoutingPolicyDocuments.orEmpty(names)) {
            String n = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT);
            try {
                into.add(Provider.valueOf(n));
            } catch (IllegalArgumentException e) {
                problems.add(field + ": '" + raw + "' is not a provider");
            }
        }
    }

    private void checkRule(int i, RuleBody rule, int maxCandidates, Set<Provider> allowed, Set<Provider> excluded,
                           Set<String> ruleNames, List<String> problems) {
        String at = "rules[" + i + "]";
        if (rule == null) {
            problems.add(at + " is empty");
            return;
        }
        String name = rule.name() == null ? null : rule.name().trim();
        if (name == null || !RULE_NAME.matcher(name).matches()) {
            problems.add(at + ".name must be 1-64 letters, digits, '.', '_' or '-'");
        } else if ("default".equalsIgnoreCase(name)) {
            problems.add(at + ".name 'default' is reserved for the default model");
        } else if (!ruleNames.add(name.toLowerCase(Locale.ROOT))) {
            problems.add(at + ".name '" + name + "' is used by another rule");
        }

        ConditionBody when = rule.when();
        if (when != null) {
            if (when.maxInputTokens() != null && when.maxInputTokens() < 0) {
                problems.add(at + ".when.max_input_tokens must be 0 or more");
            }
            if (when.minInputTokens() != null && when.minInputTokens() < 0) {
                problems.add(at + ".when.min_input_tokens must be 0 or more");
            }
            if (when.maxInputTokens() != null && when.minInputTokens() != null
                    && when.minInputTokens() > when.maxInputTokens()) {
                problems.add(at + ".when: min_input_tokens is more than max_input_tokens, so it never matches");
            }
            for (String task : RoutingPolicyDocuments.orEmpty(when.taskIn())) {
                try {
                    TaskType.valueOf(task == null ? "" : task.trim().toUpperCase(Locale.ROOT));
                } catch (IllegalArgumentException e) {
                    problems.add(at + ".when.task_in: '" + task + "' is not a task (CHAT, CODE, REASONING, "
                            + "LONG_CONTEXT, UNKNOWN)");
                }
            }
            if (when.hintEquals() != null && !HINT.matcher(when.hintEquals().trim()).matches()) {
                problems.add(at + ".when.hint_equals must be 1-64 letters, digits, '.', '_' or '-'");
            }
        }

        List<String> targets = RoutingPolicyDocuments.orEmpty(rule.targets());
        if (targets.isEmpty()) {
            problems.add(at + ".targets must name at least one model");
        }
        if (targets.size() > maxCandidates) {
            problems.add(at + ".targets has " + targets.size() + " models; limits.max_candidates allows "
                    + maxCandidates);
        }
        for (String target : targets) {
            if (target == null || target.isBlank()) {
                problems.add(at + ".targets has an empty model name");
            } else {
                checkTextModel(at + ".targets", target.trim(), allowed, excluded, problems);
            }
        }
    }

    /** A catalog TEXT model whose provider this policy's own limits let through. */
    private void checkTextModel(String field, String model, Set<Provider> allowed, Set<Provider> excluded,
                                List<String> problems) {
        ModelInfo mi = catalog.find(model).orElse(null);
        if (mi == null) {
            problems.add(field + ": '" + model + "' is not a model in the catalog");
            return;
        }
        if (mi.modality() != Modality.TEXT) {
            problems.add(field + ": '" + model + "' is a " + mi.modality() + " model; routing chooses TEXT models");
            return;
        }
        checkProvider(field, model, mi.provider(), allowed, excluded, problems);
    }

    private static void checkProvider(String field, String model, Provider provider, Set<Provider> allowed,
                                      Set<Provider> excluded, List<String> problems) {
        if (excluded.contains(provider)) {
            problems.add(field + ": '" + model + "' is from " + provider + ", which this policy excludes");
        } else if (!allowed.isEmpty() && !allowed.contains(provider)) {
            problems.add(field + ": '" + model + "' is from " + provider + ", which this policy does not allow");
        }
    }

    private void checkFallbacks(List<FallbackBody> fallbacks, Set<Provider> allowed, Set<Provider> excluded,
                                Map<String, String> otherFallbacks, List<String> problems) {
        if (fallbacks.size() > MAX_FALLBACKS) {
            problems.add("a policy has at most " + MAX_FALLBACKS + " fallbacks; this one has " + fallbacks.size());
        }
        Set<String> primaries = new HashSet<>();
        for (int i = 0; i < fallbacks.size(); i++) {
            String at = "fallbacks[" + i + "]";
            FallbackBody f = fallbacks.get(i);
            if (f == null || f.primary() == null || f.primary().isBlank()) {
                problems.add(at + ".primary is required");
                continue;
            }
            String primary = f.primary().trim();
            ModelInfo pm = catalog.find(primary).orElse(null);
            if (pm == null) {
                problems.add(at + ".primary: '" + primary + "' is not a model in the catalog");
                continue;
            }
            if (pm.modality() != Modality.IMAGE && pm.modality() != Modality.VIDEO) {
                problems.add(at + ".primary: '" + primary + "' is a " + pm.modality()
                        + " model; fallbacks are for image and video models");
                continue;
            }
            if (!primaries.add(primary)) {
                problems.add(at + ".primary: '" + primary + "' has fallbacks earlier in this policy");
            }
            String elsewhere = otherFallbacks == null ? null : otherFallbacks.get(primary);
            if (elsewhere != null) {
                problems.add(at + ".primary: '" + primary + "' already has fallbacks in your policy '"
                        + elsewhere + "'");
            }
            List<AlternativeBody> alternatives = RoutingPolicyDocuments.orEmpty(f.alternatives());
            if (alternatives.isEmpty() || alternatives.size() > MAX_ALTERNATIVES) {
                problems.add(at + ".alternatives must have 1 to " + MAX_ALTERNATIVES + " models");
            }
            Set<String> seen = new HashSet<>();
            for (int j = 0; j < alternatives.size(); j++) {
                checkAlternative(at + ".alternatives[" + j + "]", alternatives.get(j), pm, allowed, excluded,
                        seen, problems);
            }
        }
    }

    private void checkAlternative(String at, AlternativeBody a, ModelInfo primary, Set<Provider> allowed,
                                  Set<Provider> excluded, Set<String> seen, List<String> problems) {
        if (a == null || a.model() == null || a.model().isBlank()) {
            problems.add(at + ".model is required");
            return;
        }
        String model = a.model().trim();
        ModelInfo mi = catalog.find(model).orElse(null);
        if (mi == null) {
            problems.add(at + ".model: '" + model + "' is not a model in the catalog");
        } else if (mi.modality() != primary.modality()) {
            problems.add(at + ".model: '" + model + "' is a " + mi.modality() + " model; '"
                    + primary.modelName() + "' is " + primary.modality());
        } else {
            checkProvider(at + ".model", model, mi.provider(), allowed, excluded, problems);
        }
        if (model.equals(primary.modelName())) {
            problems.add(at + ".model is the primary itself");
        } else if (!seen.add(model)) {
            problems.add(at + ".model: '" + model + "' is listed twice");
        }
        List<String> on = RoutingPolicyDocuments.orEmpty(a.on());
        if (on.isEmpty()) {
            problems.add(at + ".on must name at least one of provider_error, timeout, health_below");
        }
        boolean health = false;
        for (String t : on) {
            String trigger = t == null ? "" : t.trim().toLowerCase(Locale.ROOT);
            if (!TRIGGERS.contains(trigger)) {
                problems.add(at + ".on: '" + t + "' is not one of provider_error, timeout, health_below "
                        + "(a content refusal is never retried on another model)");
            }
            health |= "health_below".equals(trigger);
        }
        if (health && (a.healthBelow() == null || a.healthBelow() <= 0 || a.healthBelow() > 1)) {
            problems.add(at + ".health_below must be more than 0 and at most 1 (a success rate)");
        }
    }
}
