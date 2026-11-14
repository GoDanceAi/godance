package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;

import tools.jackson.databind.ObjectMapper;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.Region;
import ai.godance.domain.UsageRecord;
import ai.godance.gateway.ModelCatalog.ModelInfo;
import ai.godance.gateway.VideoDtos.GeneratedVideo;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.gateway.VideoProviderAdapter;
import ai.godance.gateway.VideoProviderGateway;
import ai.godance.security.WebhookSigner;
import ai.godance.service.GenerationJobSettlement.StoredOutput;
import ai.godance.service.GenerationQuotes.Quote;
import ai.godance.storage.ObjectStorageClient;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Async-only video orchestrator. Mirrors {@link ImageGenerationService}'s
 * async path but never offers a sync mode — every video model's
 * {@code requiresAsync} flag is true and {@link VideoController} only
 * exposes a 202 response.
 *
 * <p>Cost is per-second × clip-count × markup. The clip length is decided
 * once, here: a request that names none gets its adapter's default, and
 * that same value is both priced and sent to the provider. The price for
 * the requested clip count is held up front; when the provider delivers
 * fewer clips, only those are charged and the rest of the hold is returned.
 *
 * <p>Every status change goes through {@link GenerationJobSettlement}: the
 * job row and its pre-debit are written together, and the job ends exactly
 * once, DONE with its usage row or FAILED with a refund, even if
 * {@link GenerationJobSweepJob} reaches it first (spec risks R-03, R-05).
 */
@Service
public class VideoGenerationService {

