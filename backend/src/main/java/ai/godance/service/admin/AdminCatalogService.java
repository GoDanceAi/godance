package ai.godance.service.admin;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.BillingProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CatalogStatus;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.repository.ModelPriceHistoryRepository;
import ai.godance.service.AuditLogService;

/**
 * Admin console catalog management: full-catalog listing (including
 * non-ACTIVE rows the public {@code /v1/models} hides), the
 * enable/disable + markup-override PATCH, and the per-model price
 * history read. Every mutation writes an {@code admin.catalog.updated}
 * audit event and refreshes the in-memory {@link ModelCatalog} snapshot
 * so pricing takes effect without a restart.
 *
 * <p>"Disable" maps to {@link CatalogStatus#DEPRECATED} — the only
 * non-billable status that means "operator turned it off" rather than
 * "pending approval" ({@code STAGED}) or "seen in a feed"
 * ({@code DISCOVERED}). Re-enabling flips back to {@code ACTIVE}.
 */
@Service
public class AdminCatalogService {

    private final ModelCatalogEntryRepository catalog;
    private final ModelPriceHistoryRepository history;
    private final ModelCatalog modelCatalog;
    private final BillingProperties billing;
    private final AuditLogService audit;

    public AdminCatalogService(ModelCatalogEntryRepository catalog,
                               ModelPriceHistoryRepository history,
                               ModelCatalog modelCatalog,
                               BillingProperties billing,
                               AuditLogService audit) {
        this.catalog = catalog;
        this.history = history;
        this.modelCatalog = modelCatalog;
        this.billing = billing;
        this.audit = audit;
    }

    public record CatalogModelView(
            Long id,
            String modelName,
            String provider,
            String modality,
            String status,
            boolean enabled,
            long inputMicrocentsPer1k,
            long outputMicrocentsPer1k,
            long perImageMicrocents,
            long perSecondMicrocents,
            Double markupOverride,
            double effectiveMarkupPercent,
            boolean requiresAsync,
            String costSource,
            Instant lastVerifiedAt,
            Instant updatedAt
    ) { }

    @Transactional(readOnly = true)
    public List<CatalogModelView> listModels() {
        return catalog.findAll().stream()
                .sorted(java.util.Comparator.comparing(ModelCatalogEntry::getModelName))
                .map(this::toView)
                .toList();
    }

    public record PriceHistoryView(
            Long id,
            String field,
            Long oldValue,
            Long newValue,
            String source,
            String sourceUrl,
            Instant observedAt
    ) { }

    @Transactional(readOnly = true)
    public List<PriceHistoryView> priceHistory(Long modelId) {
        ModelCatalogEntry entry = requireEntry(modelId);
        return history.findByModelNameOrderByObservedAtDesc(entry.getModelName()).stream()
                .map(h -> new PriceHistoryView(h.getId(),
                        h.getField() == null ? null : h.getField().name(),
                        h.getOldValue(), h.getNewValue(), h.getSource(), h.getSourceUrl(),
                        h.getObservedAt()))
                .toList();
    }

    /**
     * Apply {@code enabled} and/or {@code markupPercent}. Both optional,
     * at least one required. {@code markupPercent} sets the per-model
     * override (the tier default from {@link BillingProperties} keeps
     * applying to models without one).
     */
    @Transactional
    public CatalogModelView updateModel(Long modelId, Boolean enabled, Double markupPercent,
                                        String adminEmail) {
        if (enabled == null && markupPercent == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "at least one of enabled, markupPercent is required");
        }
        if (markupPercent != null && (markupPercent < 0 || markupPercent > 1000)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "markupPercent must be between 0 and 1000");
        }
        ModelCatalogEntry entry = requireEntry(modelId);

        Map<String, Object> changes = new HashMap<>();
        if (enabled != null) {
            CatalogStatus oldStatus = entry.getStatus();
            CatalogStatus newStatus = enabled ? CatalogStatus.ACTIVE : CatalogStatus.DEPRECATED;
            if (oldStatus != newStatus) {
                entry.setStatus(newStatus);
                changes.put("status", Map.of("old", oldStatus.name(), "new", newStatus.name()));
            }
        }
        if (markupPercent != null) {
            Double old = entry.getMarkupOverride();
            if (!markupPercent.equals(old)) {
                entry.setMarkupOverride(markupPercent);
                Map<String, Object> change = new HashMap<>();
                change.put("old", old);
                change.put("new", markupPercent);
                changes.put("markupOverride", change);
            }
        }
        if (!changes.isEmpty()) {
            catalog.save(entry);
            Map<String, Object> detail = new HashMap<>();
            detail.put("adminEmail", adminEmail);
            detail.put("modelName", entry.getModelName());
            detail.put("modelId", entry.getId());
            detail.put("changes", changes);
            audit.record(AuditLogService.EVT_ADMIN_CATALOG_UPDATED, AuditOutcome.SUCCESS,
                    null, null, detail);
            // Republish the in-memory snapshot so gateway pricing/dispatch
            // reflects the change immediately.
            modelCatalog.refresh();
        }
        return toView(entry);
    }

    private ModelCatalogEntry requireEntry(Long modelId) {
        return catalog.findById(modelId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "model " + modelId + " not found"));
    }

    private CatalogModelView toView(ModelCatalogEntry e) {
        return new CatalogModelView(
                e.getId(), e.getModelName(),
                e.getProvider() == null ? null : e.getProvider().name(),
                e.getModality() == null ? null : e.getModality().name(),
                e.getStatus().name(),
                e.getStatus() == CatalogStatus.ACTIVE,
                e.getInputMicrocentsPer1k(), e.getOutputMicrocentsPer1k(),
                e.getPerImageMicrocents(), e.getPerSecondMicrocents(),
                e.getMarkupOverride(),
                effectiveMarkup(e),
                e.isRequiresAsync(), e.getCostSource(), e.getLastVerifiedAt(), e.getUpdatedAt());
    }

    /** Mirrors {@link ai.godance.gateway.CostCalculator}'s markup resolution. */
    private double effectiveMarkup(ModelCatalogEntry e) {
        if (e.getMarkupOverride() != null) {
            return e.getMarkupOverride();
        }
        return switch (e.getModality()) {
            case TEXT, EMBEDDING, AUDIO_STT, AUDIO_TTS -> billing.textMarkupPercent();
            case IMAGE -> billing.imageMarkupPercent();
            case VIDEO -> billing.videoMarkupPercent();
        };
    }
}
