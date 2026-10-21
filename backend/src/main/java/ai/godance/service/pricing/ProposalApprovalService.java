package ai.godance.service.pricing;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.CatalogStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.ModelPriceHistory;
import ai.godance.domain.PriceField;
import ai.godance.domain.Provider;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.repository.ModelPriceHistoryRepository;
import ai.godance.service.AuditLogService;

/**
 * Applies (or rejects) a staged {@link ModelChangeProposal}. The only
 * code path that ever mutates a live billable {@code model_catalog} row
 * outside the V11 seed.
 *
 * <p>Approval is idempotent: re-approving a non-PENDING proposal returns
 * its current state without double-applying. Approving a NEW_MODEL
 * proposal inserts the catalog row as {@link CatalogStatus#DISCOVERED}
 * (never ACTIVE) — the system has no adapter mapping yet, so the row
 * stays non-billable until an operator promotes it manually.
 */
@Service
public class ProposalApprovalService {

    public static final String EVT_PROPOSAL_APPROVED = "pricing.proposal.approved";
    public static final String EVT_PROPOSAL_REJECTED = "pricing.proposal.rejected";

    private static final Logger log = LoggerFactory.getLogger(ProposalApprovalService.class);

    private final ModelChangeProposalRepository proposals;
    private final ModelCatalogEntryRepository catalog;
    private final ModelPriceHistoryRepository history;
    private final ModelCatalog modelCatalog;
    private final AuditLogService audit;

    public ProposalApprovalService(ModelChangeProposalRepository proposals,
                                   ModelCatalogEntryRepository catalog,
                                   ModelPriceHistoryRepository history,
                                   ModelCatalog modelCatalog,
                                   AuditLogService audit) {
        this.proposals = proposals;
        this.catalog = catalog;
        this.history = history;
        this.modelCatalog = modelCatalog;
        this.audit = audit;
    }

