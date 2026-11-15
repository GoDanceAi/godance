package ai.godance.web;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;

import ai.godance.service.ImageGenerationService.ImageResult;
import ai.godance.service.ImageResponseFormat;
import ai.godance.service.ImageSyncService;
import ai.godance.web.dto.ImageDtos.AsyncJobAcknowledged;
import ai.godance.web.dto.ImageDtos.ImageGenerationResponse;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/**
 * How an image request is answered once its job exists: round 10's rule for
 * the key that made it. One place, used by every route that makes an image
 * job ({@code /v1/images/generations}, {@code /v1/images/edits}), so they
 * cannot drift apart.
 *
 * <p>A request gets 202 with its job, unless it is made with an API key set
 * to {@code sync} ({@code image_response}, see {@link ImageSyncService}).
 * Such a request waits for its job and answers 200 in OpenAI's shape, unless
 * it set {@code "async": true} or sent {@code Prefer: respond-async}. A
 * signed-in session never waits.
 */
@Component
public class ImageJobAnswers {

    /** What a request that stopped waiting is told to wait before it reads the job. */
    static final int RETRY_AFTER_SECONDS = 5;

    private final ImageSyncService sync;

    public ImageJobAnswers(ImageSyncService sync) {
        this.sync = sync;
    }

    /**
     * Whether a request waits for its job: only when the caller did not ask
     * for a job, with an API key (which must also be set to sync), on a
     * model that runs as one.
     */
    public boolean mayWait(UUID apiKeyId, boolean runsAsJob, Boolean async, String prefer) {
        return apiKeyId != null && runsAsJob && !Boolean.TRUE.equals(async) && !prefersAsync(prefer);
    }

    /** The answer for a job: at once when the request may not wait, by the key's setting when it may. */
    public Mono<ResponseEntity<?>> forJob(UUID apiKeyId, UUID jobId, ImageResponseFormat format, boolean mayWait) {
        return mayWait ? answerFor(apiKeyId, jobId, format) : Mono.just(accepted(jobId));
    }

    /** The answer for a request that ran to its end inside the call: 200 with the images. */
    public ResponseEntity<?> forResult(ImageResult result, ImageResponseFormat format, String model) {
        return ResponseEntity.status(HttpStatus.OK)
                .<Object>body(inlines(format, model)
                        ? sync.response(result.jobId(), format)
                        : ImageGenerationResponse.from(result));
    }

    /** Today's answer for a job: 202 with its id and where to poll it. */
    private static ResponseEntity<?> accepted(UUID jobId) {
        return ResponseEntity.accepted().<Object>body(AsyncJobAcknowledged.forJob(jobId));
    }

    /**
     * The answer for a job the caller may wait on. A key left on async gets
     * the 202 at once. A sync key waits for the job: done is 200 in OpenAI's
     * shape; failed is a 502 problem with {@code code: "generation_failed"};
     * still running at the limit is the 202 with {@code Retry-After}, and the
     * job carries on.
     */
    private Mono<ResponseEntity<?>> answerFor(UUID apiKeyId, UUID jobId, ImageResponseFormat format) {
        return sync.waits(apiKeyId).flatMap(waits -> {
            if (!waits) return Mono.just(accepted(jobId));
            return sync.await(jobId).flatMap(waited -> switch (waited.outcome()) {
                case DONE -> Mono.fromCallable(() -> sync.response(waited.job(), format))
                        .subscribeOn(Schedulers.boundedElastic())
                        .map(body -> ResponseEntity.status(HttpStatus.OK).<Object>body(body));
                case FAILED -> Mono.error(ImageSyncService.generationFailed(waited.job()));
                case TIMEOUT -> Mono.just(ResponseEntity.accepted()
                        .header(HttpHeaders.RETRY_AFTER, String.valueOf(RETRY_AFTER_SECONDS))
                        .<Object>body(waited.job() == null
                                ? AsyncJobAcknowledged.forJob(jobId)
                                : AsyncJobAcknowledged.forOpenJob(jobId, waited.job().getStatus())));
            });
        });
    }

    /** RFC 7240: {@code Prefer: respond-async} asks for the job, whatever the key is set to. */
    private static boolean prefersAsync(String prefer) {
        if (prefer == null) return false;
        for (String preference : prefer.split("[,;]")) {
            if (preference.trim().equalsIgnoreCase("respond-async")) return true;
        }
        return false;
    }

    /** Whether a response that carries the images carries any as base64. */
    private static boolean inlines(ImageResponseFormat format, String model) {
        return format == ImageResponseFormat.B64_JSON
                || (format == null && ImageResponseFormat.inlineByDefault(model));
    }
}
