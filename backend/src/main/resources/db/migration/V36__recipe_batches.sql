-- V36: recipe batches (round 12 brief 01).
--
-- A batch runs one version of a recipe over many rows. Each row is a set of
-- values for the recipe's variables, and an input for each of its slots,
-- and becomes one ordinary generation job. The batch holds no money and
-- charges nothing: every cent is the pre-debit and settlement of a row's
-- job, exactly as for a job made one at a time.
--
-- Three things here keep a row from ever being submitted twice:
--   * a row is claimed (RUNNING) before its job is created;
--   * the job records its row (generation_jobs.batch_row_id), in the
--     transaction that takes the job's pre-debit, and that same transaction
--     writes the job's id on the row;
--   * uq_generation_jobs_batch_row allows one job per row, ever.
-- So a second attempt at a row cannot insert a job, and its pre-debit rolls
-- back with it.

CREATE TABLE recipe_batches (
    id                     UUID          PRIMARY KEY,
    user_id                UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- How the batch was made: a signed-in session or an API key.
    created_by_channel     VARCHAR(16)   NOT NULL CHECK (created_by_channel IN ('STUDIO', 'API')),
    -- The key that made it; its jobs count against that key's caps. Cleared
    -- when the key is deleted, and the batch is then cancelled.
    api_key_id             UUID          REFERENCES api_keys(id) ON DELETE SET NULL,
    -- Cleared when the recipe is deleted; the batch is then cancelled.
    recipe_id              UUID          REFERENCES recipes(id) ON DELETE SET NULL,
    recipe_version_id      UUID          REFERENCES recipe_versions(id) ON DELETE SET NULL,
    modality               VARCHAR(16)   NOT NULL CHECK (modality IN ('IMAGE', 'VIDEO')),
    model                  VARCHAR(128)  NOT NULL,
    -- Request fields as the API names them (size, n, seconds, ...): the
    -- version's, with what the batch request gave laid over them.
    params                 JSONB         NOT NULL DEFAULT '{}'::jsonb,
    -- The version's variable names, in order: the columns of results.csv,
    -- which outlive the rows' values.
    variable_names         JSONB         NOT NULL DEFAULT '[]'::jsonb,
    max_concurrency        INTEGER       NOT NULL CHECK (max_concurrency >= 1),
    webhook_url            TEXT,
    status                 VARCHAR(16)   NOT NULL
                           CHECK (status IN ('QUEUED', 'RUNNING', 'PAUSED', 'DONE', 'CANCELLED')),
    pause_reason           VARCHAR(32)   CHECK (pause_reason IN ('insufficient_credit', 'spending_cap')),
    -- Which cap, in words, for a spending_cap pause.
    pause_detail           TEXT,
    rows_total             INTEGER       NOT NULL CHECK (rows_total >= 1),
    estimated_total_cents  BIGINT        NOT NULL CHECK (estimated_total_cents >= 0),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    started_at             TIMESTAMPTZ,
    -- When the last row ended. A CANCELLED batch has none until the rows
    -- that were running when it was cancelled have finished.
    completed_at           TIMESTAMPTZ,
    -- Set, once, by whoever sends the batch's webhook: sent at most once.
    webhook_sent_at        TIMESTAMPTZ,
    CONSTRAINT ck_recipe_batches_pause CHECK ((status = 'PAUSED') = (pause_reason IS NOT NULL))
);

-- A user's batches, newest first, and the count held against the limit.
CREATE INDEX ix_recipe_batches_user_created ON recipe_batches (user_id, created_at DESC, id DESC);

-- What the runner reads every few seconds: batches that are not over.
CREATE INDEX ix_recipe_batches_open ON recipe_batches (created_at, id) WHERE completed_at IS NULL;

CREATE TABLE recipe_batch_rows (
    id            UUID          PRIMARY KEY,
    batch_id      UUID          NOT NULL REFERENCES recipe_batches(id) ON DELETE CASCADE,
    row_index     INTEGER       NOT NULL CHECK (row_index >= 0),
    status        VARCHAR(16)   NOT NULL
                  CHECK (status IN ('PENDING', 'RUNNING', 'DONE', 'FAILED', 'SKIPPED')),
    -- The value every variable has in this row. NULL once the row's job is
    -- purged: the purge redacts it with the job's prompt.
    variables     JSONB,
    -- The input named for each slot: {"product photo": {"file_id": "..."}}.
    inputs        JSONB         NOT NULL DEFAULT '{}'::jsonb,
    -- The row's job. Written in the transaction that creates the job, so it
    -- is there before the provider is called. No foreign key: the purge
    -- keeps the job, and deleting the batch must not touch it.
    job_id        UUID,
    cost_cents    BIGINT,
    error         TEXT,
    started_at    TIMESTAMPTZ,
    completed_at  TIMESTAMPTZ,
    CONSTRAINT uq_recipe_batch_rows_index UNIQUE (batch_id, row_index)
);

-- The rows of a batch that are not over: what the runner looks at.
CREATE INDEX ix_recipe_batch_rows_open ON recipe_batch_rows (batch_id, row_index)
    WHERE status IN ('PENDING', 'RUNNING');

-- The row a job belongs to, when the purge redacts it.
CREATE INDEX ix_recipe_batch_rows_job ON recipe_batch_rows (job_id) WHERE job_id IS NOT NULL;

-- The input files a batch's rows name, held so the nightly run does not
-- delete one while the batch waits. Removed when the batch ends; a file a
-- row's job used is then held by that job, as for any job (V31).
CREATE TABLE recipe_batch_inputs (
    batch_id  UUID  NOT NULL REFERENCES recipe_batches(id) ON DELETE CASCADE,
    file_id   UUID  NOT NULL REFERENCES input_files(id) ON DELETE CASCADE,
    PRIMARY KEY (batch_id, file_id)
);

CREATE INDEX ix_recipe_batch_inputs_file ON recipe_batch_inputs (file_id);

-- A job made by a batch. batch_row_id is its row; deleting the batch leaves
-- the job, which then points at nothing. batch_channel is how the batch was
-- made, kept on the job so the funnel still knows after the batch is gone.
ALTER TABLE generation_jobs
    ADD COLUMN batch_row_id   UUID REFERENCES recipe_batch_rows(id) ON DELETE SET NULL,
    ADD COLUMN batch_channel  VARCHAR(16),
    ADD CONSTRAINT ck_generation_jobs_batch_channel
        CHECK (batch_channel IN ('STUDIO', 'API'));

COMMENT ON COLUMN generation_jobs.batch_row_id IS
    'The batch row the job was made for, or NULL; set NULL when the batch is deleted';
COMMENT ON COLUMN generation_jobs.batch_channel IS
    'How the job''s batch was made: STUDIO or API; NULL for a job outside a batch';

-- One job per row, ever.
CREATE UNIQUE INDEX uq_generation_jobs_batch_row
    ON generation_jobs (batch_row_id)
    WHERE batch_row_id IS NOT NULL;

-- A job in a batch carries channel BATCH (round 10's channel gains a value).
ALTER TABLE generation_jobs DROP CONSTRAINT ck_generation_jobs_channel;
ALTER TABLE generation_jobs
    ADD CONSTRAINT ck_generation_jobs_channel CHECK (channel IN ('STUDIO', 'API', 'BATCH'));

COMMENT ON COLUMN generation_jobs.channel IS
    'How the job was made: STUDIO (signed-in session), API (API key) or BATCH (a recipe batch); NULL on rows from before V28';
