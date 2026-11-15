-- V31: input files, which job uses which input, and the input terms
-- (round 11 brief 01).
--
-- An input file is an image its owner uploaded to use in a generation. It is
-- private: only the owner reads it, and nothing public ever points at it.
-- Its metadata (EXIF, XMP, GPS) is stripped before it is stored.
--
-- How long a file lives:
--   expires_at set    nothing uses the file; the nightly retention run
--                     deletes it once the time has passed
--   expires_at NULL   a job uses it (a row in generation_job_inputs), so it
--                     is kept until the last such job is purged, and then
--                     gets a fresh expires_at
--   deleted_at set    the owner deleted it, or retention did: the object is
--                     gone. The row stays only while a job still records
--                     the file, so that job's input reads back without a
--                     link; it is deleted with the last such job.
--
-- Everything here ships switched off (godance.inputs.enabled = false).

CREATE TABLE input_files (
    id               UUID          PRIMARY KEY,
    user_id          UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    storage_key      VARCHAR(200)  NOT NULL,
    content_type     VARCHAR(32)   NOT NULL
                     CHECK (content_type IN ('image/png', 'image/jpeg', 'image/webp')),
    bytes            BIGINT        NOT NULL CHECK (bytes > 0),
    width            INTEGER       NOT NULL CHECK (width > 0),
    height           INTEGER       NOT NULL CHECK (height > 0),
    -- What the person check said: TRUE, FALSE, or NULL when no check ran.
    contains_person  BOOLEAN,
    created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
    expires_at       TIMESTAMPTZ,
    deleted_at       TIMESTAMPTZ,
    CONSTRAINT uq_input_files_storage_key UNIQUE (storage_key)
);

COMMENT ON COLUMN input_files.contains_person IS
    'Result of the person check at upload: TRUE, FALSE, or NULL when no check ran';
COMMENT ON COLUMN input_files.expires_at IS
    'When an unused file is deleted; NULL while a job uses it';
COMMENT ON COLUMN input_files.deleted_at IS
    'When the object was deleted; the row stays while a job still records the file';

-- A user's files, newest first, and the count held against the limit.
CREATE INDEX ix_input_files_user_created
    ON input_files (user_id, created_at DESC, id DESC)
    WHERE deleted_at IS NULL;

-- The nightly run: unused files past their time.
CREATE INDEX ix_input_files_expires
    ON input_files (expires_at)
    WHERE deleted_at IS NULL AND expires_at IS NOT NULL;

-- Which job uses which input: the one table that says so. An input is
-- either an uploaded file, or an output of one of the same user's jobs
-- (source_job_id, source_index). position keeps the order the request gave.
--
-- The rows are deleted when the job is purged; that is what lets a file's
-- retention be read off this table alone.
CREATE TABLE generation_job_inputs (
    job_id         UUID         NOT NULL REFERENCES generation_jobs(id) ON DELETE CASCADE,
    position       INTEGER      NOT NULL CHECK (position >= 0),
    role           VARCHAR(24)  NOT NULL
                   CHECK (role IN ('image', 'mask', 'first_frame', 'last_frame', 'reference_image')),
    file_id        UUID         REFERENCES input_files(id) ON DELETE CASCADE,
    source_job_id  UUID         REFERENCES generation_jobs(id) ON DELETE CASCADE,
    source_index   INTEGER      CHECK (source_index >= 0),
    PRIMARY KEY (job_id, position),
    CONSTRAINT ck_generation_job_inputs_one_source CHECK (
        (file_id IS NOT NULL AND source_job_id IS NULL AND source_index IS NULL)
        OR (file_id IS NULL AND source_job_id IS NOT NULL AND source_index IS NOT NULL))
);

-- "Does anything still use this file?", asked at every purge and delete.
CREATE INDEX ix_generation_job_inputs_file
    ON generation_job_inputs (file_id)
    WHERE file_id IS NOT NULL;

CREATE INDEX ix_generation_job_inputs_source_job
    ON generation_job_inputs (source_job_id)
    WHERE source_job_id IS NOT NULL;

-- The version of the input terms the account accepted, and when. NULL until
-- it accepts; an older version than godance.inputs.terms-version does not
-- count.
ALTER TABLE users
    ADD COLUMN inputs_terms_version      INTEGER,
    ADD COLUMN inputs_terms_accepted_at  TIMESTAMPTZ;

COMMENT ON COLUMN users.inputs_terms_version IS
    'Version of the input terms the account accepted, or NULL';
