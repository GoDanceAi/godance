package ai.godance.service;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import ai.godance.config.RetentionProperties;
import ai.godance.domain.AuditOutcome;
import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.repository.GenerationJobRepository;

/**
 * Saving a generation: the owner's way of keeping one past the 90-day
 * deletion ({@link DataRetentionService}). {@code PUT} and
 * {@code DELETE /v1/jobs/{id}/saved}.
 *
 * <ul>
 *   <li>Owner only. Another user's job is a 404, as it is to read.</li>
 *   <li>Only a DONE job whose files still exist can be saved; anything else
 *       is a 409 {@code job_not_saveable}.</li>
 *   <li>An account can save {@code godance.retention.saved-limit} jobs. One
 *       more is a 409 {@code saved_limit_reached}. The count and the write
 *       happen under one lock per user, so requests made at once cannot
 *       together pass the limit.</li>
 *   <li>Both calls are idempotent: saving a saved job, or un-saving one that
 *       is not saved, succeeds and changes nothing.</li>
 * </ul>
 *
 * Saving changes nothing about what the job cost. Un-saving puts the job
 * back under the normal rule, so one already older than the retention
 * period is purged at the next nightly run. Closing the account deletes
 * saved jobs with the rest ({@link AccountClosureService}).
 */
@Service
@EnableConfigurationProperties(RetentionProperties.class)
public class JobSaveService {

    /** First key of the per-user advisory lock; the second is the user id's hash. */
    static final int SAVED_LOCK_SPACE = 7_000_027;

    private final GenerationJobRepository jobs;
    private final AuditLogService audit;
    private final RetentionProperties props;

    public JobSaveService(GenerationJobRepository jobs, AuditLogService audit, RetentionProperties props) {
        this.jobs = jobs;
        this.audit = audit;
        this.props = props;
    }

    /** How many generations an account has saved, and the most it can. */
    public record SavedCount(long count, int limit) { }

    public SavedCount count(UUID userId) {
        return new SavedCount(jobs.countSavedByUserId(userId), props.savedLimit());
    }

    /**
     * Saves the caller's job.
     *
     * @throws ResponseStatusException 404 for an unknown id or another user's job
     * @throws JobSaveRefusedException 409 {@code job_not_saveable} or
     *         {@code saved_limit_reached}
     */
    @Transactional
    public void save(UUID userId, UUID jobId, String ip) {
        // Before the job is read, so two saves by one user see each other's result.
        jobs.lockSavedJobsOf(SAVED_LOCK_SPACE, userId.hashCode());
        GenerationJob job = owned(userId, jobId);
        if (job.getSavedAt() != null) return;
        if (job.getPurgedAt() != null) throw JobSaveRefusedException.purged();
        if (job.getStatus() != JobStatus.DONE) throw JobSaveRefusedException.notFinished();

        long saved = jobs.countSavedByUserId(userId);
        if (saved >= props.savedLimit()) {
            throw JobSaveRefusedException.limitReached(props.savedLimit());
        }
        // 0 only when the job was purged after it was read above: the nightly
        // run and an account closure do not take this user's lock.
        if (jobs.markSaved(jobId, userId, Instant.now()) == 0) {
            throw JobSaveRefusedException.purged();
        }
        audit.record(AuditLogService.EVT_JOB_SAVED, AuditOutcome.SUCCESS, userId, ip,
                detail(jobId, saved + 1));
    }

    /**
     * Un-saves the caller's job.
     *
     * @throws ResponseStatusException 404 for an unknown id or another user's job
     */
    @Transactional
    public void unsave(UUID userId, UUID jobId, String ip) {
        owned(userId, jobId);
        if (jobs.clearSaved(jobId, userId) == 0) return;
        audit.record(AuditLogService.EVT_JOB_UNSAVED, AuditOutcome.SUCCESS, userId, ip,
                detail(jobId, jobs.countSavedByUserId(userId)));
    }

    /** 404 (not 403) on another user's job: the same leak-prevention as reading one. */
    private GenerationJob owned(UUID userId, UUID jobId) {
        return jobs.findById(jobId)
                .filter(j -> j.getUserId().equals(userId))
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "job not found"));
    }

    private Map<String, Object> detail(UUID jobId, long savedNow) {
        Map<String, Object> detail = new LinkedHashMap<>();
        detail.put("jobId", jobId.toString());
        detail.put("saved", savedNow);
        detail.put("limit", props.savedLimit());
        return detail;
    }
}
