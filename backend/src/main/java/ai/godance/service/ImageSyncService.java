package ai.godance.service;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.ImagesProperties;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.ImageResponseMode;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.ImageDtos.ImageData;
import ai.godance.web.dto.ImageDtos.ImageGenerationResponse;
import io.micrometer.core.instrument.MeterRegistry;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * Image responses in OpenAI's shape (round 10 brief 04). An API key set to
 * {@code sync} has its image requests wait for the job and answer with the
 * images, as OpenAI's API does, so standard SDK code that reads
 * {@code data[0].url} works unchanged.
 *
 * <p>Nothing here creates, charges, ends or refunds a job. The job is
 * accepted and run exactly as an async one ({@link ImageGenerationService},
 * {@link GenerationJobSettlement}); this only watches it and shapes the
 * answer from what the job recorded.
 *
 * <p><b>The wait holds no thread.</b> It reads the job's row on a timer,
 * each read on the bounded-elastic scheduler, with nothing held in between.
 * Reading the row, not listening for an in-memory signal, is what makes it
 * work when the job is finished by another instance: the sweep after a
 * lease runs out, or a second node. A client that disconnects cancels the
 * wait; the job carries on and is settled as usual.
 */
@Service
public class ImageSyncService {

    private static final Logger log = LoggerFactory.getLogger(ImageSyncService.class);

    /** The {@code code} of the 502 a failed job answers. */
    public static final String CODE_GENERATION_FAILED = "generation_failed";

    /** Counts waits by how they ended: {@code outcome} is done, failed, timeout or cancelled. */
    public static final String WAITS_METER = "godance.images.sync.waits";

    private final GenerationJobRepository jobs;
    private final GenerationJobOutputRepository outputs;
    private final ObjectStorageClient storage;
    private final ApiKeyService keys;
    private final ImagesProperties props;
    private final MeterRegistry meters;

    public ImageSyncService(GenerationJobRepository jobs, GenerationJobOutputRepository outputs,
                            ObjectStorageClient storage, ApiKeyService keys, ImagesProperties props,
                            MeterRegistry meters) {
        this.jobs = jobs;
        this.outputs = outputs;
        this.storage = storage;
        this.keys = keys;
        this.props = props;
        this.meters = meters;
    }

    /** How a wait ended. */
    public enum Outcome { DONE, FAILED, TIMEOUT }

    /** The job as last read; null only when a wait timed out before its first read came back. */
    public record Waited(Outcome outcome, GenerationJob job) { }

    /** Whether requests made with this key wait for their image jobs. */
    public Mono<Boolean> waits(UUID apiKeyId) {
        return Mono.fromCallable(() -> keys.imageResponseOf(apiKeyId) == ImageResponseMode.SYNC)
                .subscribeOn(Schedulers.boundedElastic());
    }

    /**
     * Waits for the job to end, up to {@code godance.images.sync-wait}.
     * Emits once: how it ended, or {@link Outcome#TIMEOUT} with the job
     * still open. Cancelling the subscription stops the reads.
     */
    public Mono<Waited> await(UUID jobId) {
        long started = System.nanoTime();
        GenerationJob[] lastRead = new GenerationJob[1];
        return Mono.defer(() -> read(jobId))
                .doOnNext(job -> lastRead[0] = job)
                .filter(job -> ended(job.getStatus()))
                // Not ended yet: read again after the interval. No thread waits in between.
                .repeatWhenEmpty(Integer.MAX_VALUE, polls -> polls.delayElements(props.syncPollInterval()))
                .map(job -> new Waited(job.getStatus() == JobStatus.DONE ? Outcome.DONE : Outcome.FAILED, job))
                .timeout(props.syncWait(), Mono.fromSupplier(() -> new Waited(Outcome.TIMEOUT, lastRead[0])))
                .doOnNext(waited -> record(jobId, waited.outcome().name().toLowerCase(), started))
                // Downstream of the timeout, so only the caller going away counts.
                .doOnCancel(() -> record(jobId, "cancelled", started));
    }

