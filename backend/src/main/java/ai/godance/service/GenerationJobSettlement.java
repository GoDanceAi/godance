package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import ai.godance.domain.GenerationJob;
import ai.godance.domain.JobStatus;
import ai.godance.domain.UsageRecord;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UsageRecordRepository;

/**
 * Every money-bearing step of an image or video job (spec risks R-03, R-05).
 *
 * <p>A job is accepted with its pre-debit in one transaction, and it ends
 * exactly once: DONE with its usage row, or FAILED with its estimate
 * refunded, each in one transaction. Ending is a conditional update on an
 * open job, so the worker and {@link GenerationJobSweepJob} can race and
 * only the first one settles; the other's update matches no row and it
 * does nothing. The refund reuses the pre-debit's request id, so even a
 * repeated refund is paid once.
 */
@Service
public class GenerationJobSettlement {

    /** How long a job from before V16 (no lease) may stay open before it counts as abandoned. */
    static final Duration LEGACY_ABANDON_AFTER = Duration.ofMinutes(30);

    private final GenerationJobRepository jobs;
    private final CreditLedgerService ledger;
    private final UsageRecordRepository usage;

    public GenerationJobSettlement(GenerationJobRepository jobs, CreditLedgerService ledger,
                                   UsageRecordRepository usage) {
        this.jobs = jobs;
        this.ledger = ledger;
        this.usage = usage;
    }

    /**
     * Pre-debits the job's estimate and saves the QUEUED job, together. False
     * (and nothing saved) when the balance can't cover the estimate.
     */
    @Transactional
    public boolean accept(GenerationJob job) {
        if (!ledger.preDebit(job.getUserId(), job.getEstimatedCostCents(), job.getRequestId())) {
            return false;
        }
        job.setStatus(JobStatus.QUEUED);
        jobs.save(job);
        return true;
    }

    /** QUEUED → RUNNING. False when the job already ended (the sweep got to it first). */
    @Transactional
    public boolean start(GenerationJob job) {
        Instant now = Instant.now();
        if (jobs.startIfQueued(job.getId(), now) == 0) return false;
        job.setStatus(JobStatus.RUNNING);
        job.setStartedAt(now);
        return true;
    }

    /**
     * Ends an open job DONE and writes its usage row, together. False when
     * the job already ended; then nothing is written, and the estimate was
     * already refunded by whoever ended it.
     */
    @Transactional
    public boolean complete(GenerationJob job, String outputUrl, long actualCostCents, UsageRecord usageRecord) {
        Instant now = Instant.now();
        if (jobs.completeIfOpen(job.getId(), outputUrl, actualCostCents, now) == 0) return false;
        usage.save(usageRecord);
        job.setStatus(JobStatus.DONE);
        job.setOutputUrl(outputUrl);
        job.setActualCostCents(actualCostCents);
        job.setCompletedAt(now);
        return true;
    }

    /**
     * Ends an open job FAILED and refunds its estimate, together. False when
     * the job already ended; then nothing changes.
     */
    @Transactional
    public boolean fail(GenerationJob job, String error, String refundReason) {
        Instant now = Instant.now();
        if (jobs.failIfOpen(job.getId(), error, now) == 0) return false;
        Long estimate = job.getEstimatedCostCents();
        if (estimate != null) {
            ledger.refundEstimate(job.getUserId(), estimate, refundRequestId(job), refundReason);
        }
        job.setStatus(JobStatus.FAILED);
        job.setErrorMessage(error);
        job.setCompletedAt(now);
        return true;
    }

    /** Open jobs whose worker can no longer be running. */
    @Transactional(readOnly = true)
    public List<GenerationJob> findAbandoned() {
        Instant now = Instant.now();
        return jobs.findAbandoned(now, now.minus(LEGACY_ABANDON_AFTER));
    }

    /**
     * The pre-debit's request id, whose {@code refund-} key the worker's own
     * refunds use too. A job from before V16 never recorded it, so its refund
     * is keyed on the job id instead; still at most once per job.
     */
    private static String refundRequestId(GenerationJob job) {
        return job.getRequestId() != null ? job.getRequestId() : "job-" + job.getId();
    }
}
