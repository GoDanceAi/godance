package ai.godance.service;

import java.time.Instant;

import org.quartz.DisallowConcurrentExecution;
import org.quartz.Job;
import org.quartz.JobExecutionContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * Nightly run of the Privacy Policy's retention schedule (see
 * {@link DataRetentionService}): purges generation jobs older than 90 days,
 * deletes conversations archived more than 30 days ago and audit rows older
 * than a year. Bound to a cron trigger (03:30 UTC daily) in
 * {@link ai.godance.config.QuartzJobsConfig}.
 */
@DisallowConcurrentExecution
public class DataRetentionJob implements Job {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionJob.class);

    @Autowired private DataRetentionService retention;

    @Override
    public void execute(JobExecutionContext context) {
        run(retention, Instant.now());
    }

    /** What one pass did. */
    record Summary(int jobsPurged, int jobsFailed, int conversationsDeleted, int auditRowsDeleted) { }

    /**
     * One pass over all three rules. Each rule runs even when an earlier one
     * threw; whatever it left undone is picked up by the next run.
     */
    static Summary run(DataRetentionService retention, Instant now) {
        DataRetentionService.JobPurgeCount jobs = new DataRetentionService.JobPurgeCount(0, 0);
        int conversations = 0;
        int auditRows = 0;
        try {
            jobs = retention.purgeExpiredJobs(now);
        } catch (RuntimeException e) {
            log.error("DataRetentionJob: generation job purge failed", e);
        }
        try {
            conversations = retention.deleteArchivedConversations(now);
        } catch (RuntimeException e) {
            log.error("DataRetentionJob: archived conversation delete failed", e);
        }
        try {
            auditRows = retention.deleteExpiredAuditRows(now);
        } catch (RuntimeException e) {
            log.error("DataRetentionJob: audit row delete failed", e);
        }
        Summary summary = new Summary(jobs.purged(), jobs.failed(), conversations, auditRows);
        log.info("DataRetentionJob: purged {} generation job(s) ({} could not be purged, retried next run), "
                        + "deleted {} archived conversation(s) and {} audit row(s)",
                summary.jobsPurged(), summary.jobsFailed(), summary.conversationsDeleted(),
                summary.auditRowsDeleted());
        return summary;
    }
}
