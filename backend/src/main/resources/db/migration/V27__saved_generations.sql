-- V27: saved generations (round 9).
--
-- Every job's files and prompt are deleted 90 days after it was created
-- (DataRetentionService). A user can save a finished generation to keep it
-- past that date. saved_at is when it was saved; NULL means the normal rule
-- applies. An account can save godance.retention.saved-limit jobs, 500 by
-- default.
--
-- Un-saving clears the column. A job already older than the retention
-- period is then purged at the next nightly run.
--
-- Closing an account still purges every job, saved or not. The purge clears
-- saved_at with the prompt, so a purged job is never a saved one; the check
-- below holds that for every writer.

ALTER TABLE generation_jobs
    ADD COLUMN saved_at TIMESTAMPTZ,
    ADD CONSTRAINT ck_generation_jobs_saved_is_kept
        CHECK (saved_at IS NULL OR (status = 'DONE' AND purged_at IS NULL));

COMMENT ON COLUMN generation_jobs.saved_at IS
    'When the owner saved the job, which keeps its files and prompt past the retention period; NULL when not saved';

-- "This user's saved jobs", newest first, and the count held against the limit.
CREATE INDEX ix_generation_jobs_user_saved
    ON generation_jobs (user_id, created_at DESC, id DESC)
    WHERE saved_at IS NOT NULL;

-- The nightly purge reads ended, unpurged, unsaved jobs oldest first. Rebuilt
-- without the saved ones, which would otherwise stay in it for good and be
-- read past every night; it again holds only about 90 days of jobs.
DROP INDEX ix_generation_jobs_purge_candidates;

CREATE INDEX ix_generation_jobs_purge_candidates
    ON generation_jobs (created_at, id)
    WHERE purged_at IS NULL AND saved_at IS NULL AND status IN ('DONE', 'FAILED', 'CANCELLED');