    /** One read of the job's row, off the event loop. Empty if the row is gone. */
    private Mono<GenerationJob> read(UUID jobId) {
        return Mono.fromCallable(() -> jobs.findById(jobId))
                .subscribeOn(Schedulers.boundedElastic())
                .flatMap(Mono::justOrEmpty);
    }

    private static boolean ended(JobStatus status) {
        return status == JobStatus.DONE || status == JobStatus.FAILED || status == JobStatus.CANCELLED;
    }

    private void record(UUID jobId, String outcome, long startedNanos) {
        meters.counter(WAITS_METER, "outcome", outcome).increment();
        long ms = Duration.ofNanos(System.nanoTime() - startedNanos).toMillis();
        if ("done".equals(outcome) || "cancelled".equals(outcome)) {
            log.debug("Image sync wait for job {} ended {} after {} ms", jobId, outcome, ms);
        } else {
            log.info("Image sync wait for job {} ended {} after {} ms", jobId, outcome, ms);
        }
    }

    /**
     * A DONE job's result in OpenAI's shape, built from the outputs the job
     * recorded, each link signed now. Blocking: reads the database and, for
     * {@code b64_json}, the stored files.
     *
     * <ul>
     *   <li>{@code response_format: "url"}: a link per image.</li>
     *   <li>{@code response_format: "b64_json"}: each image as base64.</li>
     *   <li>No format named: links; and for the models OpenAI's own API
     *       answers as base64 ({@link ImageResponseFormat#inlineByDefault}),
     *       the base64 as well, so code written for either shape works.</li>
     * </ul>
     * A result larger than {@code godance.images.max-inline-bytes} is never
     * inlined: it answers with links, whatever was asked for.
     */
    public ImageGenerationResponse response(GenerationJob job, ImageResponseFormat requested) {
        List<GenerationJobOutput> rows = outputs.findByJobIdOrderByOutputIndexAsc(job.getId());
        boolean wantsInline = requested == ImageResponseFormat.B64_JSON
                || (requested == null && ImageResponseFormat.inlineByDefault(job.getModel()));
        long totalBytes = rows.stream().mapToLong(GenerationJobOutput::getSizeBytes).sum();
        boolean inline = wantsInline && totalBytes <= props.maxInlineBytes().toBytes();
        if (wantsInline && !inline) {
            log.info("Image job {} is {} bytes, above the inline limit; answering with links",
                    job.getId(), totalBytes);
        }
        boolean links = !inline || requested == null;

        List<ImageData> data = new ArrayList<>(rows.size());
        for (GenerationJobOutput row : rows) {
            data.add(new ImageData(
                    links ? storage.presignedUrl(row.getStorageKey()) : null,
                    inline ? Base64.getEncoder().encodeToString(storage.download(row.getStorageKey())) : null,
                    row.getRevisedPrompt()));
        }
        long created = (job.getCompletedAt() == null ? job.getCreatedAt() : job.getCompletedAt()).getEpochSecond();
        return new ImageGenerationResponse(created, data, job.getId(), job.getActualCostCents());
    }

    /** {@link #response} for a job known by its id; 404 if it is gone. */
    public ImageGenerationResponse response(UUID jobId, ImageResponseFormat requested) {
        GenerationJob job = jobs.findById(jobId).orElseThrow(() ->
                new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
        return response(job, requested);
    }

    /** The 502 a job that ended FAILED answers. Its charge was already returned by the settlement. */
    public static ResponseStatusException generationFailed(GenerationJob job) {
        String detail = job.getErrorMessage() == null || job.getErrorMessage().isBlank()
                ? "the image could not be generated"
                : job.getErrorMessage();
        ResponseStatusException e = new ResponseStatusException(HttpStatus.BAD_GATEWAY, detail);
        e.getBody().setProperty("code", CODE_GENERATION_FAILED);
        e.getBody().setProperty("job_id", job.getId().toString());
        return e;
    }
}
