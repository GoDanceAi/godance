package ai.godance.service;

import java.io.InputStream;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import ai.godance.domain.AuditOutcome;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.gateway.ModelCatalog;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.web.dto.OpenAiVideoDtos.Deleted;
import ai.godance.web.dto.OpenAiVideoDtos.Video;
import ai.godance.web.dto.OpenAiVideoDtos.VideoError;
import ai.godance.web.dto.OpenAiVideoDtos.VideoPage;

/**
 * Godance video jobs seen as OpenAI's videos ({@code /v1/videos}). Nothing
 * is stored for this view: a video is a job, its id is the job's id, and
 * every answer is read from the job's own rows. A job made at
 * {@code POST /v1/videos/generations} is a video here as much as one made
 * at {@code POST /v1/videos}.
 *
 * <p>A video is one of the caller's jobs of modality VIDEO whose files have
 * not been deleted. Anything else answers {@code video_not_found}, another
 * user's job included, so an id says nothing about what exists. Once a
 * video is deleted here, or purged by the retention run, it is no longer a
 * video; the job stays at {@code /v1/jobs/{id}} with what it cost.
 */
@Service
public class OpenAiVideos {

    /** Page size when {@code limit} is left out, and the largest it accepts. */
    public static final int DEFAULT_LIMIT = 20;
    public static final int MAX_LIMIT = 100;

    /** Audit event of a video deleted by its owner; the detail carries the job's id. */
    public static final String EVT_VIDEO_DELETED = "video.deleted";

    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID HIGHEST_UUID = new UUID(-1L, -1L);
    private static final UUID LOWEST_UUID = new UUID(0L, 0L);

    private final GenerationJobRepository jobs;
    private final GenerationJobOutputRepository outputs;
    private final ObjectStorageClient storage;
    private final ModelCatalog catalog;
    private final DataRetentionService retention;
    private final AuditLogService audit;

    public OpenAiVideos(GenerationJobRepository jobs, GenerationJobOutputRepository outputs,
                        ObjectStorageClient storage, ModelCatalog catalog, DataRetentionService retention,
                        AuditLogService audit) {
        this.jobs = jobs;
        this.outputs = outputs;
        this.storage = storage;
        this.catalog = catalog;
        this.retention = retention;
        this.audit = audit;
    }

    /** Which of a video's files {@code content} returns. */
    public enum Variant {
        /** The clip itself: the job's first output that is not an image. */
        VIDEO,
        /** A poster frame: the job's first output that is an image. No adapter stores one today. */
        THUMBNAIL;

        /**
         * {@code video} when the request names none.
         *
         * @throws OpenAiVideoRefusedException 400 {@code variant_not_supported} for any other value
         *         ({@code spritesheet} is one OpenAI offers)
         */
        public static Variant parse(String raw) {
            if (raw == null || raw.isBlank() || raw.equals("video")) return VIDEO;
            if (raw.equals("thumbnail")) return THUMBNAIL;
            throw OpenAiVideoRefusedException.variantNotSupported(raw);
        }
    }

    /** One of the caller's videos. */
    public Video get(UUID userId, UUID videoId) {
        return view(owned(userId, videoId));
    }

    /**
     * The video a job is at the moment it was accepted: {@code queued}. Its
     * worker may already have started; the first read shows where it is.
     */
    public Video accepted(UUID userId, UUID jobId) {
        return view(owned(userId, jobId)).asAccepted();
    }

    /**
     * One page of the caller's videos, newest first unless {@code oldestFirst}.
     * {@code after} is the id of one of the caller's video jobs and returns
     * only the videos past it in that order; a video deleted since it was
     * listed still works as a cursor, so a caller can page while deleting.
     *
     * @throws OpenAiVideoRefusedException 400 {@code invalid_parameter} for a
     *         {@code limit} outside 1 to {@link #MAX_LIMIT} or an
     *         {@code after} that is not one of the caller's videos
     */
    public VideoPage list(UUID userId, Integer limit, UUID after, boolean oldestFirst) {
        int pageSize = limit == null ? DEFAULT_LIMIT : limit;
        if (pageSize < 1 || pageSize > MAX_LIMIT) {
            throw OpenAiVideoRefusedException.invalid("limit", "limit must be between 1 and " + MAX_LIMIT);
        }
        Instant cursorCreatedAt = oldestFirst ? Instant.EPOCH : END_OF_TIME;
        UUID cursorId = oldestFirst ? LOWEST_UUID : HIGHEST_UUID;
        if (after != null) {
            GenerationJob cursor = jobs.findById(after)
                    .filter(j -> j.getUserId().equals(userId) && j.getModality() == Modality.VIDEO)
                    .orElseThrow(() -> OpenAiVideoRefusedException.invalid("after",
                            "after must be the id of one of your videos"));
            cursorCreatedAt = cursor.getCreatedAt();
            cursorId = cursor.getId();
        }
        // One more than the page, to know whether there is a next one.
        List<GenerationJob> found = oldestFirst
                ? jobs.findVideosOldestFirst(userId, cursorCreatedAt, cursorId, pageSize + 1)
                : jobs.findVideosNewestFirst(userId, cursorCreatedAt, cursorId, pageSize + 1);
        boolean hasMore = found.size() > pageSize;
        List<Video> page = found.stream().limit(pageSize).map(this::view).toList();
        return VideoPage.of(page, hasMore);
    }

    /**
     * A video's file, opened for streaming: its bytes, the content type it
     * was stored with, and its size as the job recorded it (0 when it did not).
     */
    public record Content(InputStream bytes, String contentType, long sizeBytes) { }

