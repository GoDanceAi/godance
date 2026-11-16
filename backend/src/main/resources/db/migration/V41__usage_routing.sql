-- Round 14 brief 04, round 4's Phase 2: which routing policy and which of its
-- rules served a request made to a virtual model id (godance-fast,
-- godance-auto, godance-quality). Set on every usage record such a request
-- writes, the served attempt's and any failed attempt's, so the rows join
-- into a margin per policy and rule (GET /v1/admin/routing/margins).
-- NULL: a direct model call, and every record written before this.
ALTER TABLE usage_records
    ADD COLUMN routed_policy VARCHAR(128),
    ADD COLUMN routed_rule   VARCHAR(128),
    ADD CONSTRAINT ck_usage_records_routed_rule
        CHECK (routed_rule IS NULL OR routed_policy IS NOT NULL);

COMMENT ON COLUMN usage_records.routed_policy IS
    'The virtual model id the caller asked for, when routing chose the model; NULL for a direct call.';
COMMENT ON COLUMN usage_records.routed_rule IS
    'The policy rule that matched (or "default"), when routed_policy is set.';

-- The margin view reads routed rows by time range.
CREATE INDEX ix_usage_records_routed_recorded ON usage_records (recorded_at)
    WHERE routed_policy IS NOT NULL;

-- Round 4's Phase 3: a user's own routing policies (design §6), offered only
-- while godance.routing.user-policies.enabled is on. The policy itself is one
-- validated JSON document; nothing queries inside it, so its rule schema can
-- grow without a migration. The id is the user's name for it, kept apart from
-- every other account's by the owner: usr_<user id>:<name>.
CREATE TABLE routing_policies (
    id          VARCHAR(128) PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name        VARCHAR(63)  NOT NULL,
    modality    VARCHAR(16)  NOT NULL,
    enabled     BOOLEAN      NOT NULL DEFAULT TRUE,
    policy_json JSONB        NOT NULL,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_routing_policies_user_name UNIQUE (user_id, name),
    CONSTRAINT ck_routing_policies_id CHECK (id = 'usr_' || user_id::text || ':' || name),
    CONSTRAINT ck_routing_policies_name CHECK (name ~ '^[a-z0-9][a-z0-9._-]{0,62}$')
);

-- Round 14's addition to the design: a batch that opts in with "fallbacks": true
-- retries a row once on another model when its job fails on a provider error
-- or a timeout, following the fallback rules of its owner's policies.
ALTER TABLE recipe_batches
    ADD COLUMN fallbacks BOOLEAN NOT NULL DEFAULT FALSE;

-- A row of such a batch that was retried: the model its new job ran on, and the
-- failed job that one replaced. Both NULL for a row that was not retried, whose
-- model is the batch's.
ALTER TABLE recipe_batch_rows
    ADD COLUMN model          VARCHAR(128),
    ADD COLUMN retried_job_id UUID;

COMMENT ON COLUMN recipe_batch_rows.model IS
    'The model of the row''s job when a fallback replaced the batch''s model; NULL otherwise';
COMMENT ON COLUMN recipe_batch_rows.retried_job_id IS
    'The failed job a fallback replaced; NULL when the row was not retried';

-- One live job per row (V36 allowed one ever): a failed or cancelled job no
-- longer counts, so a fallback's job can belong to the row its first job
-- failed in. A row still never has two jobs that can run or be charged at once.
DROP INDEX uq_generation_jobs_batch_row;
CREATE UNIQUE INDEX uq_generation_jobs_batch_row
    ON generation_jobs (batch_row_id)
    WHERE batch_row_id IS NOT NULL AND status NOT IN ('FAILED', 'CANCELLED');
