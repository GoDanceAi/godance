package ai.godance.gateway;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.stereotype.Component;

import ai.godance.domain.Modality;
import ai.godance.domain.Provider;

/**
 * Catalog of supported models with pricing + provider routing.
 * Pricing is in <b>microcents per 1000 tokens</b> (1 microcent = 0.0001¢
 * = $0.000001) — chosen so OpenAI's "$0.15 per 1M tokens" maps cleanly
 * to a small integer ({@code 150}) without floating-point drift.
 *
 * <p>Models are not hardcoded here: each modality contributes its slice
 * via a {@link CatalogContributor} bean (see {@link
 * ai.godance.gateway.catalog}). This catalog autowires every contributor
 * and merges them, so adding a provider never edits this file.
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

    public ModelCatalog(List<CatalogContributor> contributors) {
        for (CatalogContributor contributor : contributors) {
            for (ModelInfo model : contributor.models()) {
                register(model);
            }
        }
    }

    /** Fail fast at startup if two contributors claim the same model name. */
    private void register(ModelInfo m) {
        ModelInfo prior = catalog.put(m.modelName(), m);
        if (prior != null) {
            throw new IllegalStateException(
                    "duplicate catalog entry for model '" + m.modelName()
                            + "': " + prior.provider() + " vs " + m.provider());
        }
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
