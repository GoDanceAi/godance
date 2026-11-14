package ai.godance.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.GenerationJobOutput;
import ai.godance.domain.JobStatus;
import ai.godance.domain.Modality;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.storage.ObjectStorageClient;
import ai.godance.storage.ObjectStorageClient.StoredObject;
import ai.godance.web.dto.ImageDtos.JobOutputView;
import ai.godance.web.dto.ImageDtos.JobView;

/**
 * Reads a caller's generation jobs for {@code GET /v1/jobs} and
 * {@code GET /v1/jobs/{id}}: owner only, every output with a link signed at
 * read time, and the list paged by {@code (created_at, id)} and optionally
 * narrowed to saved jobs.
 *
 * <p>A job finished before V23 has no output rows. The first read of such a
 * job lists its storage prefix and records what it finds; reads after that
 * use the rows. Those jobs are gone within the 90-day retention.
 */
@Service
public class JobReadService {

    private static final Logger log = LoggerFactory.getLogger(JobReadService.class);

    /** Largest page {@code limit} accepts. */
    public static final int MAX_LIMIT = 100;

    /** A cursor after every job: the first page. */
    private static final Instant END_OF_TIME = Instant.parse("9999-12-31T23:59:59Z");
    private static final UUID HIGHEST_UUID = new UUID(-1L, -1L);

    private final GenerationJobRepository jobs;
    private final GenerationJobOutputRepository outputs;
    private final ObjectStorageClient storage;

    public JobReadService(GenerationJobRepository jobs, GenerationJobOutputRepository outputs,
                          ObjectStorageClient storage) {
        this.jobs = jobs;
        this.outputs = outputs;
        this.storage = storage;
    }

    /** One of the caller's jobs; 404 for an unknown id or another user's job. */
    public JobView get(UUID userId, UUID jobId) {
        GenerationJob job = jobs.findById(jobId)
                .filter(j -> j.getUserId().equals(userId))
                // 404 (not 403) on cross-user lookup — same leak-prevention
                // pattern as ApiKeyService.delete.
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
        return view(job, outputs.findByJobIdOrderByOutputIndexAsc(job.getId()));
    }

    /**
     * The caller's jobs, newest first. {@code limit} null returns them all, as
     * before paging existed. {@code before} is one of the caller's job ids
     * (400 otherwise) and returns only jobs older than it. Null filters match
     * any; {@code saved} true keeps only saved jobs, false only the others.
     */
    public List<JobView> list(UUID userId, Integer limit, UUID before, Modality modality, JobStatus status,
                              Boolean saved) {
        if (limit != null && (limit < 1 || limit > MAX_LIMIT)) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "limit must be between 1 and " + MAX_LIMIT);
        }
        Instant beforeCreatedAt = END_OF_TIME;
        UUID beforeId = HIGHEST_UUID;
        if (before != null) {
            GenerationJob cursor = jobs.findById(before)
                    .filter(j -> j.getUserId().equals(userId))
                    .orElseThrow(() -> new ResponseStatusException(HttpStatus.BAD_REQUEST,
                            "before must be the id of one of your jobs"));
            beforeCreatedAt = cursor.getCreatedAt();
            beforeId = cursor.getId();
        }
        String kind = modality == null ? "" : modality.name();
        String state = status == null ? "" : status.name();
        int pageSize = limit == null ? Integer.MAX_VALUE : limit;
        List<GenerationJob> page = Boolean.TRUE.equals(saved)
                ? jobs.findSavedPageForUser(userId, kind, state, beforeCreatedAt, beforeId, pageSize)
                : jobs.findPageForUser(userId, kind, state, Boolean.FALSE.equals(saved),
                        beforeCreatedAt, beforeId, pageSize);
        if (page.isEmpty()) return List.of();

        Map<UUID, List<GenerationJobOutput>> byJob = new HashMap<>();
        for (GenerationJobOutput o : outputs.findByJobIdInOrderByJobIdAscOutputIndexAsc(
                page.stream().map(GenerationJob::getId).toList())) {
            byJob.computeIfAbsent(o.getJobId(), k -> new ArrayList<>()).add(o);
        }
        return page.stream()
                .map(j -> view(j, byJob.getOrDefault(j.getId(), List.of())))
                .toList();
    }

    private JobView view(GenerationJob job, List<GenerationJobOutput> recorded) {
        List<GenerationJobOutput> rows = recorded;
        if (rows.isEmpty() && needsBackfill(job)) {
            rows = backfill(job);
        }
        List<JobOutputView> shown = job.getPurgedAt() != null ? List.of() : rows.stream()
                .map(o -> new JobOutputView(o.getOutputIndex(), storage.presignedUrl(o.getStorageKey()),
                        o.getContentType(), o.getSizeBytes(), o.getRevisedPrompt()))
                .toList();
        return JobView.from(job, shown, DataRetentionService.mediaExpiresAt(job));
    }

    /** A DONE, unpurged job with no rows finished before V23: every DONE job stored at least one file. */
    private static boolean needsBackfill(GenerationJob job) {
        return job.getStatus() == JobStatus.DONE && job.getPurgedAt() == null;
    }

    /**
     * Lists the job's storage prefix and records each {@code <i>.<ext>} found.
     * A storage failure leaves the job without outputs for this read; the next
     * read tries again.
     */
    private List<GenerationJobOutput> backfill(GenerationJob job) {
        String prefix = (job.getModality() == Modality.VIDEO ? "videos/" : "images/") + job.getId() + "/";
        List<StoredObject> found;
        try {
            found = storage.list(prefix);
        } catch (RuntimeException e) {
            log.warn("Could not list stored outputs of job {}: {}", job.getId(), e.getMessage());
            return List.of();
        }
        int recorded = 0;
        for (StoredObject o : found) {
            String name = o.key().substring(prefix.length());
            int dot = name.indexOf('.');
            Integer index = parseIndex(dot < 0 ? name : name.substring(0, dot));
            if (index == null) continue;
            String ext = dot < 0 ? "" : name.substring(dot + 1);
            recorded += outputs.insertFoundInStorage(job.getId(), index, o.key(), contentTypeFor(ext), o.sizeBytes());
        }
        if (recorded > 0) {
            log.info("Recorded {} stored output(s) of job {}, finished before V23", recorded, job.getId());
        }
        return found.isEmpty() ? List.of() : outputs.findByJobIdOrderByOutputIndexAsc(job.getId());
    }

    private static Integer parseIndex(String s) {
        if (s.isEmpty() || s.length() > 6 || !s.chars().allMatch(Character::isDigit)) return null;
        return Integer.parseInt(s);
    }

    /** The reverse of the workers' {@code mimeToExt}. */
    private static String contentTypeFor(String ext) {
        return switch (ext) {
            case "png" -> "image/png";
            case "jpg" -> "image/jpeg";
            case "webp" -> "image/webp";
            case "mp4" -> "video/mp4";
            case "webm" -> "video/webm";
            default -> "application/octet-stream";
        };
    }
}
