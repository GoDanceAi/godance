package ai.godance.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.GatewayProperties;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.ChatDtos.ChatChunk;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ChatDtos.Usage;
import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.ProviderAdapter;
import ai.godance.gateway.ProviderGateway;
import ai.godance.repository.UsageRecordRepository;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.SignalType;
import reactor.core.scheduler.Schedulers;

/**
 * Orchestrates one chat-completion request:
 * <ol>
 *   <li>Resolve model from {@link ModelCatalog}; 404 if unknown.</li>
 *   <li>Estimate input cost (chars/3 heuristic — over-estimates ~33%
 *       so the refund-on-reconcile path is the common case).</li>
 *   <li>Pre-debit via {@link CreditLedgerService#preDebit}; 402 if
 *       insufficient.</li>
 *   <li>Resolve adapter via {@link ProviderGateway#dispatch} — fails
 *       closed with 503 on Alicloud regional policy violation.</li>
 *   <li>Block on the upstream provider call (we're on a bounded-elastic
 *       thread from the controller, so blocking is safe).</li>
 *   <li>On success: reconcile estimate vs actual (refund excess or
 *       extra-debit if output exceeded estimate), write a
 *       {@link UsageRecord}, return the response.</li>
 *   <li>On failure: full refund, surface as 502.</li>
 * </ol>
 */
@Service
public class ChatCompletionService {

    private static final Logger log = LoggerFactory.getLogger(ChatCompletionService.class);
    private static final Duration UPSTREAM_BLOCK_TIMEOUT = Duration.ofSeconds(60);
    private static final int DEFAULT_MAX_OUTPUT_TOKENS = 4096;

    private final ModelCatalog catalog;
    private final CostCalculator calculator;
    private final ProviderGateway gateway;
    private final CreditLedgerService ledger;
    private final UsageRecordRepository usage;
    private final GatewayProperties gatewayProps;
    private final ModelHealthService modelHealth;

