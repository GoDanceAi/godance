package ai.godance.service.pricing;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.godance.config.PricingProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.ModelCatalogEntry;
import ai.godance.domain.PriceField;
import ai.godance.domain.ProposalStatus;
import ai.godance.domain.ProposalType;
import ai.godance.repository.ModelCatalogEntryRepository;
import ai.godance.service.AuditLogService;

/**
 * Orchestrates one daily refresh pass: pulls OpenRouter + LiteLLM +
 * optional LLM fallback, sanity-checks each {@link ObservedPrice},
 * writes a history row for <em>every</em> observation (the time series
 * is the contract), and stages proposals for the rest.
 *
 * <p>The job <b>never</b> writes a live billable price. Every change —
 * including discoveries and anomalies — lands in {@code
 * model_change_proposal} as PENDING (or ANOMALY) for a human to approve
 * via {@link ProposalApprovalService}.
 */
@Service
public class PricingRefreshService {

    public static final String EVT_PRICING_REFRESH_DIGEST = "pricing.refresh.digest";

    private static final Logger log = LoggerFactory.getLogger(PricingRefreshService.class);

    private final OpenRouterFeedClient openRouter;
    private final LiteLLMFeedClient liteLlm;
    private final LlmFallbackExtractor llmFallback;
    private final ModelCatalogEntryRepository catalog;
    private final ProposalWriter writer;
    private final SanityGuard sanityGuard;
    private final AuditLogService audit;
    private final PricingProperties props;

    public PricingRefreshService(OpenRouterFeedClient openRouter,
                                 LiteLLMFeedClient liteLlm,
                                 LlmFallbackExtractor llmFallback,
                                 ModelCatalogEntryRepository catalog,
                                 ProposalWriter writer,
                                 SanityGuard sanityGuard,
                                 AuditLogService audit,
                                 PricingProperties props) {
        this.openRouter = openRouter;
        this.liteLlm = liteLlm;
        this.llmFallback = llmFallback;
        this.catalog = catalog;
        this.writer = writer;
        this.sanityGuard = sanityGuard;
        this.audit = audit;
        this.props = props;
    }

    /** Single entry point — drives the whole pass synchronously, returns a digest. */
    public Digest runOnce() {
        List<ModelCatalogEntry> snapshot = catalog.findAll();
        ModelAliasResolver resolver = new ModelAliasResolver(
                ModelAliasResolver.asFeedMap(props.getAliasMap()), snapshot);

        List<ObservedPrice> observations = new ArrayList<>();
        try { observations.addAll(openRouter.fetch().block()); } catch (Exception e) {
            log.warn("OpenRouter feed unavailable: {}", e.getMessage());
        }
        try { observations.addAll(liteLlm.fetch().block()); } catch (Exception e) {
            log.warn("LiteLLM feed unavailable: {}", e.getMessage());
        }
        try { observations.addAll(llmFallback.extractAll().block()); } catch (Exception e) {
            log.warn("LLM fallback extractor failed: {}", e.getMessage());
        }

        Digest digest = new Digest();
        for (ObservedPrice raw : observations) {
            try {
                process(raw, resolver, snapshot, digest);
            } catch (Exception e) {
                log.warn("refresh skipped observation {}: {}", raw.sourceModelId(), e.getMessage());
                digest.errors++;
            }
        }
        emitDigestAudit(digest);
        return digest;
    }

    private void process(ObservedPrice raw, ModelAliasResolver resolver,
                         List<ModelCatalogEntry> snapshot, Digest digest) {
        ModelAliasResolver.Resolution resolution = resolver.resolve(raw.source(), raw.sourceModelId());
        ObservedPrice obs = withResolved(raw, resolution);

        Optional<ModelCatalogEntry> entryOpt = lookup(snapshot, obs.godanceModelName());
        Long prior = priorValueFor(entryOpt, obs.field());

        SanityGuard.Result guard = sanityGuard.check(obs, prior);
        if (guard.isAnomaly()) {
            // Always write history (even for anomalies) so the time series
            // captures the bad observation explicitly.
            writer.recordHistory(obs, prior);
            Map<String, Object> payload = ProposalWriter.discoveryPayload(obs);
            payload.put("anomaly_reason", guard.name());
            writer.write(decideTypeForAnomaly(resolution.kind()), ProposalStatus.ANOMALY, obs, payload);
            digest.anomalies++;
            return;
        }

        // Always-write history regardless of whether a proposal lands.
        writer.recordHistory(obs, prior);

        switch (resolution.kind()) {
            case KNOWN -> handleKnown(obs, entryOpt.orElseThrow(), prior, digest);
            case NEW_VERSION -> {
                writer.write(ProposalType.NEW_VERSION, ProposalStatus.PENDING, obs,
                        ProposalWriter.discoveryPayload(obs));
                digest.newVersions++;
            }
            case NEW_MODEL -> {
                writer.write(ProposalType.NEW_MODEL, ProposalStatus.PENDING, obs,
                        ProposalWriter.discoveryPayload(obs));
                digest.newModels++;
            }
        }
    }