    private static final Logger log = LoggerFactory.getLogger(VideoGenerationService.class);
    private static final Duration UPSTREAM_BLOCK_TIMEOUT = Duration.ofMinutes(15);
    /** Longest a live worker can hold a job: the upstream wait plus time to store the result. */
    private static final Duration LEASE = UPSTREAM_BLOCK_TIMEOUT.plusMinutes(10);
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);

    private final GenerationQuotes quotes;
    private final VideoProviderGateway gateway;
    private final GenerationJobSettlement settlement;
    private final ObjectStorageClient storage;
    private final ObjectMapper json;
    private final WebhookSigner webhookSigner;
    private final WebhookDestinations webhookDestinations;
    private final WebClient webhookClient;

    public VideoGenerationService(GenerationQuotes quotes,
                                  VideoProviderGateway gateway, GenerationJobSettlement settlement,
                                  ObjectStorageClient storage, ObjectMapper json,
                                  WebhookSigner webhookSigner,
                                  WebhookDestinations webhookDestinations) {
        this.quotes = quotes;
        this.gateway = gateway;
        this.settlement = settlement;
        this.storage = storage;
        this.json = json;
        this.webhookSigner = webhookSigner;
        this.webhookDestinations = webhookDestinations;
        this.webhookClient = webhookDestinations.client();
    }

    public record UploadedVideo(String url, String storageKey) { }

    /**
     * Submit + return immediately with the {@link GenerationJob} id.
     * Processing continues on a bounded-elastic worker; client polls
     * {@code GET /v1/jobs/{id}} or receives a signed webhook POST.
     * {@code origin} is where the request came from; the job row records it.
     */
    public UUID submit(UUID userId, UUID apiKeyId, VideoRequest submitted, String webhookUrl,
                       JobOrigin origin) {
        webhookDestinations.requireAllowed(webhookUrl);   // before any charge (spec risk R-02)
        // 404 / 400 (including an n the model cannot return) before any charge.
        // The quote is the pre-debit, the same figure POST /v1/estimates returns.
        Quote quote = quotes.video(submitted.model(), submitted.n(), submitted.durationSeconds());
        ModelInfo model = quote.model();
        // The length that is priced is the length the provider is asked for:
        // the adapter never falls back to a default of its own.
        int duration = quote.seconds();
        VideoRequest request = new VideoRequest(submitted.model(), submitted.prompt(),
                submitted.size(), duration, submitted.n());
        int clips = quote.n();
        long cost = quote.costCents();
        String requestId = "vid_" + UUID.randomUUID().toString().replace("-", "").substring(0, 24);

        GenerationJob job = newJob(userId, model, request, duration, cost, requestId, webhookUrl);
        origin.applyTo(job);
        if (!settlement.accept(job, apiKeyId)) {
            throw new InsufficientCreditException();
        }

        // The pre-debit and the job row are committed together; if this worker
        // dies, GenerationJobSweepJob fails and refunds the job once its lease runs out.
        Mono.fromRunnable(() -> runJob(job, userId, apiKeyId, model, request, duration, clips, webhookUrl))
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();

        return job.getId();
    }

    private void runJob(GenerationJob job, UUID userId, UUID apiKeyId, ModelInfo model, VideoRequest request,
                        int duration, int clips, String webhookUrl) {
        VideoProviderAdapter adapter;
        try {
            adapter = gateway.dispatch(model);
        } catch (RuntimeException e) {
            settlement.fail(job, "gateway dispatch: " + e.getMessage(), "video gateway dispatch failed");
            return;
        }
        if (!settlement.start(job)) {
            log.warn("Video job {} ended before its worker started; not running it", job.getId());
            return;
        }

        List<GeneratedVideo> upstream;
        try {
            upstream = adapter.generate(request).block(UPSTREAM_BLOCK_TIMEOUT);
            if (upstream == null || upstream.isEmpty()) {
                throw new IllegalStateException("upstream returned no videos");
            }
        } catch (RuntimeException e) {
            log.warn("Video upstream {} failed for job {}: {}",
                    model.provider(), job.getId(), e.getMessage());
            settlement.fail(job, "upstream: " + e.getMessage(), "video upstream failed");
            return;
        }

        // The provider has delivered. Storing the result and ending the job DONE
        // share one guard: a failure here ends the job FAILED and refunded, never
        // RUNNING with the pre-debit kept (spec risk R-05).
        //
        // The charge is for the clips delivered, never more than was asked for:
        // a provider that returns one clip for n = 2 is paid for one, and
        // settlement returns the rest of the hold.
        long cost = chargeFor(job, model, duration, Math.min(upstream.size(), clips));
        List<UploadedVideo> uploaded;
        boolean completed;
        try {
            Uploads uploads = uploadAll(job.getId(), upstream);
            uploaded = uploads.videos();
            completed = settlement.complete(job, uploaded.isEmpty() ? null : uploaded.get(0).url(),
                    uploads.stored(), cost,
                    buildUsageRecord(userId, apiKeyId, model, cost, job.getRequestId(), duration, upstream.size()));
        } catch (RuntimeException e) {
            log.warn("Video job {} could not be finalised: {}", job.getId(), e.getMessage());
            settlement.fail(job, "finalize: " + e.getMessage(), "video result could not be stored");
            return;
        }
        if (!completed) {
            log.warn("Video job {} was ended by the sweep while it ran; its result is dropped", job.getId());
            return;
        }

        if (webhookUrl != null && !webhookUrl.isBlank()) {
            fireWebhook(userId, webhookUrl, job.getId(), uploaded, cost);
        }
    }

    /** The price of {@code clips} clips of this job's length, capped at what the job held up front. */
    private long chargeFor(GenerationJob job, ModelInfo model, int duration, int clips) {
        return Math.min(job.getEstimatedCostCents(), quotes.videoPriceCents(model, duration, clips));
    }

    /** What was stored: the clips as the webhook shows them, and the rows the job records. */
    private record Uploads(List<UploadedVideo> videos, List<StoredOutput> stored) { }

    private Uploads uploadAll(UUID jobId, List<GeneratedVideo> videos) {
        List<UploadedVideo> out = new ArrayList<>(videos.size());
        List<StoredOutput> stored = new ArrayList<>(videos.size());
        for (int i = 0; i < videos.size(); i++) {
            GeneratedVideo v = videos.get(i);
            String ext = mimeToExt(v.mimeType());
            String key = "videos/" + jobId + "/" + i + "." + ext;
            storage.upload(key, v.bytes(), v.mimeType());
            out.add(new UploadedVideo(storage.presignedUrl(key), key));
            String contentType = v.mimeType() == null || v.mimeType().isBlank()
                    ? "application/octet-stream" : v.mimeType();
            stored.add(new StoredOutput(key, contentType, v.bytes().length, null));
        }
        return new Uploads(out, stored);
    }

    private void fireWebhook(UUID userId, String url, UUID jobId,
                             List<UploadedVideo> videos, long cost) {
        Mono.fromRunnable(() -> {
                    try {
                        webhookDestinations.requireAllowed(url);
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

    /** The job row, not yet saved; {@link GenerationJobSettlement#accept} saves it with the pre-debit. */
    private GenerationJob newJob(UUID userId, ModelInfo model, VideoRequest request, int duration,
                                 long costCents, String requestId, String webhookUrl) {
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
        job.setEstimatedCostCents(costCents);
        job.setRequestId(requestId);
        job.setLeaseExpiresAt(Instant.now().plus(LEASE));
        job.setWebhookUrl(webhookUrl);
        return job;
    }

    /** {@code clips} is the number of clips the provider returned, which is what it bills us for. */
    private UsageRecord buildUsageRecord(UUID userId, UUID apiKeyId, ModelInfo model, long actualCents,
                                         String requestId, int duration, int clips) {
        long providerCostMicrocents = model.perSecondMicrocents() * duration * clips;
        long providerCostCents = (providerCostMicrocents + 5_000) / 10_000;

        UsageRecord r = new UsageRecord();
        r.setUserId(userId);
        r.setApiKeyId(apiKeyId);   // so per-key caps count media spend (spec risk R-10)
        r.setModality(model.modality());
        r.setModelName(model.modelName());
        r.setProvider(model.provider());
        r.setInputTokens(0);
        r.setOutputTokens(duration * clips);     // seconds of video as the "output unit"
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

    private static String mimeToExt(String mime) {
        if (mime == null) return "bin";
        return switch (mime) {
            case "video/mp4" -> "mp4";
            case "video/webm" -> "webm";
            default -> "bin";
        };
    }
}