    @Transactional
    public ModelChangeProposal approve(Long proposalId, String reviewedBy) {
        ModelChangeProposal p = proposals.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "proposal " + proposalId + " not found"));
        if (p.getStatus() != ProposalStatus.PENDING) {
            // Idempotent — return current state, do not re-apply.
            return p;
        }
        switch (p.getType()) {
            case PRICE_CHANGE -> applyPriceChange(p);
            case NEW_VERSION  -> applyNewVersion(p);
            case NEW_MODEL    -> applyNewModel(p);
        }
        p.setStatus(ProposalStatus.APPROVED);
        p.setReviewedBy(reviewedBy);
        p.setReviewedAt(Instant.now());
        ModelChangeProposal saved = proposals.save(p);
        // Refresh the in-memory map after any write to model_catalog.
        modelCatalog.refresh();
        audit.record(EVT_PROPOSAL_APPROVED, AuditOutcome.SUCCESS, null, null,
                Map.of("proposal_id", saved.getId(),
                        "type",       saved.getType().name(),
                        "model_name", saved.getModelName(),
                        "reviewed_by", reviewedBy == null ? "" : reviewedBy));
        log.info("Approved proposal id={} type={} model={}", saved.getId(), saved.getType(), saved.getModelName());
        return saved;
    }

    @Transactional
    public ModelChangeProposal reject(Long proposalId, String reviewedBy) {
        ModelChangeProposal p = proposals.findById(proposalId)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "proposal " + proposalId + " not found"));
        if (p.getStatus() != ProposalStatus.PENDING && p.getStatus() != ProposalStatus.ANOMALY) {
            return p;  // idempotent on closed states
        }
        p.setStatus(ProposalStatus.REJECTED);
        p.setReviewedBy(reviewedBy);
        p.setReviewedAt(Instant.now());
        ModelChangeProposal saved = proposals.save(p);
        audit.record(EVT_PROPOSAL_REJECTED, AuditOutcome.SUCCESS, null, null,
                Map.of("proposal_id", saved.getId(),
                        "type",       saved.getType().name(),
                        "model_name", saved.getModelName(),
                        "reviewed_by", reviewedBy == null ? "" : reviewedBy));
        return saved;
    }

    private void applyPriceChange(ModelChangeProposal p) {
        ModelCatalogEntry entry = catalog.findByModelName(p.getModelName())
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                        "cannot apply PRICE_CHANGE: model " + p.getModelName() + " not in catalog"));
        Map<String, Object> payload = p.getPayload();
        PriceField field = PriceField.valueOf(((String) payload.get("field")).toUpperCase());
        long newValue = ((Number) payload.get("new_value")).longValue();
        Long oldValue = payload.get("old_value") == null
                ? null : ((Number) payload.get("old_value")).longValue();
        applyFieldToEntry(entry, field, newValue);
        entry.setLastVerifiedAt(Instant.now());
        catalog.save(entry);
        appendHistory(entry.getModelName(), field, oldValue, newValue,
                p.getSource(), p.getSourceUrl());
    }

    private void applyNewVersion(ModelChangeProposal p) {
        // Same effect as a PRICE_CHANGE that creates the catalog row from scratch.
        Map<String, Object> payload = p.getPayload();
        PriceField field = PriceField.valueOf(((String) payload.get("field")).toUpperCase());
        long newValue = ((Number) payload.get("new_value")).longValue();
        Provider provider = p.getProvider() != null
                ? p.getProvider()
                : (payload.get("provider") != null
                        ? Provider.valueOf((String) payload.get("provider")) : null);
        if (provider == null) {
            throw new ResponseStatusException(HttpStatus.UNPROCESSABLE_CONTENT,
                    "cannot apply NEW_VERSION without a Provider mapping");
        }
        Modality modality = p.getModality() != null
                ? p.getModality()
                : (payload.get("modality") != null
                        ? Modality.valueOf((String) payload.get("modality")) : Modality.TEXT);
        ModelCatalogEntry entry = new ModelCatalogEntry();
        entry.setModelName(p.getModelName());
        entry.setProvider(provider);
        entry.setModality(modality);
        entry.setStatus(CatalogStatus.ACTIVE);
        entry.setCostSource("proposal:" + p.getId());
        entry.setLastVerifiedAt(Instant.now());
        applyFieldToEntry(entry, field, newValue);
        catalog.save(entry);
        appendHistory(entry.getModelName(), field, null, newValue, p.getSource(), p.getSourceUrl());
    }

    private void applyNewModel(ModelChangeProposal p) {
        // NEW_MODEL is non-billable on approval — status=DISCOVERED, no
        // adapter mapping yet. An operator must promote to ACTIVE manually
        // (and add the adapter) before any chat/image/video call routes here.
        Map<String, Object> payload = p.getPayload();
        Provider provider = p.getProvider() != null
                ? p.getProvider()
                : (payload.get("provider") != null
                        ? Provider.valueOf((String) payload.get("provider")) : null);
        ModelCatalogEntry entry = new ModelCatalogEntry();
        entry.setModelName(p.getModelName());
        entry.setProvider(provider);
        entry.setModality(p.getModality() != null ? p.getModality() : Modality.TEXT);
        entry.setStatus(CatalogStatus.DISCOVERED);
        entry.setCostSource("proposal:" + p.getId());
        entry.setLastVerifiedAt(Instant.now());
        if (payload.get("field") != null && payload.get("new_value") != null) {
            PriceField field = PriceField.valueOf(((String) payload.get("field")).toUpperCase());
            long newValue = ((Number) payload.get("new_value")).longValue();
            applyFieldToEntry(entry, field, newValue);
            appendHistory(entry.getModelName(), field, null, newValue,
                    p.getSource(), p.getSourceUrl());
        }
        catalog.save(entry);
    }

    private static void applyFieldToEntry(ModelCatalogEntry entry, PriceField field, long value) {
        switch (field) {
            case INPUT      -> entry.setInputMicrocentsPer1k(value);
            case OUTPUT     -> entry.setOutputMicrocentsPer1k(value);
            case PER_IMAGE  -> entry.setPerImageMicrocents(value);
            case PER_SECOND -> entry.setPerSecondMicrocents(value);
        }
    }

    private void appendHistory(String modelName, PriceField field, Long oldValue, Long newValue,
                               String source, String sourceUrl) {
        ModelPriceHistory h = new ModelPriceHistory();
        h.setModelName(modelName);
        h.setField(field);
        h.setOldValue(oldValue);
        h.setNewValue(newValue);
        h.setSource(source == null ? "manual" : source);
        h.setSourceUrl(sourceUrl);
        h.setObservedAt(Instant.now());
        history.save(h);
    }
}