    public ChatCompletionService(ModelCatalog catalog, CostCalculator calculator,
                                 ProviderGateway gateway, CreditLedgerService ledger,
                                 UsageRecordRepository usage, GatewayProperties gatewayProps,
                                 ModelHealthService modelHealth) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.gateway = gateway;
        this.ledger = ledger;
        this.usage = usage;
        this.gatewayProps = gatewayProps;
        this.modelHealth = modelHealth;
    }

    public ChatResponse execute(UUID userId, UUID apiKeyId, ChatRequest request) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        ModelInfo primary = catalog.find(request.model()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown model: " + request.model()));

        int estimatedInputTokens = estimateInputTokens(request.messages());
        long estimateCents = Math.max(1L,
                calculator.estimateInputCents(primary, estimatedInputTokens));

        if (!ledger.preDebit(userId, estimateCents, requestId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "insufficient credit; top up via /v1/billing/topup");
        }

        List<String> attemptModels = new ArrayList<>();
        attemptModels.add(request.model());
        attemptModels.addAll(gatewayProps.fallbacksFor(request.model()));
        reorderByHealth(attemptModels);

        RuntimeException lastError = null;
        for (int i = 0; i < attemptModels.size(); i++) {
            String modelName = attemptModels.get(i);
            boolean isFallback = !modelName.equals(request.model());

            ModelInfo model = catalog.find(modelName).orElse(null);
            if (model == null) {
                log.warn("Fallback model '{}' not in catalog; skipping (request {})",
                        modelName, requestId);
                continue;
            }

            ProviderAdapter adapter;
            try {
                adapter = gateway.dispatch(model);
            } catch (ResponseStatusException denied) {
                // Allowlist denied this option in the current region — try next.
                // Not an upstream call, so no UsageRecord / health signal.
                log.info("Dispatch rejected for '{}' ({}); trying next attempt",
                        modelName, denied.getReason());
                lastError = denied;
                continue;
            }

            ChatRequest forAttempt = withModel(request, model.modelName());
            long startNanos = System.nanoTime();
            try {
                ChatResponse response = adapter.chat(normalize(forAttempt, model))
                        .block(UPSTREAM_BLOCK_TIMEOUT);
                if (response == null) {
                    throw new IllegalStateException("upstream returned null response");
                }
                int latencyMs = elapsedMs(startNanos);

                long actualCents = calculator.actualCents(model,
                        response.usage().promptTokens(), response.usage().completionTokens());
                ledger.reconcile(userId, estimateCents, actualCents, requestId);
                usage.save(buildUsageRecord(userId, apiKeyId, model, response, actualCents,
                        requestId, isFallback, latencyMs, CallOutcome.OK));
                return response;
            } catch (RuntimeException upstreamErr) {
                int latencyMs = elapsedMs(startNanos);
                CallOutcome outcome = classifyOutcome(upstreamErr);
                log.warn("Attempt {} ({}) failed for request {} [{}, {}ms]: {}",
                        i, modelName, requestId, outcome, latencyMs, upstreamErr.getMessage());
                recordFailure(userId, apiKeyId, model, requestId, isFallback, latencyMs, outcome);
                lastError = upstreamErr;
            }
        }

        // Exhausted all attempts.
        ledger.refundEstimate(userId, estimateCents, requestId,
                "all providers failed for primary " + request.model());
        if (lastError instanceof ResponseStatusException rse) {
            throw rse;
        }
        throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                "all providers failed: "
                        + (lastError == null ? "no eligible attempt" : lastError.getMessage()));
    }

    private static ChatRequest withModel(ChatRequest req, String modelName) {
        if (req.model().equals(modelName)) return req;
        return new ChatRequest(modelName, req.messages(), req.maxTokens(),
                req.temperature(), req.stream());
    }

    /** chars/3 heuristic. Over-estimates by ~33% vs OpenAI's BPE ratio — refund covers the slack. */
    static int estimateInputTokens(List<ChatMessage> messages) {
        int chars = 0;
        for (ChatMessage m : messages) {
            chars += m.role() == null ? 0 : m.role().length();
            chars += m.content() == null ? 0 : m.content().length();
            chars += 10;    // per-message overhead (system framing tokens)
        }
        return Math.max(1, chars / 3);
    }

    /** Apply default max_tokens if caller didn't specify one. */
    private ChatRequest normalize(ChatRequest req, ModelInfo model) {
        int defaulted = model.defaultMaxOutputTokens() != null
                ? model.defaultMaxOutputTokens()
                : DEFAULT_MAX_OUTPUT_TOKENS;
        Integer maxTokens = req.maxTokens() == null ? defaulted : req.maxTokens();
        return new ChatRequest(req.model(), req.messages(), maxTokens,
                req.temperature(), req.stream());
    }

    private UsageRecord buildUsageRecord(UUID userId, UUID apiKeyId, ModelInfo model,
                                         ChatResponse response, long actualCents,
                                         String requestId, boolean fallbackUsed,
                                         int latencyMs, CallOutcome outcome) {
        long providerCostMicrocents =
                model.inputMicrocentsPer1k()  * response.usage().promptTokens()     / 1000
              + model.outputMicrocentsPer1k() * response.usage().completionTokens() / 1000;
        long providerCostCents = (providerCostMicrocents + 5_000) / 10_000;   // HALF_UP

        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setApiKeyId(apiKeyId);                   // non-null when the request used an API key
        r.setModality(model.modality());
        r.setModelName(model.modelName());
        r.setProvider(model.provider());
        r.setInputTokens(response.usage().promptTokens());
        r.setOutputTokens(response.usage().completionTokens());
        r.setCostCents(actualCents);
        r.setProviderCostCents(providerCostCents);
        r.setRegion(asDomainRegion(gateway.region()));
        r.setCacheHit(false);
        r.setFallbackUsed(fallbackUsed);
        r.setRequestId(requestId);
        r.setLatencyMs(latencyMs);
        r.setOutcome(outcome);
        return r;
    }

    private static Region asDomainRegion(Region r) {
        return r == null ? Region.LOCAL : r;
    }

    // ===== Observability =============================================

    /**
     * Stable-sort the attempt list so models currently flagged as degraded
     * (failure rate over the de-prefer threshold, see {@link ModelHealthService})
     * are tried last. A degraded model is de-preferred, never dropped — if
     * every healthy fallback also fails, the loop still falls through to it.
     * No-op when nothing (or everything) is degraded.
     */
    private void reorderByHealth(List<String> attemptModels) {
        if (attemptModels.size() < 2) return;
        Set<String> degraded = new HashSet<>();
        for (String m : attemptModels) {
            ModelInfo mi = catalog.find(m).orElse(null);
            if (mi != null && modelHealth.isModelDegraded(mi.provider(), mi.modelName())) {
                degraded.add(m);
            }
        }
        if (degraded.isEmpty() || degraded.size() == attemptModels.size()) return;
        attemptModels.sort(Comparator.comparingInt(m -> degraded.contains(m) ? 1 : 0));
        log.info("Outage-aware routing: de-preferred {} → attempt order {}",
                degraded, attemptModels);
    }

    /** Write a zero-cost {@link UsageRecord} for a failed upstream attempt. */
    private void recordFailure(UUID userId, UUID apiKeyId, ModelInfo model, String requestId,
                               boolean fallbackUsed, int latencyMs, CallOutcome outcome) {
        try {
            UsageRecord r = new UsageRecord();
            r.setUserId(userId);
            r.setApiKeyId(apiKeyId);            // non-null when the request used an API key
            r.setModality(model.modality());
            r.setModelName(model.modelName());
            r.setProvider(model.provider());
            r.setInputTokens(0);
            r.setOutputTokens(0);
            r.setCostCents(0);                 // the user is refunded; the failed call is free
            r.setProviderCostCents(0L);
            r.setRegion(asDomainRegion(gateway.region()));
            r.setCacheHit(false);
            r.setFallbackUsed(fallbackUsed);
            r.setRequestId(requestId);
            r.setLatencyMs(latencyMs);
            r.setOutcome(outcome);
            usage.save(r);
        } catch (RuntimeException e) {
            // Health bookkeeping must never mask the real upstream failure.
            log.warn("Could not persist failure UsageRecord for request {}: {}",
                    requestId, e.getMessage());
        }
    }

    /** RATE_LIMITED on an upstream HTTP 429, ERROR for every other failure. */
    private static CallOutcome classifyOutcome(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause()) {
            if (t instanceof WebClientResponseException w
                    && w.getStatusCode().value() == 429) {
                return CallOutcome.RATE_LIMITED;
            }
        }
        return CallOutcome.ERROR;
    }

    private static int elapsedMs(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, ms));
    }

    // ===== Streaming =================================================

    /**
     * Streamed equivalent of {@link #execute}. Pre-debit happens
     * synchronously up front (so 402 surfaces before bytes flow);
     * reconcile + UsageRecord write happen asynchronously after the
     * upstream stream completes, using the {@code usage} block on the
     * final chunk. Client disconnect or upstream error triggers a full
     * refund.
     */
    public Flux<ChatChunk> executeStreaming(UUID userId, UUID apiKeyId, ChatRequest request) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        ModelInfo model = catalog.find(request.model()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown model: " + request.model()));

        int estimatedInputTokens = estimateInputTokens(request.messages());
        long estimateCents = Math.max(1L,
                calculator.estimateInputCents(model, estimatedInputTokens));

        if (!ledger.preDebit(userId, estimateCents, requestId)) {
            return Flux.error(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "insufficient credit; top up via /v1/billing/topup"));
        }

        ProviderAdapter adapter;
        try {
            adapter = gateway.dispatch(model);
        } catch (RuntimeException e) {
            ledger.refundEstimate(userId, estimateCents, requestId, "gateway dispatch failed");
            return Flux.error(e);
        }

        AtomicReference<Usage> finalUsage = new AtomicReference<>();
        long startNanos = System.nanoTime();

        return adapter.chatStream(normalize(request, model))
                .doOnNext(chunk -> {
                    if (chunk.usage() != null) finalUsage.set(chunk.usage());
                })
                .doFinally(signal -> dispatchPostStream(
                        signal, userId, apiKeyId, model, estimateCents, requestId,
                        finalUsage.get(), startNanos));
    }

    /**
     * Post-stream reconcile. Runs on a bounded-elastic worker so the
     * Reactor-stream thread (which doesn't tolerate blocking JPA calls)
     * stays clean. We tolerate one-off scheduler latency here; the
     * client has already received the final chunk by this point.
     */
    private void dispatchPostStream(SignalType signal, UUID userId, UUID apiKeyId, ModelInfo model,
                                    long estimateCents, String requestId, Usage usage,
                                    long startNanos) {
        Mono.fromRunnable(() -> {
            int latencyMs = elapsedMs(startNanos);
            try {
                if (signal == SignalType.ON_COMPLETE && usage != null) {
                    long actualCents = calculator.actualCents(model,
                            usage.promptTokens(), usage.completionTokens());
                    ledger.reconcile(userId, estimateCents, actualCents, requestId);
                    this.usage.save(buildStreamedUsageRecord(
                            userId, apiKeyId, model, usage, actualCents, requestId,
                            latencyMs, CallOutcome.OK));
                } else if (signal == SignalType.ON_COMPLETE) {
                    // Stream completed but provider never emitted a usage block.
                    // Keep the pre-debit as the recorded cost; write a UsageRecord
                    // with zero token counts so the audit trail isn't blank.
                    log.warn("Stream {} completed without usage block; keeping pre-debit", requestId);
                    this.usage.save(buildStreamedUsageRecord(
                            userId, apiKeyId, model, new Usage(0, 0, 0), estimateCents, requestId,
                            latencyMs, CallOutcome.OK));
                } else if (signal == SignalType.ON_ERROR) {
                    // Upstream failed mid-stream — full refund, plus a failure
                    // UsageRecord so the outage shows up in provider health.
                    ledger.refundEstimate(userId, estimateCents, requestId,
                            "stream signal " + signal);
                    recordFailure(userId, apiKeyId, model, requestId, false, latencyMs, CallOutcome.ERROR);
                } else {
                    // CANCEL — client disconnected. Full refund; not a provider
                    // fault, so no health signal.
                    ledger.refundEstimate(userId, estimateCents, requestId,
                            "stream signal " + signal);
                }
            } catch (RuntimeException e) {
                log.warn("Post-stream reconcile failed for request {}: {}",
                        requestId, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private UsageRecord buildStreamedUsageRecord(UUID userId, UUID apiKeyId, ModelInfo model, Usage u,
                                                 long actualCents, String requestId,
                                                 int latencyMs, CallOutcome outcome) {
        long providerCostMicrocents =
                model.inputMicrocentsPer1k()  * u.promptTokens()     / 1000
              + model.outputMicrocentsPer1k() * u.completionTokens() / 1000;
        long providerCostCents = (providerCostMicrocents + 5_000) / 10_000;

        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setApiKeyId(apiKeyId);
        r.setModality(model.modality());
        r.setModelName(model.modelName());
        r.setProvider(model.provider());
        r.setInputTokens(u.promptTokens());
        r.setOutputTokens(u.completionTokens());
        r.setCostCents(actualCents);
        r.setProviderCostCents(providerCostCents);
        r.setRegion(asDomainRegion(gateway.region()));
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setRequestId(requestId);
        r.setLatencyMs(latencyMs);
        r.setOutcome(outcome);
        return r;
    }
}
