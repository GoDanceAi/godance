package ai.godance.config;

import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Configuration for the daily pricing-refresh job + downstream services.
 * Bound to {@code godance.pricing.*} in {@code application.yml}.
 *
 * <p>All knobs are env-overridable so production can tune cadence,
 * sanity bounds, and feed URLs without a rebuild. Secrets must NEVER be
 * placed here — feed URLs are public; LLM-fallback credentials, if any
 * one day, would live in their own provider-properties block.
 */
@ConfigurationProperties("godance.pricing")
public class PricingProperties {

    /** Quartz cron — default 04:00 UTC daily, well clear of the auto-recharge 03:00 window. */
    private String refreshCron = "0 0 4 * * ?";

    /** Percent delta below which a price observation is logged but never proposed. */
    private double noiseThresholdPct = 1.0;

    /** Sanity floor — values at or below this become {@code ANOMALY}, not {@code PRICE_CHANGE}. */
    private long minMicrocents = 1;

    /** Sanity ceiling — multiplier of prior value above which the observation is an {@code ANOMALY}. */
    private double maxMultiplier = 10.0;

    /** Cheap catalog model used for HTML→JSON extraction of provider pricing pages. */
    private String llmFallbackModelId = "gemini-3.1-flash-lite";

    /** Per-run cap on how many fallback pages may be fetched (token + bandwidth guardrail). */
    private int llmFallbackMaxPagesPerRun = 20;

    /** Per-fetch timeout for fallback page pulls. */
    private java.time.Duration llmFallbackFetchTimeout = java.time.Duration.ofSeconds(15);

    /** Per-page allowlist regexes — fetches outside this set are refused. */
    private List<String> pageAllowlistRegex = List.of();

    private Feeds feeds = new Feeds();

    private AliasMap aliasMap = new AliasMap();

    private List<FallbackPage> fallbackPages = List.of();

    // ----- accessors -----
    public String getRefreshCron() { return refreshCron; }
    public void setRefreshCron(String v) { this.refreshCron = v; }

    public double getNoiseThresholdPct() { return noiseThresholdPct; }
    public void setNoiseThresholdPct(double v) { this.noiseThresholdPct = v; }

    public long getMinMicrocents() { return minMicrocents; }
    public void setMinMicrocents(long v) { this.minMicrocents = v; }

    public double getMaxMultiplier() { return maxMultiplier; }
    public void setMaxMultiplier(double v) { this.maxMultiplier = v; }

    public String getLlmFallbackModelId() { return llmFallbackModelId; }
    public void setLlmFallbackModelId(String v) { this.llmFallbackModelId = v; }

    public int getLlmFallbackMaxPagesPerRun() { return llmFallbackMaxPagesPerRun; }
    public void setLlmFallbackMaxPagesPerRun(int v) { this.llmFallbackMaxPagesPerRun = v; }

    public java.time.Duration getLlmFallbackFetchTimeout() { return llmFallbackFetchTimeout; }
    public void setLlmFallbackFetchTimeout(java.time.Duration v) { this.llmFallbackFetchTimeout = v; }

    public List<String> getPageAllowlistRegex() { return pageAllowlistRegex; }
    public void setPageAllowlistRegex(List<String> v) { this.pageAllowlistRegex = v; }

    public Feeds getFeeds() { return feeds; }
    public void setFeeds(Feeds feeds) { this.feeds = feeds; }

    public AliasMap getAliasMap() { return aliasMap; }
    public void setAliasMap(AliasMap aliasMap) { this.aliasMap = aliasMap; }

    public List<FallbackPage> getFallbackPages() { return fallbackPages; }
    public void setFallbackPages(List<FallbackPage> v) { this.fallbackPages = v; }

    public static class Feeds {
        private String openrouterUrl = "https://openrouter.ai/api/v1/models";
        private String litellmUrl = "https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json";

        public String getOpenrouterUrl() { return openrouterUrl; }
        public void setOpenrouterUrl(String v) { this.openrouterUrl = v; }

        public String getLitellmUrl() { return litellmUrl; }
        public void setLitellmUrl(String v) { this.litellmUrl = v; }
    }

    /**
     * Upstream model id → Godance {@code model_name}. Per-feed map so a
     * single Godance model can have different ids on different aggregators
     * (e.g. {@code anthropic/claude-opus-4-7} on OpenRouter vs.
     * {@code claude-opus-4-7-20251220} on LiteLLM).
     */
    public static class AliasMap {
        private Map<String, String> openrouter = Map.of();
        private Map<String, String> litellm = Map.of();

        public Map<String, String> getOpenrouter() { return openrouter; }
        public void setOpenrouter(Map<String, String> v) { this.openrouter = v; }

        public Map<String, String> getLitellm() { return litellm; }
        public void setLitellm(Map<String, String> v) { this.litellm = v; }
    }

    /**
     * Allowlisted pricing page for the LLM-fallback path. {@code url}
     * must match one of {@link PricingProperties#getPageAllowlistRegex()};
     * {@code provider} hints the LLM which provider it's reading.
     */
    public static class FallbackPage {
        private String url;
        private String provider;

        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }

        public String getProvider() { return provider; }
        public void setProvider(String provider) { this.provider = provider; }
    }
}
