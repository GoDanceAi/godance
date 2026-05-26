package ai.godance.service.pricing;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.Provider;

/**
 * Maps upstream feed model ids to Godance {@code model_name}s, and
 * classifies unseen ids as either a {@link Resolution.Kind#NEW_VERSION
 * new version} of a known family or a completely {@link
 * Resolution.Kind#NEW_MODEL new model}.
 *
 * <p>Resolution order:
 * <ol>
 *   <li>Exact match in the per-feed alias map (highest authority).</li>
 *   <li>Bare-id-after-vendor-strip equals a known catalog
 *       {@code model_name} of the same provider — return it as
 *       {@link Resolution.Kind#KNOWN}.</li>
 *   <li>Bare id shares a hyphen-prefix with any catalog model of the
 *       same provider — return {@link Resolution.Kind#NEW_VERSION}
 *       (proposal lands but is non-billable until a human approves).</li>
 *   <li>Otherwise — {@link Resolution.Kind#NEW_MODEL}, parked as
 *       {@code status=DISCOVERED} on approval (no adapter mapping yet).</li>
 * </ol>
 *
 * <p>The resolver is intentionally provider-aware: an unseen {@code
 * xai/gpt-5} would never be classified as a NEW_VERSION of OpenAI's GPT
 * family because the provider differs.
 */
public class ModelAliasResolver {

    /** Vendor-prefix to Provider mapping for OpenRouter-style namespaced ids. */
    private static final Map<String, Provider> VENDOR_TO_PROVIDER = Map.ofEntries(
            Map.entry("openai",        Provider.OPENAI),
            Map.entry("anthropic",     Provider.ANTHROPIC),
            Map.entry("google",        Provider.GOOGLE_VERTEX),
            Map.entry("vertex",        Provider.GOOGLE_VERTEX),
            Map.entry("xai",           Provider.XAI),
            Map.entry("deepseek",      Provider.DEEPSEEK),
            Map.entry("moonshot",      Provider.MOONSHOT),
            Map.entry("moonshotai",    Provider.MOONSHOT),
            Map.entry("alibaba",       Provider.ALIBABA_DASHSCOPE),
            Map.entry("qwen",          Provider.ALIBABA_DASHSCOPE),
            Map.entry("dashscope",     Provider.ALIBABA_DASHSCOPE),
            Map.entry("zhipu",         Provider.ZHIPU),
            Map.entry("z-ai",          Provider.ZHIPU),
            Map.entry("zhipuai",       Provider.ZHIPU),
            Map.entry("minimax",       Provider.MINIMAX),
            Map.entry("baidu",         Provider.BAIDU),
            Map.entry("bytedance",     Provider.VOLCENGINE_ARK),
            Map.entry("volcengine",    Provider.VOLCENGINE_ARK),
            Map.entry("bfl",           Provider.BFL),
            Map.entry("blackforestlabs", Provider.BFL),
            Map.entry("stability",     Provider.STABILITY),
            Map.entry("stabilityai",   Provider.STABILITY),
            Map.entry("kling",         Provider.KLING),
            Map.entry("kuaishou",      Provider.KLING),
            Map.entry("runway",        Provider.RUNWAY),
            Map.entry("runwayml",      Provider.RUNWAY));

    /** Per-feed alias map ({@code feedName -> sourceId -> godanceModelName}). */
    private final Map<String, Map<String, String>> aliasMap;
    /** Current catalog snapshot, keyed by model_name (for family-stem matching). */
    private final List<ModelCatalogEntry> catalogSnapshot;

    public ModelAliasResolver(Map<String, Map<String, String>> aliasMap,
                              List<ModelCatalogEntry> catalogSnapshot) {
        this.aliasMap = aliasMap;
        this.catalogSnapshot = catalogSnapshot;
    }

    public Resolution resolve(String feedName, String sourceId) {
        Map<String, String> feedAliases = aliasMap.getOrDefault(feedName, Map.of());
        String aliased = feedAliases.get(sourceId);
        if (aliased != null) {
            return new Resolution(Resolution.Kind.KNOWN, aliased,
                    inferProvider(sourceId).orElse(null));
        }
        String bare = stripVendorPrefix(sourceId);
        Optional<Provider> provider = inferProvider(sourceId);

        // Step 2 — bare id is a direct catalog match.
        for (ModelCatalogEntry e : catalogSnapshot) {
            if (e.getModelName().equals(bare)
                    && provider.map(p -> p == e.getProvider()).orElse(true)) {
                return new Resolution(Resolution.Kind.KNOWN, bare, e.getProvider());
            }
        }
        // Step 3 — family-stem match against a same-provider catalog model.
        if (familyStemMatchesAny(bare, provider.orElse(null))) {
            return new Resolution(Resolution.Kind.NEW_VERSION, bare, provider.orElse(null));
        }
        // Step 4 — completely new.
        return new Resolution(Resolution.Kind.NEW_MODEL, bare, provider.orElse(null));
    }

    /**
     * Strip the longest trailing hyphen-segment of the bare id; if any
     * catalog model of the same provider {@code startsWith(prefix + "-")},
     * it's a new family member. Iterates progressively shorter prefixes
     * to handle multi-segment versions like {@code 4-7} → {@code 4} →
     * (empty stop).
     */
    private boolean familyStemMatchesAny(String bareId, Provider provider) {
        String prefix = bareId;
        while (true) {
            int lastDash = prefix.lastIndexOf('-');
            if (lastDash <= 0) return false;
            prefix = prefix.substring(0, lastDash);
            String prefixDash = prefix + "-";
            for (ModelCatalogEntry e : catalogSnapshot) {
                if (provider != null && e.getProvider() != provider) continue;
                if (e.getModelName().startsWith(prefixDash) && !e.getModelName().equals(bareId)) {
                    return true;
                }
            }
        }
    }

    /** OpenRouter ids are {@code vendor/name}. Bare ids have no slash. */
    static String stripVendorPrefix(String sourceId) {
        int slash = sourceId.indexOf('/');
        return slash < 0 ? sourceId : sourceId.substring(slash + 1);
    }

    static Optional<Provider> inferProvider(String sourceId) {
        int slash = sourceId.indexOf('/');
        if (slash <= 0) return Optional.empty();
        String vendor = sourceId.substring(0, slash).toLowerCase();
        // Strip OpenRouter region prefixes like "~google/" or "us./".
        if (vendor.startsWith("~")) vendor = vendor.substring(1);
        return Optional.ofNullable(VENDOR_TO_PROVIDER.get(vendor));
    }

    public record Resolution(Kind kind, String godanceModelName, Provider provider) {
        public enum Kind { KNOWN, NEW_VERSION, NEW_MODEL }
    }

    /** Convenience factory — builds with empty catalog (tests / fallback). */
    public static ModelAliasResolver empty(Map<String, Map<String, String>> aliasMap) {
        return new ModelAliasResolver(aliasMap, List.of());
    }

    /** Convenience: build the feed-name keyed map from {@link ai.godance.config.PricingProperties.AliasMap}. */
    public static Map<String, Map<String, String>> asFeedMap(
            ai.godance.config.PricingProperties.AliasMap m) {
        Map<String, Map<String, String>> out = new HashMap<>();
        out.put("openrouter", m.getOpenrouter());
        out.put("litellm",    m.getLitellm());
        return out;
    }
}
