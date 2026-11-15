package ai.godance.service;

import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.stereotype.Component;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.InputsProperties;
import ai.godance.domain.CallOutcome;
import ai.godance.domain.Modality;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.ChatDtos.ChatMessage;
import ai.godance.gateway.ChatDtos.ChatRequest;
import ai.godance.gateway.ChatDtos.ChatResponse;
import ai.godance.gateway.ChatDtos.ImagePart;
import ai.godance.gateway.ChatDtos.TextPart;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.ProviderAdapter;
import ai.godance.gateway.ProviderGateway;
import ai.godance.repository.UsageRecordRepository;

/**
 * The person check for uploaded images (round 11 brief 02), which brief 01
 * left as an interface: one image and a fixed yes-or-no question to the
 * vision chat model named by {@code godance.inputs.check-model}.
 *
 * <p><b>It fails closed.</b> Only an answer that is exactly "yes" or "no"
 * counts. No model configured, a model that is gone or cannot see, a region
 * that may not use it, an error, a timeout, an empty answer, a refusal, a
 * sentence: each is {@link Verdict#UNAVAILABLE}, which refuses the upload
 * while people are not allowed.
 *
 * <p><b>The call is Godance's own.</b> The uploader is never charged: no
 * ledger row is written. Each call leaves a usage record under the
 * uploader's account with a cost of zero and the provider's cost filled
 * in, so what the check costs the platform can be totalled and its failures
 * count towards the provider's health like any other call. There was no
 * existing pattern for internal calls; this is it.
 *
 * <p><b>The regional allowlist applies.</b> The call goes through
 * {@link ProviderGateway#dispatch} like every other. A check model the
 * region may not use, or one that is not an active chat model that accepts
 * images, is a configuration error: the application does not start.
 *
 * <p>The image is sent to the check model and nowhere else. It is not
 * logged, and neither is the model's answer beyond the verdict.
 */
@Component
public class VisionPersonCheck implements InputPersonCheck, SmartInitializingSingleton {

    private static final Logger log = LoggerFactory.getLogger(VisionPersonCheck.class);

    /** The longest the check waits for the model before it counts as unavailable. */
    static final Duration TIMEOUT = Duration.ofSeconds(15);
    /** Room for one word. */
    static final int MAX_TOKENS = 5;

    /** The question, fixed: nothing a user sends is ever added to it. */
    static final String INSTRUCTION = "Is any person, or any part of a person, visible in this image? "
            + "A face, a body, a hand or a silhouette counts, and so does a person shown in a photo, "
            + "a screen or a poster inside the image. Answer with one word only: yes or no.";

    private final InputsProperties props;
    private final ModelCatalog catalog;
    private final ProviderGateway gateway;
    private final UsageRecordRepository usage;
    private final Duration timeout;

    @org.springframework.beans.factory.annotation.Autowired
    public VisionPersonCheck(InputsProperties props, ModelCatalog catalog, ProviderGateway gateway,
                             UsageRecordRepository usage) {
        this(props, catalog, gateway, usage, TIMEOUT);
    }

    /** With the time the check waits for the model, for a test that should not wait {@link #TIMEOUT}. */
    VisionPersonCheck(InputsProperties props, ModelCatalog catalog, ProviderGateway gateway,
                      UsageRecordRepository usage, Duration timeout) {
        this.props = props;
        this.catalog = catalog;
        this.gateway = gateway;
        this.usage = usage;
        this.timeout = timeout;
    }

    /**
     * Start-up check of {@code godance.inputs.check-model}. Empty is fine:
     * then there is no check. Anything else must be usable here.
     */
    @Override
    public void afterSingletonsInstantiated() {
        if (!props.hasCheckModel()) return;
        String name = props.checkModel();
        ModelInfo model = catalog.find(name).orElseThrow(() -> new IllegalStateException(
                "godance.inputs.check-model names \"" + name + "\", which is not an active model in the catalog"));
        if (model.modality() != Modality.TEXT || !acceptsImages(model)) {
            throw new IllegalStateException("godance.inputs.check-model must be a chat model that accepts images; \""
                    + name + "\" is not one (see capabilities.inputs.images on GET /v1/models)");
        }
        try {
            gateway.dispatch(model);
        } catch (ResponseStatusException denied) {
            throw new IllegalStateException("godance.inputs.check-model \"" + name + "\" is served by "
                    + model.provider() + ", which this region (" + gateway.region() + ") may not use");
        }
        log.info("Input person check: {} ({})", name, model.provider());
    }

