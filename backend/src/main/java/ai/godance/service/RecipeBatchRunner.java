package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.server.ResponseStatusException;

import tools.jackson.databind.ObjectMapper;

import ai.godance.domain.ApiKey;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.BatchRowStatus;
import ai.godance.domain.BatchStatus;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobChannel;
import ai.godance.domain.Modality;
import ai.godance.domain.RecipeBatch;
import ai.godance.domain.RecipeBatchRow;
import ai.godance.gateway.ImageDtos.ImageRequest;
import ai.godance.gateway.VideoDtos.VideoRequest;
import ai.godance.repository.ApiKeyRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.RecipeBatchRepository;
import ai.godance.repository.RecipeBatchRowRepository;
import ai.godance.security.WebhookSigner;
import ai.godance.service.RecipeRuns.ImageRun;
import ai.godance.service.RecipeRuns.VideoRun;
import ai.godance.service.SpendingLimitService.SpendingLimitExceededException;
import ai.godance.web.dto.BatchDtos.BatchView;
import ai.godance.web.dto.RecipeDtos.RecipeRef;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Advances recipe batches (round 12 brief 01): one pass, {@link #tick()},
 * run every few seconds by {@link RecipeBatchTickJob} on one instance.
 *
 * <p>For each batch that is not over, a pass does three things, in order.
 * <ol>
 *   <li><b>Rows take their jobs' outcomes.</b> A running row whose job has
 *       ended becomes DONE with what the job was charged, or FAILED with the
 *       job's error. A failed job was already refunded by the job's own
 *       path; one failed row does not stop the batch.</li>
 *   <li><b>Rows are started,</b> lowest index first, until
 *       {@code max_concurrency} are running. A row is claimed (RUNNING), and
 *       then its job is created by the same code a single recipe run uses
 *       ({@link RecipeRuns}, then the generation services). That is the
 *       normal path: the job's pre-debit, lease, settlement and refund are
 *       every other job's.</li>
 *   <li><b>The batch ends</b> when no row is pending or running: its input
 *       holds are let go, and its webhook is sent, once.</li>
 * </ol>
 *
 * <p><b>A batch charges nothing.</b> Nothing here touches the ledger. The
 * only money that moves is each job's own pre-debit and settlement.
 *
 * <p><b>No row is submitted twice.</b> The job records its row, and the
 * transaction that takes the job's pre-debit also writes the job's id on
 * the row ({@link GenerationJobSettlement#accept}); the database allows one
 * job per row. So after a crash a running row is in one of two states. With
 * a job id, its job exists: the row waits for that job, which ends by
 * itself or is failed and refunded by {@link GenerationJobSweepJob} when
 * its lease runs out; the row is never started again. With no job id, no
 * job was created and no provider was called: starting it again is its
 * first submission.
 *
 * <p><b>A refused pre-debit pauses the batch.</b> When the balance cannot
 * cover a row's job, or a spending cap of the account or of the batch's API
 * key refuses it, nothing was written for that job. The row goes back to
 * PENDING and the batch is PAUSED with the reason, until its owner resumes
 * it. Any other refusal of a row (an input deleted since the batch was
 * made, say) fails that row alone.
 *
 * <p>Every change of state is a conditional update, so a cancel, a resume
 * and a pass can overlap and none undoes another.
 */
@Service
public class RecipeBatchRunner {

    private static final Logger log = LoggerFactory.getLogger(RecipeBatchRunner.class);

    static final String PAUSE_CREDIT = "insufficient_credit";
    static final String PAUSE_CAP = "spending_cap";

    /** The event a batch's webhook carries. */
    public static final String WEBHOOK_EVENT = "recipe_batch.completed";

    /** The most batches one pass looks at; with two unfinished per account this is hundreds of accounts. */
    static final int MAX_OPEN_PER_PASS = 500;
    private static final int MAX_ERROR_LENGTH = 1_000;
    private static final Duration WEBHOOK_TIMEOUT = Duration.ofSeconds(10);

    private final RecipeBatchRepository batches;
    private final RecipeBatchRowRepository rows;
    private final GenerationJobRepository jobs;
    private final ApiKeyRepository apiKeys;
    private final SpendingLimitService spendingLimits;
    private final RecipeRuns runs;
    private final ImageGenerationService images;
    private final VideoGenerationService videos;
    private final InputService inputs;
    private final AuditLogService audit;
    private final RecipeBatchViews views;
    private final WebhookSigner webhookSigner;
    private final WebhookDestinations webhookDestinations;
    private final WebClient webhookClient;
    private final ObjectMapper json;
    private final TransactionTemplate tx;

    public RecipeBatchRunner(RecipeBatchRepository batches, RecipeBatchRowRepository rows,
                             GenerationJobRepository jobs, ApiKeyRepository apiKeys,
                             SpendingLimitService spendingLimits, RecipeRuns runs,
                             ImageGenerationService images, VideoGenerationService videos,
                             InputService inputs, AuditLogService audit, RecipeBatchViews views,
                             WebhookSigner webhookSigner, WebhookDestinations webhookDestinations,
                             ObjectMapper json, PlatformTransactionManager txManager) {
        this.batches = batches;
        this.rows = rows;
        this.jobs = jobs;
        this.apiKeys = apiKeys;
        this.spendingLimits = spendingLimits;
        this.runs = runs;
        this.images = images;
        this.videos = videos;
        this.inputs = inputs;
        this.audit = audit;
        this.views = views;
        this.webhookSigner = webhookSigner;
        this.webhookDestinations = webhookDestinations;
        this.webhookClient = webhookDestinations.client();
        this.json = json;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * One pass over every batch that is not over. A batch that fails to
     * advance is logged and left for the next pass; the others still run.
     */
    public void tick() {
        for (UUID batchId : batches.findOpenIds(MAX_OPEN_PER_PASS)) {
            try {
                advance(batchId);
            } catch (RuntimeException e) {
                log.error("RecipeBatchRunner: batch {} could not be advanced this pass", batchId, e);
            }
        }
        // A batch that ended just before a restart still has its webhook to send.
        for (UUID batchId : batches.findEndedWithUnsentWebhook(MAX_OPEN_PER_PASS)) {
            try {
                sendWebhookOnce(batchId);
            } catch (RuntimeException e) {
                log.warn("RecipeBatchRunner: webhook of batch {} could not be sent: {}", batchId, e.getMessage());
            }
        }
    }

    /** One batch, one pass. */
    void advance(UUID batchId) {
        RecipeBatch batch = batches.findById(batchId).orElse(null);
        if (batch == null) return;
        takeOutcomes(batch);
        if (batch.getStatus().startsRows()) {
            String gone = whyItCannotGoOn(batch);
            if (gone != null) {
                cancelBecause(batch, gone);
            } else {
                startRows(batch);
            }
        }
        settle(batchId);
    }

    // ===== 1. Rows take their jobs' outcomes ===========================

    private void takeOutcomes(RecipeBatch batch) {
        for (RecipeBatchRow row : rows.findRunning(batch.getId())) {
            if (row.getJobId() == null) {
                recoverClaimed(batch, row);
                continue;
            }
            GenerationJob job = jobs.findById(row.getJobId()).orElse(null);
            if (job == null) {
                // Jobs are never deleted; this keeps a batch from waiting forever if one ever is.
                finish(row, BatchRowStatus.FAILED, 0, "the row's job no longer exists");
                continue;
            }
            switch (job.getStatus()) {
                case DONE -> finish(row, BatchRowStatus.DONE,
                        job.getActualCostCents() == null ? 0 : job.getActualCostCents(), null);
                // A failed job's estimate went back through the job's own refund: the row cost nothing.
                case FAILED, CANCELLED -> finish(row, BatchRowStatus.FAILED, 0,
                        job.getErrorMessage() == null ? "the job failed" : job.getErrorMessage());
                default -> { }
            }
        }
    }

    private void finish(RecipeBatchRow row, BatchRowStatus status, long costCents, String error) {
        tx.executeWithoutResult(s -> rows.finish(row.getId(), status.name(), costCents, truncate(error), Instant.now()));
    }

    /**
     * A row that was claimed and has no job id: a pass stopped between
     * claiming it and creating its job. No job was created for it, since a
     * job and its row's job id are written together, so no provider was
     * called. It is started now if its batch still starts rows, skipped if
     * the batch was cancelled, and put back if it was paused.
     */
    private void recoverClaimed(RecipeBatch batch, RecipeBatchRow row) {
        GenerationJob made = jobs.findByBatchRowId(row.getId()).orElse(null);
        if (made != null) {
            // Cannot happen while a job and its row's job id are one transaction; never start a second job.
            tx.executeWithoutResult(s -> rows.attachJob(row.getId(), made.getId()));
            return;
        }
        switch (batch.getStatus()) {
            case QUEUED, RUNNING -> startRow(batch, row);
            case CANCELLED -> tx.executeWithoutResult(s -> rows.skipUnstarted(row.getId(), Instant.now()));
            default -> tx.executeWithoutResult(s -> rows.release(row.getId()));
        }
    }

    // ===== 2. Rows are started ========================================

    private void startRows(RecipeBatch batch) {
        UUID batchId = batch.getId();
        int free = batch.getMaxConcurrency() - rows.countRunning(batchId);
        if (free <= 0) return;
        for (RecipeBatchRow row : rows.findNextPending(batchId, free)) {
            Instant now = Instant.now();
            // Claimed only while the batch still starts rows: a cancel or a pause that got there first wins.
            Integer claimed = tx.execute(s -> {
                if (rows.claim(row.getId(), now) == 0) return 0;
                batches.markRunning(batchId, now);
                return 1;
            });
            if (claimed == null || claimed == 0) return;
            if (!startRow(batch, row)) return;
        }
    }

    /**
     * Creates the job of a claimed row. False when the batch was paused
     * instead: the caller starts no more rows this pass.
     */
    private boolean startRow(RecipeBatch batch, RecipeBatchRow row) {
        try {
            // What the generation routes check before a request reaches its controller: a cap already
            // reached refuses the job, and the 80% warning is sent. The pre-debit then holds the estimate.
            spendingLimits.enforce(batch.getUserId(), batch.getApiKeyId());
            submit(batch, row);
            return true;
        } catch (InsufficientCreditException e) {
            pause(batch, row, PAUSE_CREDIT, null);
            return false;
        } catch (SpendingLimitExceededException e) {
            pause(batch, row, PAUSE_CAP, e.getReason());
            return false;
        } catch (ResponseStatusException e) {
            // Refused as a single run would be: an input deleted since the batch was made, a model withdrawn.
            failUnstarted(row, e.getReason() == null ? "this row could not be run" : e.getReason());
            return true;
        } catch (RuntimeException e) {
            log.warn("RecipeBatchRunner: row {} of batch {} could not be started", row.getRowIndex(),
                    batch.getId(), e);
            failUnstarted(row, "this row could not be started");
            return true;
        }
    }

    /**
     * The row's job, through the normal path: rendered and checked by
     * {@link RecipeRuns}, then accepted by the generation service, which
     * takes the pre-debit and writes the job and the row's job id together.
     * No per-job webhook: a batch sends one webhook, when it ends.
     */
    private void submit(RecipeBatch batch, RecipeBatchRow row) {
        if (batch.getRecipeVersionId() == null) throw RecipeRefusedException.notFound();
        UUID userId = batch.getUserId();
        RecipeRef recipe = new RecipeRef(batch.getRecipeVersionId(), row.getVariables(),
                BatchRowInputs.fromStored(row.getInputs()));
        JobOrigin origin = JobOrigin.ofBatchRow(row.getId(), batch.getCreatedByChannel());
        BatchSettings settings = BatchSettings.of(batch.getParams());
        if (batch.getModality() == Modality.IMAGE) {
            ImageRequest domain = new ImageRequest(batch.getModel(), null, settings.size(), settings.n(),
                    settings.style(), settings.quality());
            ImageRun run = runs.image(userId, domain, null, recipe, origin);
            images.executeAsync(userId, batch.getApiKeyId(), run.request(), null, run.origin(), run.inputs());
        } else {
            VideoRequest domain = new VideoRequest(batch.getModel(), null, settings.size(), settings.seconds(),
                    settings.n());
            VideoRun run = runs.video(userId, domain, null, recipe, VideoInputs.Refs.NONE, origin);
            videos.submit(userId, batch.getApiKeyId(), run.request(), null, run.origin(), run.inputs());
        }
    }

    /** The row goes back to PENDING and the batch pauses, together. Nothing was charged for the row. */
    private void pause(RecipeBatch batch, RecipeBatchRow row, String reason, String detail) {
        tx.executeWithoutResult(s -> {
            rows.release(row.getId());
            if (batches.pause(batch.getId(), reason, detail) == 0) return;
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("batchId", batch.getId().toString());
            event.put("reason", reason);
            if (detail != null) event.put("detail", detail);
            event.put("row", row.getRowIndex());
            audit.record(AuditLogService.EVT_BATCH_PAUSED, AuditOutcome.SUCCESS, batch.getUserId(), null, event);
        });
    }

    /** Ends a claimed row FAILED. Changes nothing when the row has a job: then the job decides. */
    private void failUnstarted(RecipeBatchRow row, String error) {
        tx.executeWithoutResult(s -> rows.failUnstarted(row.getId(), truncate(error), Instant.now()));
    }

    /**
     * Why a batch that would start rows must not: its recipe was deleted,
     * or the API key that made it can no longer be used. Null when it may
     * go on. A key that is deleted, switched off or expired stops what it
     * started, as it stops any request made with it.
     */
    private String whyItCannotGoOn(RecipeBatch batch) {
        if (batch.getRecipeVersionId() == null) return "recipe_deleted";
        if (batch.getCreatedByChannel() != JobChannel.API) return null;
        if (batch.getApiKeyId() == null) return "api_key_deleted";
        ApiKey key = apiKeys.findById(batch.getApiKeyId()).orElse(null);
        if (key == null) return "api_key_deleted";
        if (!key.isActive()) return "api_key_inactive";
        if (key.getExpiresAt() != null && !key.getExpiresAt().isAfter(Instant.now())) return "api_key_expired";
        return null;
    }

    private void cancelBecause(RecipeBatch batch, String reason) {
        tx.executeWithoutResult(s -> {
            if (batches.cancel(batch.getId()) == 0) return;
            int skipped = rows.skipPending(batch.getId(), Instant.now());
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("batchId", batch.getId().toString());
            event.put("reason", reason);
            event.put("rowsSkipped", skipped);
            audit.record(AuditLogService.EVT_BATCH_CANCELLED, AuditOutcome.SUCCESS, batch.getUserId(), null, event);
        });
        log.info("RecipeBatchRunner: batch {} cancelled: {}", batch.getId(), reason);
    }

    // ===== 3. The batch ends ==========================================

    /**
     * Ends the batch if none of its rows is pending or running, and then
     * sends its webhook. Ending is one conditional update, so however many
     * callers get here, one ends the batch and one sends the webhook.
     */
    public void settle(UUID batchId) {
        Instant now = Instant.now();
        tx.executeWithoutResult(s -> {
            if (batches.endIfSettled(batchId, now) == 0) return;
            // The files its rows named are let go; one a job used is still held by that job.
            inputs.releaseBatch(batchId, now);
            RecipeBatch ended = batches.findById(batchId).orElse(null);
            if (ended == null || ended.getStatus() != BatchStatus.DONE) return;
            BatchView view = views.view(ended);
            Map<String, Object> event = new LinkedHashMap<>();
            event.put("batchId", batchId.toString());
            event.put("rowsDone", view.rowsDone());
            event.put("rowsFailed", view.rowsFailed());
            event.put("spentCents", view.spentCents());
            audit.record(AuditLogService.EVT_BATCH_COMPLETED, AuditOutcome.SUCCESS, ended.getUserId(), null, event);
        });
        sendWebhookOnce(batchId);
    }

    /**
     * Sends the webhook of a batch that has ended, if it asked for one and
     * nobody has sent it. The right to send is taken first, by a
     * conditional update, so it is sent at most once; like a job's webhook
     * it is not retried.
     */
    private void sendWebhookOnce(UUID batchId) {
        RecipeBatch batch = batches.findById(batchId).orElse(null);
        if (batch == null || batch.getCompletedAt() == null || batch.getWebhookUrl() == null
                || batch.getWebhookSentAt() != null) {
            return;
        }
        Integer claimed = tx.execute(s -> batches.claimWebhook(batchId, Instant.now()));
        if (claimed == null || claimed == 0) return;

        String url = batch.getWebhookUrl();
        UUID userId = batch.getUserId();
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("event", WEBHOOK_EVENT);
        payload.put("batch", views.view(batch));
        Map<String, String> results = new LinkedHashMap<>();
        results.put("csv", "/v1/recipe-batches/" + batchId + "/results.csv");
        results.put("zip", "/v1/recipe-batches/" + batchId + "/results.zip");
        payload.put("results", results);

        Mono.fromRunnable(() -> {
                    try {
                        webhookDestinations.requireAllowed(url);
                        String rawBody = json.writeValueAsString(payload);
                        // Signed exactly as a job's webhook is: the owner's secret, over "<t>.<body>".
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
                        log.warn("Webhook delivery to {} failed for batch {}: {}", url, batchId, e.getMessage());
                    }
                })
                .subscribeOn(Schedulers.boundedElastic())
                .subscribe();
    }

    private static String truncate(String error) {
        if (error == null || error.length() <= MAX_ERROR_LENGTH) return error;
        return error.substring(0, MAX_ERROR_LENGTH);
    }
}
