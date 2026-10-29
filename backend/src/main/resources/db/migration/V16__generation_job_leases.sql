-- =====================================================================
-- Godance V16 — make an accepted media job recoverable (spec risks R-03,
-- R-05).
--
-- An image or video job runs on an in-memory worker. A deploy, a crash or
-- an unhandled failure used to leave its row QUEUED or RUNNING with the
-- pre-debit kept and nothing left to finish or refund it.
--
-- request_id names the job's ledger pre-debit, so its refund reuses the
-- same idempotency key and can never be paid twice. lease_expires_at is
-- the latest time a live worker can still be on the job; past it,
-- GenerationJobSweepJob ends the job FAILED and refunds it. Rows written
-- before V16 have neither and are swept once they are 30 minutes old.
-- =====================================================================

ALTER TABLE generation_jobs
    ADD COLUMN request_id       VARCHAR(64),
    ADD COLUMN lease_expires_at TIMESTAMPTZ;

COMMENT ON COLUMN generation_jobs.request_id IS
    'Ledger request id of the job''s pre-debit; its refund key is refund-<request_id>';
COMMENT ON COLUMN generation_jobs.lease_expires_at IS
    'Latest time a live worker can still hold the job; an open job past it is failed and refunded';

-- The sweep reads only open jobs.
CREATE INDEX ix_generation_jobs_open_lease
    ON generation_jobs (lease_expires_at)
    WHERE status IN ('QUEUED', 'RUNNING');