    /**
     * Opens one of a video's files. The caller streams it to the end or
     * closes it.
     *
     * @throws OpenAiVideoRefusedException 404 {@code video_not_found}; 409
     *         {@code video_not_ready} while it is queued or in progress; 404
     *         {@code video_content_not_found} when it failed or has no file
     *         of that kind; 503 {@code video_content_unavailable} when
     *         storage could not hand the file over
     */
    public Content content(UUID userId, UUID videoId, Variant variant) {
        GenerationJob job = owned(userId, videoId);
        switch (job.getStatus()) {
            case QUEUED, RUNNING -> throw OpenAiVideoRefusedException.notReady(statusOf(job));
            case FAILED, CANCELLED -> throw OpenAiVideoRefusedException.contentNotFound(
                    "The video failed, so it has no content.");
            case DONE -> { }
        }
        GenerationJobOutput file = outputs.findByJobIdOrderByOutputIndexAsc(job.getId()).stream()
                .filter(output -> isImage(output) == (variant == Variant.THUMBNAIL))
                .findFirst()
                .orElseThrow(() -> OpenAiVideoRefusedException.contentNotFound(variant == Variant.THUMBNAIL
                        ? "This video has no thumbnail."
                        : "This video has no stored file."));
        try {
            return new Content(storage.open(file.getStorageKey()), file.getContentType(), file.getSizeBytes());
        } catch (IllegalStateException e) {
            // Storage could not hand the file over: it is down, or the file is being deleted right now.
            throw OpenAiVideoRefusedException.contentUnavailable();
        }
    }

    /**
     * Deletes one of the caller's videos now: its files, its prompt and its
     * inputs go exactly as the retention run would delete them at the end of
     * the 90 days ({@link DataRetentionService#purgeJob}), saved or not. The
     * ledger is not touched: what the video cost stays charged, and the job
     * stays at {@code /v1/jobs/{id}} as a purged job.
     *
     * @throws OpenAiVideoRefusedException 404 {@code video_not_found}; 409
     *         {@code video_not_deletable} while it is queued or in progress
     *         (ending it would mean a refund, and delete never refunds); 503
     *         {@code video_delete_failed} when storage could not delete
     */
    public Deleted delete(UUID userId, UUID videoId, String ip) {
        GenerationJob job = owned(userId, videoId);
        if (job.getStatus() == JobStatus.QUEUED || job.getStatus() == JobStatus.RUNNING) {
            throw OpenAiVideoRefusedException.notDeletable(statusOf(job));
        }
        if (!retention.purgeJob(job.getId(), Instant.now())) {
            throw OpenAiVideoRefusedException.deleteFailed();
        }
        audit.record(EVT_VIDEO_DELETED, AuditOutcome.SUCCESS, userId, ip, Map.of("job_id", job.getId().toString()));
        return Deleted.of(job.getId());
    }

    /** 404 for anything that is not one of the caller's videos; another user's answers the same as none. */
    private GenerationJob owned(UUID userId, UUID videoId) {
        return jobs.findById(videoId)
                .filter(j -> j.getUserId().equals(userId)
                        && j.getModality() == Modality.VIDEO
                        && j.getPurgedAt() == null)
                .orElseThrow(OpenAiVideoRefusedException::notFound);
    }

    // ===== Job → video ================================================

    private Video view(GenerationJob job) {
        Map<String, Object> request = job.getPrompt() == null ? Map.of() : job.getPrompt();
        JobStatus status = job.getStatus();
        boolean failed = status == JobStatus.FAILED || status == JobStatus.CANCELLED;
        Instant expires = DataRetentionService.mediaExpiresAt(job);
        return new Video(
                job.getId(),
                Video.OBJECT,
                job.getModel(),
                statusOf(job),
                status == JobStatus.DONE ? 100 : 0,
                job.getCreatedAt().getEpochSecond(),
                job.getCompletedAt() == null ? null : job.getCompletedAt().getEpochSecond(),
                expires == null ? null : expires.getEpochSecond(),
                request.get("prompt") instanceof String prompt ? prompt : null,
                request.get("duration_seconds") instanceof Number seconds ? String.valueOf(seconds.intValue()) : null,
                sizeOf(job, request),
                failed ? errorOf(job) : null,
                null,
                costOf(job),
                "/v1/jobs/" + job.getId());
    }

    /** OpenAI's status for a job's. A cancelled job reads as failed: OpenAI has no other ended state. */
    private static String statusOf(GenerationJob job) {
        return switch (job.getStatus()) {
            case QUEUED -> "queued";
            case RUNNING -> "in_progress";
            case DONE -> "completed";
            case FAILED, CANCELLED -> "failed";
        };
    }

    /** The size the request named; else the size the model makes when none is named, where the catalog says. */
    private String sizeOf(GenerationJob job, Map<String, Object> request) {
        if (request.get("size") instanceof String size && !size.isBlank()) return size;
        return catalog.metadata(job.getModel()).capabilities().defaultSize();
    }

    private static VideoError errorOf(GenerationJob job) {
        boolean cancelled = job.getStatus() == JobStatus.CANCELLED;
        String message = job.getErrorMessage();
        if (message == null || message.isBlank()) {
            message = cancelled ? "The video was cancelled." : "The video could not be generated.";
        }
        return new VideoError(cancelled ? "generation_cancelled" : "generation_failed", message);
    }

    /** Held while it runs, charged once done, nothing when it failed (the hold was returned). */
    private static long costOf(GenerationJob job) {
        Long cents = switch (job.getStatus()) {
            case QUEUED, RUNNING -> job.getEstimatedCostCents();
            case DONE -> job.getActualCostCents() != null ? job.getActualCostCents() : job.getEstimatedCostCents();
            case FAILED, CANCELLED -> 0L;
        };
        return cents == null ? 0L : cents;
    }

    private static boolean isImage(GenerationJobOutput output) {
        return output.getContentType() != null && output.getContentType().startsWith("image/");
    }
}
