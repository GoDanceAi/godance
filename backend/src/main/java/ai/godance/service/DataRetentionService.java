package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import ai.godance.domain.GenerationJob;
import ai.godance.repository.AuditLogRetentionRepository;
import ai.godance.repository.ConversationRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.storage.ObjectStorageClient;

/**
 * The deletions the Privacy Policy promises, run nightly by
 * {@link DataRetentionJob}:
 * <ul>
 *   <li>generated media and job prompts: 90 days after the job was created,
 *       once it has ended (DONE, FAILED or CANCELLED);</li>
 *   <li>archived conversations, with their messages: 30 days after archiving;</li>
 *   <li>audit log rows: 365 days after they were written.</li>
 * </ul>
 * Billing records (credit ledger, usage records, auto-recharge attempts)
 * are never deleted. A purged job keeps its cost columns.
 *
 * <p>Work is done in bounded batches, each its own transaction, with a
 * per-run ceiling; a backlog larger than the ceiling carries over to the
 * next night.
 */
@Service
public class DataRetentionService {

    private static final Logger log = LoggerFactory.getLogger(DataRetentionService.class);

    public static final Duration JOB_RETENTION = Duration.ofDays(90);
    public static final Duration ARCHIVED_CONVERSATION_RETENTION = Duration.ofDays(30);
    public static final Duration AUDIT_LOG_RETENTION = Duration.ofDays(365);

    /** Jobs per batch; each job costs two object-storage listings and at most two deletes. */
    static final int JOB_BATCH = 100;
    static final int MAX_JOB_BATCHES = 200;
    /** Rows per batch for the plain table deletes. */
    static final int ROW_BATCH = 1_000;
    static final int MAX_ROW_BATCHES = 100;

    /** Where a job's media lives: {@code images/<jobId>/<i>.<ext>} and {@code videos/<jobId>/<i>.<ext>}. */
    private static final List<String> MEDIA_PREFIXES = List.of("images/", "videos/");

    private static final UUID LOWEST_UUID = new UUID(0L, 0L);

    private final GenerationJobRepository jobs;
    private final ConversationRepository conversations;
    private final AuditLogRetentionRepository auditRows;
    private final ObjectStorageClient storage;

    public DataRetentionService(GenerationJobRepository jobs, ConversationRepository conversations,
                                AuditLogRetentionRepository auditRows, ObjectStorageClient storage) {
        this.jobs = jobs;
        this.conversations = conversations;
        this.auditRows = auditRows;
        this.storage = storage;
    }

    /** How many jobs a pass purged, and how many it could not (left for the next run). */
    public record JobPurgeCount(int purged, int failed) { }

    /** Purges every ended job created more than {@link #JOB_RETENTION} before {@code now}. */
    public JobPurgeCount purgeExpiredJobs(Instant now) {
        Instant cutoff = now.minus(JOB_RETENTION);
        Instant afterCreatedAt = Instant.EPOCH;
        UUID afterId = LOWEST_UUID;
        int purged = 0;
        int failed = 0;
        for (int batch = 0; batch < MAX_JOB_BATCHES; batch++) {
            // Keyset paging, so a job that fails to purge is passed over this run, not re-read.
            List<GenerationJob> page = jobs.findPurgeCandidates(cutoff, afterCreatedAt, afterId, JOB_BATCH);
            for (GenerationJob job : page) {
                if (purgeJob(job.getId(), now)) purged++;
                else failed++;
            }
            if (page.size() < JOB_BATCH) {
                return new JobPurgeCount(purged, failed);
            }
            GenerationJob last = page.get(page.size() - 1);
            afterCreatedAt = last.getCreatedAt();
            afterId = last.getId();
        }
        log.warn("DataRetention: stopped after {} generation jobs this run; the rest are purged next run",
                MAX_JOB_BATCHES * JOB_BATCH);
        return new JobPurgeCount(purged, failed);
    }

    /**
     * Deletes one ended job's media objects, then redacts its row. Storage
     * goes first: if it fails the row is left untouched and the job is tried
     * again on the next run. False when anything failed.
     */
    public boolean purgeJob(UUID jobId, Instant now) {
        try {
            for (String prefix : MEDIA_PREFIXES) {
                storage.deletePrefix(prefix + jobId + "/");
            }
            jobs.redactPurged(jobId, now);
            return true;
        } catch (RuntimeException e) {
            log.warn("DataRetention: could not purge generation job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    /** Deletes conversations archived more than {@link #ARCHIVED_CONVERSATION_RETENTION} before {@code now}. */
    public int deleteArchivedConversations(Instant now) {
        Instant cutoff = now.minus(ARCHIVED_CONVERSATION_RETENTION);
        return deleteInBatches(limit -> conversations.deleteArchivedBefore(cutoff, limit), "archived conversations");
    }

    /** Deletes audit rows written more than {@link #AUDIT_LOG_RETENTION} before {@code now}. */
    public int deleteExpiredAuditRows(Instant now) {
        Instant cutoff = now.minus(AUDIT_LOG_RETENTION);
        return deleteInBatches(limit -> auditRows.deleteCreatedBefore(cutoff, limit), "audit rows");
    }

    private static int deleteInBatches(IntUnaryOperator deleteBatch, String what) {
        int total = 0;
        for (int batch = 0; batch < MAX_ROW_BATCHES; batch++) {
            int deleted = deleteBatch.applyAsInt(ROW_BATCH);
            total += deleted;
            if (deleted < ROW_BATCH) {
                return total;
            }
        }
        log.warn("DataRetention: stopped after {} {} this run; the rest are deleted next run", total, what);
        return total;
    }
}