    @Override
    public Verdict check(UUID userId, byte[] image, String contentType) {
        if (!props.hasCheckModel()) return Verdict.UNAVAILABLE;
        ModelInfo model = catalog.find(props.checkModel()).orElse(null);
        if (model == null || model.modality() != Modality.TEXT || !acceptsImages(model)) {
            log.warn("Input person check: the check model is no longer an active chat model that accepts images");
            return Verdict.UNAVAILABLE;
        }
        ProviderAdapter adapter;
        try {
            adapter = gateway.dispatch(model);
        } catch (ResponseStatusException denied) {
            log.warn("Input person check: {} may not be used in this region", model.provider());
            return Verdict.UNAVAILABLE;
        }

        String requestId = "check_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
        ChatRequest request = new ChatRequest(model.modelName(),
                List.of(new ChatMessage("user", List.of(new TextPart(INSTRUCTION), new ImagePart(image, contentType, null)))),
                MAX_TOKENS, 0.0, false);
        long startNanos = System.nanoTime();
        ChatResponse response;
        try {
            response = adapter.chat(request).block(timeout);
            if (response == null) throw new IllegalStateException("the check model returned nothing");
        } catch (RuntimeException e) {
            // The exception's message can hold the provider's words; the class is enough here.
            log.warn("Input person check: the call failed ({})", e.getClass().getSimpleName());
            record(userId, model, requestId, 0, 0, elapsedMs(startNanos), outcomeOf(e));
            return Verdict.UNAVAILABLE;
        }
        record(userId, model, requestId, response.usage().promptTokens(), response.usage().completionTokens(),
                elapsedMs(startNanos), CallOutcome.OK);
        return read(response);
    }

    /** Only an answer that is exactly yes or no counts; anything else cannot be relied on. */
    static Verdict read(ChatResponse response) {
        if (response.choices() == null || response.choices().size() != 1) return Verdict.UNAVAILABLE;
        ChatMessage message = response.choices().get(0).message();
        String answer = message == null ? null : message.content();
        if (answer == null) return Verdict.UNAVAILABLE;
        String word = answer.strip().toLowerCase(Locale.ROOT);
        if (word.endsWith(".")) word = word.substring(0, word.length() - 1);
        return switch (word) {
            case "yes" -> Verdict.PERSON;
            case "no" -> Verdict.NO_PERSON;
            default -> Verdict.UNAVAILABLE;
        };
    }

    private boolean acceptsImages(ModelInfo model) {
        return catalog.metadata(model.modelName()).capabilities().inputs().images();
    }

    /**
     * The usage record of one check: charged to nobody (cost zero, and no
     * ledger row is ever written for it), with what it cost the platform.
     */
    private void record(UUID userId, ModelInfo model, String requestId, int inputTokens, int outputTokens,
                        int latencyMs, CallOutcome outcome) {
        try {
            long providerCostMicrocents =
                    model.inputMicrocentsPer1k() * inputTokens / 1000
                  + model.outputMicrocentsPer1k() * outputTokens / 1000;
            UsageRecord r = new UsageRecord();
            r.setUserId(userId);
            r.setModality(model.modality());
            r.setModelName(model.modelName());
            r.setProvider(model.provider());
            r.setInputTokens(inputTokens);
            r.setOutputTokens(outputTokens);
            r.setCostCents(0);
            r.setCostMicrocents(0L);
            r.setProviderCostCents((providerCostMicrocents + 5_000) / 10_000);
            r.setProviderCostMicrocents(providerCostMicrocents);
            r.setRegion(gateway.region() == null ? Region.LOCAL : gateway.region());
            r.setCacheHit(false);
            r.setFallbackUsed(false);
            r.setRequestId(requestId);
            r.setLatencyMs(latencyMs);
            r.setOutcome(outcome);
            usage.save(r);
        } catch (RuntimeException e) {
            // Bookkeeping never changes the verdict.
            log.warn("Input person check: could not record usage for {}: {}", requestId, e.getMessage());
        }
    }

    private static CallOutcome outcomeOf(Throwable err) {
        for (Throwable t = err; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof WebClientResponseException w && w.getStatusCode().value() == 429) {
                return CallOutcome.RATE_LIMITED;
            }
        }
        return CallOutcome.ERROR;
    }

    private static int elapsedMs(long startNanos) {
        long ms = (System.nanoTime() - startNanos) / 1_000_000L;
        return (int) Math.min(Integer.MAX_VALUE, Math.max(0L, ms));
    }
}
