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
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import com.fasterxml.jackson.databind.ObjectMapper;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.CostCalculator;
import ai.godance.gateway.ModelCatalog;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import ai.godance.gateway.VideoProviderGateway;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;
import ai.godance.security.WebhookSigner;
import ai.godance.storage.ObjectStorageClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Async-only video orchestrator. Mirrors {@link ImageGenerationService}'s
 * async path but never offers a sync mode — every video model's
 * {@code requiresAsync} flag is true and {@link VideoController} only
 * exposes a 202 response.
 *
 * <p>Cost is per-second × clip-count × markup, computed up-front from
 * the request (no reconcile pass — duration is the client's contract).
 */
@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);
    private static final Duration UPSTREAM_BLOCK_TIMEOUT = Duration.ofMinutes(15);
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);
    private static final int DEFAULT_DURATION_SECONDS = 5;

    private final ModelCatalog catalog;
    private final CostCalculator calculator;
    private final VideoProviderGateway gateway;
    private final CreditLedgerService ledger;
    private final UsageRecordRepository usage;
    private final GenerationJobRepository jobs;
    private final ObjectStorageClient storage;
    private final ObjectMapper json;
    private final WebhookSigner webhookSigner;
    private final WebClient webhookClient;

    public VideoGenerationService(ModelCatalog catalog, CostCalculator calculator,
                                  VideoProviderGateway gateway, CreditLedgerService ledger,
                                  UsageRecordRepository usage, GenerationJobRepository jobs,
                                  ObjectStorageClient storage, ObjectMapper json,
                                  WebhookSigner webhookSigner) {
        this.catalog = catalog;
        this.calculator = calculator;
        this.gateway = gateway;
        this.ledger = ledger;
        this.usage = usage;
        this.jobs = jobs;
        this.storage = storage;
        this.json = json;
        this.webhookSigner = webhookSigner;
        this.webhookClient = WebClient.builder().build();
    }

    public record UploadedVideo(String url, String storageKey) { }

    /**
     * Submit + return immediately with the {@link GenerationJob} id.
     * Processing continues on a bounded-elastic worker; client polls
     * {@code GET /v1/jobs/{id}} or receives a signed webhook POST.
     */
    public UUID submit(UUID userId, VideoRequest request, String webhookUrl) {
        ModelInfo model = requireVideoModel(request);
        int duration = request.durationSeconds() == null
                ? DEFAULT_DURATION_SECONDS : request.durationSeconds();
        int clips = request.n() == null ? 1 : request.n();
        long cost = Math.max(1L, calculator.videoCostCents(model, duration, clips));
        String requestId = "vid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        if (!ledger.preDebit(userId, cost, requestId)) {
            throw new ResponseStatusException(HttpStatus.PAYMENT_REQUIRED,
                    "insufficient credit; top up via /v1/billing/topup");
        }
        GenerationJob job = createJob(userId, model, request, duration, cost, webhookUrl);

        Mono.fromRunnable(() -> runJob(job, userId, model, request, duration, clips, cost,
                        requestId, webhookUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return job.getId();
    }

    private void runJob(GenerationJob job, UUID userId, ModelInfo model, VideoRequest request,
                        int duration, int clips, long cost, String requestId, String webhookUrl) {
        VideoProviderAdapter adapter;
        try {
            adapter = gateway.dispatch(model);
        } catch (RuntimeException e) {
            failJob(job, "gateway dispatch: " + e.getMessage());
            ledger.refundEstimate(userId, cost, requestId, "video gateway dispatch failed");
            return;
        }
        markRunning(job);

        List<GeneratedVideo> upstream;
        try {
            upstream = adapter.generate(request).block(UPSTREAM_BLOCK_TIMEOUT);
            if (upstream == null || upstream.isEmpty()) {
                throw new IllegalStateException("upstream returned no videos");
            }
        } catch (RuntimeException e) {
            log.warn("Video upstream {} failed for job {}: {}",
                    model.provider(), job.getId(), e.getMessage());
            failJob(job, "upstream: " + e.getMessage());
            ledger.refundEstimate(userId, cost, requestId, "video upstream failed");
            return;
        }

        List<UploadedVideo> uploaded = uploadAll(job.getId(), upstream);
        completeJob(job, uploaded, cost);
        usage.save(buildUsageRecord(userId, model, cost, requestId, duration, clips));

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            fireWebhook(userId, webhookUrl, job.getId(), uploaded, cost);
        }
    }

    private List<UploadedVideo> uploadAll(UUID jobId, List<GeneratedVideo> videos) {
        List<UploadedVideo> out = new ArrayList<>(videos.size());
        for (int i = 0; i < videos.size(); i++) {
            GeneratedVideo v = videos.get(i);
            String ext = mimeToExt(v.mimeType());
            String key = "videos/" + jobId + "/" + i + "." + ext;
            storage.upload(key, v.bytes(), v.mimeType());
            out.add(new UploadedVideo(storage.presignedUrl(key), key));
        }
        return out;
    }

    private void fireWebhook(UUID userId, String url, UUID jobId,
                             List<UploadedVideo> videos, long cost) {
        Mono.fromRunnable(() -> {
                    try {
                        Map<String, Object> payload = Map.of(
                                "event", "video.generation.completed",
                                "jobId", jobId,
                                "videos", videos,
                                "costCents", cost,
                                "createdAt", Instant.now().getEpochSecond());
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
                        log.warn("Webhook delivery to {} failed for video job {}: {}",
                                url, jobId, e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    // ===== Persistence helpers =======================================

    private ModelInfo requireVideoModel(VideoRequest request) {
        ModelInfo model = catalog.find(request.model()).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND,
                        "unknown model: " + request.model()));
        if (model.modality() != Modality.VIDEO) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "model " + request.model() + " is not a video model");
        }
        return model;
    }

    @Transactional
    public GenerationJob createJob(UUID userId, ModelInfo model, VideoRequest request,
                                   int duration, long costCents, String webhookUrl) {
        GenerationJob job = new GenerationJob();
        job.setUserId(userId);
        job.setModality(model.modality());
        job.setProvider(model.provider());
        job.setModel(model.modelName());
        Map<String, Object> prompt = new java.util.HashMap<>();
        prompt.put("prompt", request.prompt());
        prompt.put("duration_seconds", duration);
        if (request.size() != null) prompt.put("size", request.size());
        if (request.n() != null) prompt.put("n", request.n());
        job.setPrompt(prompt);
        job.setStatus(JobStatus.QUEUED);
        job.setEstimatedCostCents(costCents);
        job.setWebhookUrl(webhookUrl);
        return jobs.save(job);
    }

    @Transactional
    public void markRunning(GenerationJob job) {
        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(Instant.now());
        jobs.save(job);
    }

    @Transactional
    public void completeJob(GenerationJob job, List<UploadedVideo> uploaded, long cost) {
        job.setStatus(JobStatus.DONE);
        job.setActualCostCents(cost);
        if (!uploaded.isEmpty()) job.setOutputUrl(uploaded.get(0).url());
        job.setCompletedAt(Instant.now());
        jobs.save(job);
    }

    @Transactional
    public void failJob(GenerationJob job, String error) {
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(error);
        job.setCompletedAt(Instant.now());
        jobs.save(job);
    }

    private UsageRecord buildUsageRecord(UUID userId, ModelInfo model, long actualCents,
                                         String requestId, int duration, int clips) {
        long providerCostMicrocents = model.perSecondMicrocents() * duration * clips;
        long providerCostCents = (providerCostMicrocents + 5_000) / 10_000;

        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setApiKeyId(null);
        r.setModality(model.modality());
        r.setModelName(model.modelName());
        r.setProvider(model.provider());
        r.setInputTokens(0);
        r.setOutputTokens(duration * clips);     // seconds of video as the "output unit"
        r.setCostCents(actualCents);
        r.setProviderCostCents(providerCostCents);
        r.setRegion(gateway.region() == null ? Region.LOCAL : gateway.region());
        r.setCacheHit(false);
        r.setFallbackUsed(false);
        r.setRequestId(requestId);
        return r;
    }

    private static String mimeToExt(String mime) {
        if (mime == null) return "bin";
        return switch (mime) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            default -> "bin";
        };
    }
}
