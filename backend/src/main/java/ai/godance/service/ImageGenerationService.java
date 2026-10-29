package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ImageDtos.GeneratedImage;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.ImageProviderAdapter;
import ai.godance.gateway.ImageProviderGateway;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.security.WebhookSigner;
import ai.godance.storage.ObjectStorageClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Orchestrates one image-generation request end-to-end:
 *
 * <ol>
 *   <li>Resolve model from catalog; 404 if unknown / wrong modality.</li>
 *   <li>Cost is per-image flat; pre-debit covers the whole job up front
 *       (image gen has no "estimated then reconciled" gap — cost is
 *       determined by image count, which is known at request time).</li>
 *   <li>Insert the {@link GenerationJob} row in {@code QUEUED} state in the
 *       same transaction as the pre-debit ({@link GenerationJobSettlement#accept}).</li>
 *   <li>Dispatch via {@link ImageProviderGateway} (Alicloud allowlist
 *       re-check happens here).</li>
 *   <li>Upstream {@code .block(timeout)} — synchronous from controller's
 *       view since we're already on a bounded-elastic worker.</li>
 *   <li>Upload bytes to our bucket; presigned URLs are returned.</li>
 *   <li>On success: {@code DONE} status + UsageRecord + optional webhook callback.</li>
 *   <li>On failure, including storing the result: refund + {@code FAILED} status.</li>
 * </ol>
 *
 * <p>Every status change goes through {@link GenerationJobSettlement}, so a
 * job ends exactly once even if {@link GenerationJobSweepJob} reaches it
 * first (spec risks R-03, R-05).
 */
@Service
public class ImageGenerationService {

    private static final Logger log = LoggerFactory.getLogger(ImageGenerationService.class);
    private static final Duration UPSTREAM_BLOCK_TIMEOUT = Duration.ofMinutes(6);  // covers Flux poll-timeout
    /** Longest a live worker can hold a job: the upstream wait plus time to store the result. */
    private static final Duration LEASE = UPSTREAM_BLOCK_TIMEOUT.plusMinutes(10);
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);

    private final ModelCatalog catalog;
    private final CostCalculator calculator;
    private final ImageProviderGateway gateway;
    private final GenerationJobSettlement settlement;
    private final ObjectStorageClient storage;
    private final ObjectMapper json;
    private final WebhookSigner webhookSigner;
    private final WebhookDestinations webhookDestinations;
    private final WebClient webhookClient;

    public ImageGenerationService(ModelCatalog catalog, CostCalculator calculator,
                                  ImageProviderGateway gateway, GenerationJobSettlement settlement,
                                  ObjectStorageClient storage, ObjectMapper json,
                                  WebhookSigner webhookSigner,
                                  WebhookDestinations webhookDestinations) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.gateway = gateway;
        this.settlement = settlement;
        this.storage = storage;
        this.json = json;
        this.webhookSigner = webhookSigner;
        this.webhookDestinations = webhookDestinations;
        this.webhookClient = webhookDestinations.client();
    }

    public record ImageResult(UUID jobId, List<UploadedImage> images, long costCents, long createdAt) { }
    public record UploadedImage(String url, String storageKey, String revisedPrompt) { }

    /**
     * Synchronous image gen: blocks on upstream + upload, returns the
     * fully-resolved URLs. Use for OpenAI-compat clients.
     */
    public ImageResult execute(UUID userId, UUID apiKeyId, ImageRequest request) {
        return executeInternal(userId, apiKeyId, request, null);
    }

    /**
     * Returns true if the requested model's {@code requiresAsync} flag is set —
     * the controller auto-promotes the request to async mode regardless of
     * whether the client opted in. Used for HQ image models that take too
     * long to safely block an HTTP request.
     */
    public boolean requiresAsyncFor(ImageRequest request) {
        return catalog.find(request.model())
                .map(ModelInfo::requiresAsync)
                .orElse(false);
    }

    /**
     * Async image gen: returns immediately with the {@link UUID} of a
     * {@code QUEUED} GenerationJob; processing continues on a
     * bounded-elastic worker. Client polls {@code GET /v1/jobs/{id}}
     * or receives a POST at {@code webhookUrl} on completion.
     */
    public UUID executeAsync(UUID userId, UUID apiKeyId, ImageRequest request, String webhookUrl) {
        webhookDestinations.requireAllowed(webhookUrl);   // before any charge (spec risk R-02)
        ModelInfo model = requireModel(request);
        GenerationJob job = acceptJob(userId, apiKeyId, model, request, webhookUrl);

        // Kick off async processing. The pre-debit and the job row are already
        // committed together; if this worker dies, GenerationJobSweepJob fails
        // and refunds the job once its lease runs out.
        Mono.fromRunnable(() -> runJob(job, userId, apiKeyId, model, request, webhookUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return job.getId();
    }

    private ImageResult executeInternal(UUID userId, UUID apiKeyId, ImageRequest request, String webhookUrl) {
        ModelInfo model = requireModel(request);
        GenerationJob job = acceptJob(userId, apiKeyId, model, request, webhookUrl);
        return runJob(job, userId, apiKeyId, model, request, webhookUrl);
    }

    /** Builds the job and accepts it: pre-debit and QUEUED row in one transaction, or 402. */
    private GenerationJob acceptJob(UUID userId, UUID apiKeyId, ModelInfo model, ImageRequest request,
                                    String webhookUrl) {
        int count = request.n() == null ? 1 : request.n();
        long cost = Math.max(1L, calculator.imageCostCents(model, count));
        GenerationJob job = newJob(userId, model, request, cost, newRequestId(), webhookUrl);
        if (!settlement.accept(job, apiKeyId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "insufficient credit; top up via /v1/billing/topup");
        }
        return job;
    }

    private ImageResult runJob(GenerationJob job, UUID userId, UUID apiKeyId, ModelInfo model,
                               ImageRequest request, String webhookUrl) {
        long cost = job.getEstimatedCostCents();
        ImageProviderAdapter adapter;
        try {
            adapter = gateway.dispatch(model);
        } catch (RuntimeException e) {
            settlement.fail(job, "gateway dispatch: " + e.getMessage(), "gateway dispatch failed");
            throw e;
        }

        if (!settlement.start(job)) {
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "image job expired before it started");
        }

        List<GeneratedImage> upstream;
        try {
            upstream = adapter.generate(request).block(UPSTREAM_BLOCK_TIMEOUT);
            if (upstream == null || upstream.isEmpty()) {
                throw new IllegalStateException("upstream returned no images");
            }
        } catch (RuntimeException e) {
            log.warn("Image upstream {} failed for job {}: {}",
                    model.provider(), job.getId(), e.getMessage());
            settlement.fail(job, "upstream: " + e.getMessage(), "image upstream failed");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                    "image provider error: " + e.getMessage());
        }

        // The provider has delivered. Storing the result and ending the job DONE
        // share one guard: a failure here ends the job FAILED and refunded, never
        // RUNNING with the pre-debit kept (spec risk R-05). The first image's URL
        // goes into output_url; the rest live under images/<jobId>/.
        List<UploadedImage> uploaded;
        boolean completed;
        try {
            uploaded = uploadAll(job.getId(), upstream);
            completed = settlement.complete(job, uploaded.isEmpty() ? null : uploaded.get(0).url(), cost,
                    buildUsageRecord(userId, apiKeyId, model, request, cost, job.getRequestId(), upstream.size()));
        } catch (RuntimeException e) {
            log.warn("Image job {} could not be finalised: {}", job.getId(), e.getMessage());
            settlement.fail(job, "finalize: " + e.getMessage(), "image result could not be stored");
            throw new ResponseStatusException(HttpStatus.BAD_GATEWAY, "image result could not be stored");
        }
        if (!completed) {
            // The sweep ended this job while it ran and has already refunded it.
            throw new ResponseStatusException(HttpStatus.GATEWAY_TIMEOUT,
                    "image job expired before it finished");
        }

        ImageResult result = new ImageResult(job.getId(), uploaded, cost,
                job.getCompletedAt().toEpochMilli() / 1000);
        if (webhookUrl != null && !webhookUrl.isBlank()) {
            fireWebhook(userId, webhookUrl, result);
        }
        return result;
    }

    private List<UploadedImage> uploadAll(UUID jobId, List<GeneratedImage> images) {
        List<UploadedImage> out = new ArrayList<>(images.size());
        for (int i = 0; i < images.size(); i++) {
            GeneratedImage g = images.get(i);
            String ext = mimeToExt(g.mimeType());
            String key = "images/" + jobId + "/" + i + "." + ext;
            storage.upload(key, g.bytes(), g.mimeType());
            out.add(new UploadedImage(storage.presignedUrl(key), key, g.revisedPrompt()));
        }
        return out;
    }

    private void fireWebhook(UUID userId, String url, ImageResult result) {
        Mono.fromRunnable(() -> {
                    try {
                        webhookDestinations.requireAllowed(url);
                        Map<String, Object> payload = Map.of(
                                "event", "image.generation.completed",
                                "jobId", result.jobId(),
                                "images", result.images(),
                                "costCents", result.costCents(),
                                "createdAt", result.createdAt());
                        String rawBody = json.writeValueAsString(payload);
                        String signature = webhookSigner.headerValueFromMaterial(
                                webhookSigner.materialFor(userId), rawBody);
                        webhookClient.post().uri(url)
                                .contentType(MediaType.APPLICATION_JSON)
                                .header(WebhookSigner.HEADER, signature)
                                .bodyValue(rawBody)
                                .retrieve()
                                .toBodilessEntity()
                                .timeout(WEBHOOK_TIMEOUT)
                                .block();
                    } catch (Exception e) {
                        log.warn("Webhook delivery to {} failed for job {}: {}",
                                url, result.jobId(), e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    // ===== Persistence helpers =======================================

    private ModelInfo requireModel(ImageRequest request) {
        ModelInfo model = catalog.find(request.model()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown model: " + request.model()));
        if (model.modality() != ai.godance.domain.Modality.IMAGE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + request.model() + " is not an image-generation model");
        }
        return model;
    }

    /** The job row, not yet saved; {@link GenerationJobSettlement#accept} saves it with the pre-debit. */
    private GenerationJob newJob(UUID userId, ModelInfo model, ImageRequest request,
                                 long costCents, String requestId, String webhookUrl) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(model.modality());
        job.setProvider(model.provider());
        job.setModel(model.modelName());
        Map<String, Object> prompt = new java.util.HashMap<>();
        prompt.put("prompt", request.prompt());
        if (request.size() != null) prompt.put("size", request.size());
        if (request.n() != null) prompt.put("n", request.n());
        if (request.style() != null) prompt.put("style", request.style());
        if (request.quality() != null) prompt.put("quality", request.quality());
        job.setPrompt(prompt);
        job.setEstimatedCostCents(costCents);
        job.setRequestId(requestId);
        job.setLeaseExpiresAt(Instant.now().plus(LEASE));
        job.setWebhookUrl(webhookUrl);
        return job;
    }

    private UsageRecord buildUsageRecord(UUID userId, UUID apiKeyId, ModelInfo model, ImageRequest request,
                                         long actualCents, String requestId, int imageCount) {
        long providerCostMicrocents = model.perImageMicrocents() * imageCount;
        long providerCostCents = (providerCostMicrocents + 5_000) / 10_000;

        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setApiKeyId(apiKeyId);   // so per-key caps count media spend (spec risk R-10)
        r.setModality(model.modality());
        r.setModelName(model.modelName());
        r.setProvider(model.provider());
        r.setInputTokens(0);
        r.setOutputTokens(imageCount);   // count of images returned
        r.setCostCents(actualCents);
        r.setCostMicrocents(actualCents * 10_000);          // media bills whole cents up front
        r.setProviderCostCents(providerCostCents);
        r.setProviderCostMicrocents(providerCostMicrocents);
        r.setRegion(gateway.region() == null ? Region.LOCAL : gateway.region());
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setRequestId(requestId);
        return r;
    }

    private static String newRequestId() {
        return "img_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);
    }

    private static String mimeToExt(String mime) {
        if (mime == null) return "bin";
        return switch (mime) {
            case "image/png" -> "png";
            case "image/jpeg" -> "jpg";
            case "image/webp" -> "webp";
            default -> "bin";
        };
    }
}
