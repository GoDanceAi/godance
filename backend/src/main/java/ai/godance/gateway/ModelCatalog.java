package ai.godance.gateway;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;

/**
 * Static catalog of supported models with pricing + provider routing.
 * Pricing is in <b>microcents per 1000 tokens</b> (1 microcent = 0.0001¢
 * = $0.000001) — chosen so OpenAI's "$0.15 per 1M tokens" maps cleanly
 * to a small integer ({@code 150}) without floating-point drift.
 *
 * <p>Markup defaults to the per-modality tier in {@link
 * ai.godance.config.BillingProperties}; an explicit {@code markupOverride}
 * on a {@link ModelInfo} wins when set.
 */
@Component
public class ModelCatalog {

    public record ModelInfo(
            String modelName,
            Provider provider,
            Modality modality,
            long inputMicrocentsPer1k,
            long outputMicrocentsPer1k,
            Integer defaultMaxOutputTokens,
            Double markupOverride,         // null = use tier default
            long perImageMicrocents,       // 0 for non-image models
            long perSecondMicrocents,      // 0 for non-video models (Session 9)
            boolean requiresAsync          // true → endpoint must use async path; ignored for sync-only modalities (Session 9)
    ) {
        /** Compact constructor for text/audio/embedding models. */
        public static ModelInfo text(String modelName, Provider provider, Modality modality,
                                     long inputMicrocentsPer1k, long outputMicrocentsPer1k,
                                     Integer defaultMaxOutputTokens, Double markupOverride) {
            return new ModelInfo(modelName, provider, modality,
                    inputMicrocentsPer1k, outputMicrocentsPer1k,
                    defaultMaxOutputTokens, markupOverride, 0L, 0L, false);
        }

        /** Compact constructor for image models. */
        public static ModelInfo image(String modelName, Provider provider,
                                      long perImageMicrocents, Double markupOverride) {
            return new ModelInfo(modelName, provider, Modality.IMAGE,
                    0L, 0L, null, markupOverride, perImageMicrocents, 0L, false);
        }

        /** Image model that auto-promotes to async (HQ variants too slow for sync HTTP). */
        public static ModelInfo imageAsync(String modelName, Provider provider,
                                           long perImageMicrocents, Double markupOverride) {
            return new ModelInfo(modelName, provider, Modality.IMAGE,
                    0L, 0L, null, markupOverride, perImageMicrocents, 0L, true);
        }

        /** Video model — always async; priced per second of output duration. */
        public static ModelInfo video(String modelName, Provider provider,
                                      long perSecondMicrocents, Double markupOverride) {
            return new ModelInfo(modelName, provider, Modality.VIDEO,
                    0L, 0L, null, markupOverride, 0L, perSecondMicrocents, true);
        }
    }

    private final Map<String, ModelInfo> catalog = new HashMap<>();

    public ModelCatalog() {
        // OpenAI text (Session 6)
        register(ModelInfo.text("gpt-4o-mini",
                Provider.OPENAI, Modality.TEXT, 150L, 600L, 4096, null));        // $0.15/$0.60 per 1M
        register(ModelInfo.text("gpt-4o",
                Provider.OPENAI, Modality.TEXT, 2500L, 10000L, 4096, null));     // $2.50/$10.00 per 1M
        register(ModelInfo.text("gpt-4.1",
                Provider.OPENAI, Modality.TEXT, 2000L, 8000L, 4096, null));      // placeholder pricing

        // Anthropic text (Session 7)
        register(ModelInfo.text("claude-3-5-sonnet-20241022",
                Provider.ANTHROPIC, Modality.TEXT, 3000L, 15000L, 8192, null));  // $3 / $15 per 1M
        register(ModelInfo.text("claude-3-5-haiku-20241022",
                Provider.ANTHROPIC, Modality.TEXT, 800L, 4000L, 8192, null));    // $0.80 / $4 per 1M

        // Google Gemini text (Session 7)
        register(ModelInfo.text("gemini-1.5-pro",
                Provider.GOOGLE_VERTEX, Modality.TEXT, 1250L, 5000L, 8192, null));  // $1.25 / $5 per 1M
        register(ModelInfo.text("gemini-1.5-flash",
                Provider.GOOGLE_VERTEX, Modality.TEXT, 75L, 300L, 8192, null));     // $0.075 / $0.30 per 1M

        // Image models (Session 8). Per-image pricing in microcents
        // (1 microcent = $0.000001 → 10 000 µ¢ = 1¢). Size variants share
        // base price for v1; per-size pricing is a polish task. Sources:
        //   - OpenAI: $0.04/standard 1024² (openai.com/api/pricing — gpt-image-1).
        //   - BFL Flux Pro 1.1: $0.04/image (bfl.ai/pricing).
        //   - BFL Flux 2: placeholder until public pricing lands.
        register(ModelInfo.image("gpt-image-1",
                Provider.OPENAI, 40_000L, null));    // $0.04/image
        register(ModelInfo.image("flux-1.1-pro",
                Provider.BFL, 40_000L, null));       // $0.04/image
        register(ModelInfo.image("flux-2",
                Provider.BFL, 50_000L, null));       // placeholder $0.05/image

        // HQ image variants (Session 9): take long enough that sync HTTP is
        // risky; auto-promote to async via the requiresAsync flag.
        register(ModelInfo.imageAsync("gpt-image-1-hd",
                Provider.OPENAI, 80_000L, null));    // $0.08/image (HD 1024² tier)

        // Video models (Session 9). Per-second pricing (1¢ = 10 000 µ¢).
        // Sources:
        //   - Sora 2: $0.15/sec at 1080p (openai.com/api/pricing — placeholder).
        //   - Volcengine Ark Seedance 1.0: ~$0.08/sec at 720p (volcengine.com pricing).
        register(ModelInfo.video("sora-2",
                Provider.OPENAI, 150_000L, null));           // $0.15/sec
        register(ModelInfo.video("seedance-1.0",
                Provider.VOLCENGINE_ARK, 80_000L, null));    // $0.08/sec
    }

    private void register(ModelInfo m) {
        catalog.put(m.modelName(), m);
    }

    public Optional<ModelInfo> find(String modelName) {
        return Optional.ofNullable(catalog.get(modelName));
    }

    /** Read-only snapshot of every registered model, sorted by name. */
    public java.util.List<ModelInfo> all() {
        return catalog.values().stream()
                .sorted(java.util.Comparator.comparing(ModelInfo::modelName))
                .toList();
    }
}