    private void handleKnown(ObservedPrice obs, ModelCatalogEntry entry, Long prior, Digest digest) {
        if (prior == null || prior <= 0) {
            // Catalog row exists but the priced field has no prior value — propose as a price change.
            writer.write(ProposalType.PRICE_CHANGE, ProposalStatus.PENDING, obs,
                    ProposalWriter.priceChangePayload(obs, prior));
            digest.priceChanges++;
            return;
        }
        if (obs.microcents() == prior) {
            digest.noChange++;
            return;
        }
        double deltaPct = Math.abs((double)(obs.microcents() - prior)) * 100.0 / (double) prior;
        if (deltaPct <= props.getNoiseThresholdPct()) {
            digest.withinNoise++;
            return;
        }
        Map<String, Object> payload = ProposalWriter.priceChangePayload(obs, prior);
        payload.put("delta_pct", deltaPct);
        writer.write(ProposalType.PRICE_CHANGE, ProposalStatus.PENDING, obs, payload);
        digest.priceChanges++;
        digest.topMovers.add(new Mover(obs.godanceModelName(), obs.field().asColumnKey(),
                prior, obs.microcents(), deltaPct));
    }

    private static ObservedPrice withResolved(ObservedPrice raw, ModelAliasResolver.Resolution r) {
        return new ObservedPrice(
                raw.sourceModelId(),
                r.godanceModelName(),
                r.provider() != null ? r.provider() : raw.provider(),
                raw.modality(),
                raw.field(),
                raw.microcents(),
                raw.rawUsd(),
                raw.source(),
                raw.sourceUrl(),
                raw.observedAt());
    }

    private static Optional<ModelCatalogEntry> lookup(List<ModelCatalogEntry> snapshot, String name) {
        return snapshot.stream().filter(e -> e.getModelName().equals(name)).findFirst();
    }

    private static Long priorValueFor(Optional<ModelCatalogEntry> entry, PriceField field) {
        if (entry.isEmpty()) return null;
        ModelCatalogEntry e = entry.get();
        return switch (field) {
            case INPUT      -> e.getInputMicrocentsPer1k();
            case OUTPUT     -> e.getOutputMicrocentsPer1k();
            case PER_IMAGE  -> e.getPerImageMicrocents();
            case PER_SECOND -> e.getPerSecondMicrocents();
        };
    }

    private static ProposalType decideTypeForAnomaly(ModelAliasResolver.Resolution.Kind kind) {
        return switch (kind) {
            case KNOWN       -> ProposalType.PRICE_CHANGE;
            case NEW_VERSION -> ProposalType.NEW_VERSION;
            case NEW_MODEL   -> ProposalType.NEW_MODEL;
        };
    }

    private void emitDigestAudit(Digest d) {
        Map<String, Object> detail = new HashMap<>();
        detail.put("price_changes", d.priceChanges);
        detail.put("new_versions",  d.newVersions);
        detail.put("new_models",    d.newModels);
        detail.put("anomalies",     d.anomalies);
        detail.put("within_noise",  d.withinNoise);
        detail.put("no_change",     d.noChange);
        detail.put("errors",        d.errors);
        d.topMovers.sort(Comparator.comparingDouble((Mover m) -> Math.abs(m.deltaPct())).reversed());
        List<Map<String, Object>> top = new ArrayList<>();
        for (int i = 0; i < Math.min(d.topMovers.size(), 5); i++) {
            Mover m = d.topMovers.get(i);
            top.add(Map.of(
                    "model",     m.modelName(),
                    "field",     m.field(),
                    "old_value", m.oldValue(),
                    "new_value", m.newValue(),
                    "delta_pct", m.deltaPct()));
        }
        detail.put("top_movers", top);
        audit.record(EVT_PRICING_REFRESH_DIGEST, AuditOutcome.SUCCESS, null, null, detail);
        log.info("PricingRefresh digest: price_changes={} new_versions={} new_models={} anomalies={} within_noise={} no_change={} errors={}",
                d.priceChanges, d.newVersions, d.newModels, d.anomalies, d.withinNoise, d.noChange, d.errors);
    }

    /** Aggregated per-run counts + top-movers. Public so tests and the digest emit can read it. */
    public static class Digest {
        public int priceChanges;
        public int newVersions;
        public int newModels;
        public int anomalies;
        public int withinNoise;
        public int noChange;
        public int errors;
        public final List<Mover> topMovers = new ArrayList<>();
    }

    public record Mover(String modelName, String field, long oldValue, long newValue, double deltaPct) {}
}
