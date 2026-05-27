package ai.godance.service.pricing;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.ModelChangeProposal;
import ai.godance.domain.ModelPriceHistory;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.repository.ModelChangeProposalRepository;
import ai.godance.repository.ModelPriceHistoryRepository;

/**
 * All catalog-staging writes from the refresh job funnel through here so
 * each (history row, proposal row) pair commits in its own transaction —
 * a parse error on one observation does not roll back the rest of the run.
 *
 * <p>This bean is intentionally thin: it has no business logic of its
 * own. The decision of <em>which</em> proposal type to emit, and
 * whether to even propose, lives in {@link PricingRefreshService}.
 */
@Service
public class ProposalWriter {

    private final ModelPriceHistoryRepository history;
    private final ModelChangeProposalRepository proposals;

    public ProposalWriter(ModelPriceHistoryRepository history,
                          ModelChangeProposalRepository proposals) {
        this.history = history;
        this.proposals = proposals;
    }

    /** Always-write history row. The time series exists regardless of whether a proposal lands. */
    @Transactional
    public void recordHistory(ObservedPrice obs, Long oldValue) {
        ModelPriceHistory h = new ModelPriceHistory();
        h.setModelName(obs.godanceModelName());
        h.setField(obs.field());
        h.setOldValue(oldValue);
        h.setNewValue(obs.microcents() == Long.MIN_VALUE ? null : obs.microcents());
        h.setSource(obs.source());
        h.setSourceUrl(obs.sourceUrl());
        h.setObservedAt(obs.observedAt() != null ? obs.observedAt() : Instant.now());
        history.save(h);
    }

    @Transactional
    public ModelChangeProposal write(ProposalType type, ProposalStatus status, ObservedPrice obs,
                                     Map<String, Object> payload) {
        ModelChangeProposal p = new ModelChangeProposal();
        p.setType(type);
        p.setStatus(status);
        p.setModelName(obs.godanceModelName());
        p.setProvider(obs.provider());
        p.setModality(obs.modality());
        p.setPayload(payload);
        p.setSource(obs.source());
        p.setSourceUrl(obs.sourceUrl());
        p.setDetectedAt(obs.observedAt() != null ? obs.observedAt() : Instant.now());
        return proposals.save(p);
    }

    /** Convenience: build the canonical payload Map for a single-field price move. */
    public static Map<String, Object> priceChangePayload(ObservedPrice obs, Long oldValue) {
        Map<String, Object> m = new HashMap<>();
        m.put("field",        obs.field().asColumnKey());
        m.put("old_value",    oldValue);
        m.put("new_value",    obs.microcents());
        m.put("raw_usd",      obs.rawUsd() == null ? null : obs.rawUsd().toPlainString());
        m.put("source",       obs.source());
        m.put("source_url",   obs.sourceUrl());
        return m;
    }

    public static Map<String, Object> discoveryPayload(ObservedPrice obs) {
        Map<String, Object> m = new HashMap<>();
        m.put("source_model_id", obs.sourceModelId());
        m.put("field",           obs.field().asColumnKey());
        m.put("new_value",       obs.microcents() == Long.MIN_VALUE ? null : obs.microcents());
        m.put("raw_usd",         obs.rawUsd() == null ? null : obs.rawUsd().toPlainString());
        m.put("provider",        obs.provider() == null ? null : obs.provider().name());
        m.put("modality",        obs.modality() == null ? null : obs.modality().name());
        m.put("source",          obs.source());
        m.put("source_url",      obs.sourceUrl());
        return m;
    }
}
