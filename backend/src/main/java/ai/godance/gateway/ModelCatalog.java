package ai.godance.gateway;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import ai.godance.domain.CatalogStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.Provider;
import ai.godance.repository.ModelCatalogEntryRepository;
import jakarta.annotation.PostConstruct;

/**
 * Catalog of supported models with pricing + provider routing.
 * Pricing is in <b>microcents per 1000 tokens</b> (1 microcent = 0.0001¢
 * = $0.000001) — chosen so OpenAI's "$0.15 per 1M tokens" maps cleanly
 * to a small integer ({@code 150}) without floating-point drift.
 *
 * <p>The runtime source of truth is the {@code model_catalog} table
 * (seeded by V11 from the prior {@code CatalogContributor} beans at
 * e10be63). On boot — and again whenever {@link #refresh()} is called
 * after an approval write — every {@link CatalogStatus#ACTIVE ACTIVE}
 * row is hydrated into a {@code volatile} in-memory map for lock-free
 * reads on the hot path.
 *
 * <p>{@code DISCOVERED} / {@code STAGED} / {@code DEPRECATED} rows are
 * deliberately excluded from {@link #find(String)} so the rest of the
 * gateway treats them as not-yet-billable. Markup defaults to the
 * per-modality tier in {@link ai.godance.config.BillingProperties}; an
 * explicit {@code markupOverride} on a {@link ModelInfo} wins when set.
 */
@Component
public class ModelCatalog {

    private static final Logger log = LoggerFactory.getLogger(ModelCatalog.class);

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

    private final ModelCatalogEntryRepository entries;
    /**
     * Catalog snapshot — replaced wholesale on every {@link #refresh()}.
     * {@code volatile} so reader threads see the latest reference without
     * locking; the map itself is immutable post-publish, so no per-entry
     * synchronization is needed either.
     */
    private volatile Map<String, ModelInfo> catalog = Map.of();

    public ModelCatalog(ModelCatalogEntryRepository entries) {
        this.entries = entries;
    }

    @PostConstruct
    void load() {
        refresh();
    }

    /**
     * Rebuilds the in-memory map from {@link CatalogStatus#ACTIVE ACTIVE}
     * rows. Called at boot and after every admin approval write. Cheap
     * enough at our model-count scale that a full reload beats incremental
     * patch logic.
     */
    public void refresh() {
        List<ModelCatalogEntry> active = entries.findByStatus(CatalogStatus.ACTIVE);
        Map<String, ModelInfo> next = new HashMap<>(active.size() * 2);
        for (ModelCatalogEntry e : active) {
            ModelInfo info = toModelInfo(e);
            ModelInfo prior = next.put(info.modelName(), info);
            if (prior != null) {
                // model_catalog.model_name is UNIQUE, so this would be a
                // schema/seed bug — fail fast rather than silently shadow.
                throw new IllegalStateException(
                        "duplicate catalog entry for model '" + info.modelName()
                                + "': " + prior.provider() + " vs " + info.provider());
            }
        }
        this.catalog = Map.copyOf(next);
        log.info("ModelCatalog refreshed: {} ACTIVE models", catalog.size());
    }

    public Optional<ModelInfo> find(String modelName) {
        return Optional.ofNullable(catalog.get(modelName));
    }

    /** Read-only snapshot of every registered model, sorted by name. */
    public List<ModelInfo> all() {
        return catalog.values().stream()
                .sorted(java.util.Comparator.comparing(ModelInfo::modelName))
                .toList();
    }

    private static ModelInfo toModelInfo(ModelCatalogEntry e) {
        return new ModelInfo(
                e.getModelName(),
                e.getProvider(),
                e.getModality(),
                e.getInputMicrocentsPer1k(),
                e.getOutputMicrocentsPer1k(),
                e.getDefaultMaxOutputTokens(),
                e.getMarkupOverride(),
                e.getPerImageMicrocents(),
                e.getPerSecondMicrocents(),
                e.isRequiresAsync());
    }
}
