package ai.godance.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
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
import ai.godance.routing.ResolvedRoute;
import ai.godance.routing.RouteResolver;
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
    private final RouteResolver routeResolver;

    public ChatCompletionService(ModelCatalog catalog, CostCalculator calculator,
                                 ProviderGateway gateway, CreditLedgerService ledger,
                                 UsageRecordRepository usage, GatewayProperties gatewayProps,
                                 ModelHealthService modelHealth, RouteResolver routeResolver) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.gateway = gateway;
        this.ledger = ledger;
        this.usage = usage;
        this.gatewayProps = gatewayProps;
        this.modelHealth = modelHealth;
        this.routeResolver = routeResolver;
    }

    public ChatResponse execute(UUID userId, UUID apiKeyId, ChatRequest request) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        int estimatedInputTokens = estimateInputTokens(request.messages());

        // Resolve routing first: a virtual id (godance-auto/...) becomes an
        // ordered list of concrete candidates; a direct model call is an
        // unchanged passthrough of [model] + fallbacksFor(model).
        ResolvedRoute route = routeResolver.resolve(request, estimatedInputTokens, gateway.region());
        List<String> attemptModels = new ArrayList<>(route.orderedCandidates());
        logRoutingDecision(requestId, route, attemptModels);

        // Estimate + pre-debit against the FIRST concrete candidate — the
        // virtual id itself is not in the catalog and would 404 the old way.
        ModelInfo primary = catalog.find(attemptModels.get(0)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown model: " + request.model()));

        long estimateCents = Math.max(1L,
                calculator.estimateInputCents(primary, estimatedInputTokens));

        if (!ledger.preDebit(userId, apiKeyId, estimateCents, requestId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "insufficient credit; top up via /v1/billing/topup");
        }

        // fallbackUsed means "not the first resolved candidate": request.model()
        // for a direct call, the routed primary for a virtual id (the virtual
        // id itself is never an attempt). Read from the route, not
        // attemptModels, so a health reorder still counts as a fallback, as
        // it did before routing.
        String firstChoice = route.orderedCandidates().get(0);

        reorderByHealth(attemptModels);

        RuntimeException lastError = null;
        for (int i = 0; i < attemptModels.size(); i++) {
            String modelName = attemptModels.get(i);
            boolean isFallback = !modelName.equals(firstChoice);

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
            ChatResponse response;
            try {
                response = adapter.chat(normalize(forAttempt, model))
                        .block(UPSTREAM_BLOCK_TIMEOUT);
                if (response == null) {
                    throw new IllegalStateException("upstream returned null response");
                }
            } catch (RuntimeException upstreamErr) {
                int latencyMs = elapsedMs(startNanos);
                CallOutcome outcome = classifyOutcome(upstreamErr);
                log.warn("Attempt {} ({}) failed for request {} [{}, {}ms]: {}",
                        i, modelName, requestId, outcome, latencyMs, upstreamErr.getMessage());
                recordFailure(userId, apiKeyId, model, requestId, isFallback, latencyMs, outcome);
                lastError = upstreamErr;
                continue;
            }

            // The upstream call succeeded and was paid for. A failure from here
            // on is ours, so it must not call another provider (spec risk R-07):
            // the caller still gets the completion. If settling fails, the
            // pre-debited estimate stands as the charge; the error log names
            // the request id for reconciliation.
            int latencyMs = elapsedMs(startNanos);
            try {
                long actualMicrocents = calculator.actualMicrocents(model,
                        response.usage().promptTokens(), response.usage().completionTokens());
                long billedCents = ledger.settle(userId, estimateCents, actualMicrocents, requestId);
                usage.save(buildUsageRecord(userId, apiKeyId, model, response, billedCents,
                        actualMicrocents, requestId, isFallback, latencyMs, CallOutcome.OK));
            } catch (RuntimeException settlementErr) {
                log.error("Settlement failed for request {} after a successful upstream call to {}; "
                        + "not retrying upstream: {}", requestId, modelName, settlementErr.getMessage(),
                        settlementErr);
            }
            return response;
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
                                         ChatResponse response, long billedCents,
                                         long costMicrocents, String requestId,
                                         boolean fallbackUsed, int latencyMs,
                                         CallOutcome outcome) {
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
        r.setCostCents(billedCents);
        r.setCostMicrocents(costMicrocents);
        r.setProviderCostCents(providerCostCents);
        r.setProviderCostMicrocents(providerCostMicrocents);
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
     * Margin-attribution audit line for routed (virtual) calls. Keyed by
     * {@code requestId} so it joins to the {@link UsageRecord} written for
     * whichever concrete model actually served — that join yields the
     * spread between what the caller paid for the virtual id and the served
     * model's {@code providerCostCents} (Phase 1 exit gate; the dedicated
     * routed_policy/routed_rule columns are Phase 2). No-op for direct calls.
     */
    private void logRoutingDecision(String requestId, ResolvedRoute route, List<String> attemptModels) {
        if (route.fromVirtual()) {
            log.info("Routing decision: requestId={} virtualId={} rule={} candidates={}",
                    requestId, route.policyId(), route.matchedRule(), attemptModels);
        }
    }

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
     * final chunk. Client disconnect, a mid-stream upstream error, or every
     * attempt failing triggers a full refund.
     *
     * <p>Falls through the candidate list like {@link #execute}, after the
     * same {@link #reorderByHealth}: an attempt that fails before relaying
     * its first chunk (dispatch rejection, model not in the catalog, or an
     * upstream error) moves on to the next candidate. Once a chunk has
     * reached the client the response belongs to that model, so a later
     * error ends the stream rather than splicing in a second model's output.
     */
    public Flux<ChatChunk> executeStreaming(UUID userId, UUID apiKeyId, ChatRequest request) {
        String requestId = "req_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        int estimatedInputTokens = estimateInputTokens(request.messages());

        // Resolve routing first (same as execute()).
        ResolvedRoute route = routeResolver.resolve(request, estimatedInputTokens, gateway.region());
        List<String> attemptModels = new ArrayList<>(route.orderedCandidates());
        logRoutingDecision(requestId, route, attemptModels);

        ModelInfo primary = catalog.find(attemptModels.get(0)).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown model: " + request.model()));

        long estimateCents = Math.max(1L,
                calculator.estimateInputCents(primary, estimatedInputTokens));

        boolean debited;
        try {
            debited = ledger.preDebit(userId, apiKeyId, estimateCents, requestId);
        } catch (ResponseStatusException capReached) {
            return Flux.error(capReached);
        }
        if (!debited) {
            return Flux.error(new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "insufficient credit; top up via /v1/billing/topup"));
        }

        // Same fallbackUsed rule as execute(): not the first resolved candidate.
        String firstChoice = route.orderedCandidates().get(0);

        reorderByHealth(attemptModels);

        StreamState state = new StreamState(userId, apiKeyId, request, requestId,
                estimateCents, attemptModels, firstChoice);
        return streamAttempt(state, 0, null)
                .doFinally(signal -> dispatchPostStream(signal, state));
    }

    /** One attempt's model, once its upstream call has started. */
    private record StreamAttempt(ModelInfo model, boolean fallbackUsed, long startNanos) { }

    /** Per-request state shared by the attempts of one {@link #executeStreaming} call. */
    private static final class StreamState {
        final UUID userId;
        final UUID apiKeyId;
        final ChatRequest request;
        final String requestId;
        final long estimateCents;
        final List<String> attempts;
        final String firstChoice;
        /** The attempt in flight, or the last one to run. Null until an upstream call starts. */
        final AtomicReference<StreamAttempt> current = new AtomicReference<>();
        /** Set on the first chunk relayed to the client; from then on there is no fall-through. */
        final AtomicBoolean relayed = new AtomicBoolean();
        final AtomicReference<Usage> usage = new AtomicReference<>();

        StreamState(UUID userId, UUID apiKeyId, ChatRequest request, String requestId,
                    long estimateCents, List<String> attempts, String firstChoice) {
            this.userId = userId;
            this.apiKeyId = apiKeyId;
            this.request = request;
            this.requestId = requestId;
            this.estimateCents = estimateCents;
            this.attempts = List.copyOf(attempts);
            this.firstChoice = firstChoice;
        }
    }

    /**
     * Attempt {@code i}, falling through to {@code i + 1} on a failure
     * before its first chunk — the streamed form of the {@link #execute}
     * loop. Dispatch rejections and catalog misses are skipped without an
     * upstream call or UsageRecord; an upstream error writes the same
     * failure UsageRecord as {@link #execute} before the next attempt.
     */
    private Flux<ChatChunk> streamAttempt(StreamState s, int i, RuntimeException lastError) {
        if (i >= s.attempts.size()) {
            // Exhausted all attempts; dispatchPostStream refunds the estimate.
            if (lastError instanceof ResponseStatusException rse) {
                return Flux.error(rse);
            }
            return Flux.error(new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "all providers failed: "
                            + (lastError == null ? "no eligible attempt" : lastError.getMessage())));
        }

        String modelName = s.attempts.get(i);
        boolean isFallback = !modelName.equals(s.firstChoice);

        ModelInfo model = catalog.find(modelName).orElse(null);
        if (model == null) {
            log.warn("Fallback model '{}' not in catalog; skipping (request {})",
                    modelName, s.requestId);
            return streamAttempt(s, i + 1, lastError);
        }

        ProviderAdapter adapter;
        try {
            adapter = gateway.dispatch(model);
        } catch (ResponseStatusException denied) {
            // Allowlist denied this option in the current region — try next.
            log.info("Dispatch rejected for '{}' ({}); trying next attempt",
                    modelName, denied.getReason());
            return streamAttempt(s, i + 1, denied);
        }

        // Rewrite the upstream request to the concrete model name so providers
        // never see the virtual id. No-op for a direct call's primary.
        ChatRequest forStream = withModel(s.request, model.modelName());
        return Flux.defer(() -> {
                    s.current.set(new StreamAttempt(model, isFallback, System.nanoTime()));
                    return adapter.chatStream(normalize(forStream, model));
                })
                .doOnNext(chunk -> {
                    s.relayed.set(true);
                    if (chunk.usage() != null) s.usage.set(chunk.usage());
                })
                .onErrorResume(err -> !s.relayed.get(), err -> {
                    int latencyMs = elapsedMs(s.current.get().startNanos());
                    CallOutcome outcome = classifyOutcome(err);
                    log.warn("Stream attempt {} ({}) failed before its first chunk for request {} [{}, {}ms]: {}",
                            i, modelName, s.requestId, outcome, latencyMs, err.getMessage());
                    RuntimeException failure = err instanceof RuntimeException re
                            ? re : new IllegalStateException(err);
                    // recordFailure blocks on JPA, so keep it off the stream thread.
                    return Mono.fromRunnable(() -> recordFailure(s.userId, s.apiKeyId, model,
                                    s.requestId, isFallback, latencyMs, outcome))
                            .subscribeOn(Schedulers.boundedElastic())
                            .thenMany(Flux.defer(() -> streamAttempt(s, i + 1, failure)));
                });
    }

    /**
     * Post-stream reconcile, run once per request. Runs on a bounded-elastic
     * worker so the Reactor-stream thread (which doesn't tolerate blocking
     * JPA calls) stays clean. We tolerate one-off scheduler latency here; the
     * client has already received the final chunk by this point.
     */
    private void dispatchPostStream(SignalType signal, StreamState s) {
        Mono.fromRunnable(() -> {
            StreamAttempt served = s.current.get();
            Usage usage = s.usage.get();
            try {
                if (signal == SignalType.ON_COMPLETE && usage != null) {
                    long actualMicrocents = calculator.actualMicrocents(served.model(),
                            usage.promptTokens(), usage.completionTokens());
                    long billedCents = ledger.settle(s.userId, s.estimateCents, actualMicrocents, s.requestId);
                    this.usage.save(buildStreamedUsageRecord(
                            s.userId, s.apiKeyId, served.model(), usage, billedCents, actualMicrocents,
                            s.requestId, served.fallbackUsed(), elapsedMs(served.startNanos()), CallOutcome.OK));
                } else if (signal == SignalType.ON_COMPLETE) {
                    // Stream completed but provider never emitted a usage block.
                    // Keep the pre-debit as the recorded cost; write a UsageRecord
                    // with zero token counts so the audit trail isn't blank.
                    log.warn("Stream {} completed without usage block; keeping pre-debit", s.requestId);
                    this.usage.save(buildStreamedUsageRecord(
                            s.userId, s.apiKeyId, served.model(), new Usage(0, 0, 0), s.estimateCents,
                            s.estimateCents * CreditLedgerService.MICROCENTS_PER_CENT,
                            s.requestId, served.fallbackUsed(), elapsedMs(served.startNanos()),
                            CallOutcome.OK));
                } else if (signal == SignalType.ON_ERROR && s.relayed.get()) {
                    // Upstream failed mid-stream — full refund, plus a failure
                    // UsageRecord so the outage shows up in provider health.
                    ledger.refundEstimate(s.userId, s.estimateCents, s.requestId,
                            "stream signal " + signal);
                    recordFailure(s.userId, s.apiKeyId, served.model(), s.requestId,
                            served.fallbackUsed(), elapsedMs(served.startNanos()), CallOutcome.ERROR);
                } else if (signal == SignalType.ON_ERROR) {
                    // Every attempt failed before its first chunk; each upstream
                    // failure already wrote its own UsageRecord.
                    ledger.refundEstimate(s.userId, s.estimateCents, s.requestId,
                            "all providers failed for primary " + s.request.model());
                } else {
                    // CANCEL — client disconnected. Full refund; not a provider
                    // fault, so no health signal.
                    ledger.refundEstimate(s.userId, s.estimateCents, s.requestId,
                            "stream signal " + signal);
                }
            } catch (RuntimeException e) {
                log.warn("Post-stream reconcile failed for request {}: {}",
                        s.requestId, e.getMessage());
            }
        }).subscribeOn(Schedulers.boundedElastic()).subscribe();
    }

    private UsageRecord buildStreamedUsageRecord(UUID userId, UUID apiKeyId, ModelInfo model, Usage u,
                                                 long billedCents, long costMicrocents,
                                                 String requestId, boolean fallbackUsed,
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
        r.setCostCents(billedCents);
        r.setCostMicrocents(costMicrocents);
        r.setProviderCostCents(providerCostCents);
        r.setProviderCostMicrocents(providerCostMicrocents);
        r.setRegion(asDomainRegion(gateway.region()));
        r.setCacheHit(false);
        r.setFallbackUsed(fallbackUsed);
        r.setRequestId(requestId);
        r.setLatencyMs(latencyMs);
        r.setOutcome(outcome);
        return r;
    }
}
