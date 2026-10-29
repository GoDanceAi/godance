-- =====================================================================
-- Godance V20 — data retention (the Privacy Policy schedule).
--
-- DataRetentionJob runs daily and:
--   * purges generation jobs 90 days after creation once they have ended
--     (DONE / FAILED / CANCELLED): deletes their media objects and redacts
--     the row (prompt_json -> {"purged": true}, output_url and webhook_url
--     -> NULL). Cost columns stay; they are billing records;
--   * deletes conversations 30 days after they were archived (their
--     messages go with them through the V7 ON DELETE CASCADE);
--   * deletes audit_logs rows older than 365 days.
-- AccountClosureService purges all of a closed account's jobs at once.
-- credit_ledger, usage_records and auto_recharge_attempts are never
-- deleted: the ledger is summed for the authoritative balance.
-- =====================================================================

ALTER TABLE generation_jobs
    ADD COLUMN purged_at TIMESTAMPTZ;

COMMENT ON COLUMN generation_jobs.purged_at IS
    'When the job''s media objects were deleted and its prompt, output URL and webhook URL redacted; costs are kept';

-- The daily purge reads ended, unpurged jobs oldest first. Purged rows leave
-- the index, so it only ever holds about 90 days of jobs.
CREATE INDEX ix_generation_jobs_purge_candidates
    ON generation_jobs (created_at, id)
    WHERE purged_at IS NULL AND status IN ('DONE', 'FAILED', 'CANCELLED');

-- The daily delete of archived conversations. V7's only index on the table
-- covers active conversations alone.
CREATE INDEX ix_conversations_archived_at
    ON conversations (archived_at)
    WHERE archived_at IS NOT NULL;
