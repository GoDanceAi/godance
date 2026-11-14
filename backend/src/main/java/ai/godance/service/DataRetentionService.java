package ai.godance.service;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.function.IntUnaryOperator;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import ai.godance.domain.GenerationJob;
import ai.godance.repository.AuditLogRetentionRepository;
import ai.godance.repository.ConversationRepository;
import ai.godance.repository.GenerationJobOutputRepository;
import ai.godance.repository.GenerationJobRepository;
import ai.godance.repository.UnapprovedChildRepository;
import ai.godance.storage.ObjectStorageClient;

/**
 * The deletions the Privacy Policy promises, run nightly by
 * {@link DataRetentionJob}:
 * <ul>
 *   <li>generated media and job prompts: 90 days after the job was created,
 *       once it has ended (DONE, FAILED or CANCELLED), unless its owner has
 *       saved it ({@link JobSaveService}). A saved job is kept until it is
 *       un-saved or the account is closed;</li>
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
    /** COPPA: how long a child's account waits for a parent's consent before it is deleted. */
    public static final Duration UNAPPROVED_CHILD_RETENTION = Duration.ofDays(30);

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
    private final UnapprovedChildRepository unapprovedChildren;
    private final GenerationJobOutputRepository outputs;
    private final TransactionTemplate tx;

    public DataRetentionService(GenerationJobRepository jobs, ConversationRepository conversations,
                                AuditLogRetentionRepository auditRows, ObjectStorageClient storage,
                                UnapprovedChildRepository unapprovedChildren,
                                GenerationJobOutputRepository outputs, PlatformTransactionManager txManager) {
        this.jobs = jobs;
        this.conversations = conversations;
        this.auditRows = auditRows;
        this.storage = storage;
        this.unapprovedChildren = unapprovedChildren;
        this.outputs = outputs;
        this.tx = new TransactionTemplate(txManager);
    }

    /**
     * When a job's files and prompt are due to be deleted: {@link #JOB_RETENTION}
     * after it was created. Null while the job is saved, and once they have
     * been deleted (the job is purged), so null means "not scheduled for
     * deletion"; clients must accept it. A job un-saved after its date has
     * one in the past until the next run deletes it.
     */
    public static Instant mediaExpiresAt(GenerationJob job) {
        if (job.getPurgedAt() != null || job.getSavedAt() != null || job.getCreatedAt() == null) return null;
        return job.getCreatedAt().plus(JOB_RETENTION);
    }

    /** How many jobs a pass purged, and how many it could not (left for the next run). */
    public record JobPurgeCount(int purged, int failed) { }

    /**
     * Purges every ended job created more than {@link #JOB_RETENTION} before
     * {@code now}, except the ones their owners have saved.
     */
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
                switch (purgeUnlessSaved(job.getId(), now)) {
                    case PURGED -> purged++;
                    case FAILED -> failed++;
                    case KEPT -> { }
                }
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
     * Deletes one ended job's media objects, then redacts its row and deletes
     * its output rows (V23) in one transaction. Storage goes first: if it
     * fails the rows are left untouched and the job is tried again on the
     * next run. False when anything failed.
     *
     * <p>Purges a saved job like any other, which is what closing an account
     * needs. The nightly run goes through {@link #purgeUnlessSaved}.
     */
    public boolean purgeJob(UUID jobId, Instant now) {
        try {
            deleteMedia(jobId);
            tx.executeWithoutResult(status -> {
                jobs.redactPurged(jobId, now);
                outputs.deleteByJobId(jobId);
            });
            return true;
        } catch (RuntimeException e) {
            log.warn("DataRetention: could not purge generation job {}: {}", jobId, e.getMessage());
            return false;
        }
    }

    /** What the nightly run did with one job. */
    private enum Outcome { PURGED, FAILED, KEPT }

    /**
     * The nightly purge of one job: {@link #purgeJob}, unless its owner saved
     * it after this run read it. The job's row is locked first and checked
     * again, and the lock is held across the storage deletes, so a save made
     * while they run waits for them and is then refused as purged. Either
     * the save wins and every file stays, or the purge does and the save
     * says so.
     *
     * <p>A storage failure rolls the transaction back, which leaves the rows
     * untouched for the next run, as in {@link #purgeJob}.
     */
    private Outcome purgeUnlessSaved(UUID jobId, Instant now) {
        try {
            Boolean purged = tx.execute(status -> {
                if (jobs.lockIfStillPurgeable(jobId).isEmpty()) return false;
                deleteMedia(jobId);
                jobs.redactPurged(jobId, now);
                outputs.deleteByJobId(jobId);
                return true;
            });
            return Boolean.TRUE.equals(purged) ? Outcome.PURGED : Outcome.KEPT;
        } catch (RuntimeException e) {
            log.warn("DataRetention: could not purge generation job {}: {}", jobId, e.getMessage());
            return Outcome.FAILED;
        }
    }

    private void deleteMedia(UUID jobId) {
        for (String prefix : MEDIA_PREFIXES) {
            storage.deletePrefix(prefix + jobId + "/");
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

    /**
     * Deletes children's accounts whose parent has not given consent
     * {@link #UNAPPROVED_CHILD_RETENTION} after it was last requested, with the
     * parent's email and the audit rows that name it (COPPA: a child's and
     * parent's details are deleted if consent doesn't come in a reasonable
     * time). Such an account could only sign in and wait for consent.
     */
    @Transactional
    public int deleteUnapprovedChildAccounts(Instant now) {
        List<UUID> ids = unapprovedChildren.lockUnapprovedBefore(now.minus(UNAPPROVED_CHILD_RETENTION), ROW_BATCH);
        if (ids.isEmpty()) return 0;
        auditRows.deleteForUsers(ids);
        return unapprovedChildren.deleteUnapproved(ids);
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
