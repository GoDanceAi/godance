-- V23: every output of a generation job.
--
-- Before V23 only the first output was recorded, as a presigned link in
-- generation_jobs.output_url; a job with n = 4 stored four files and kept a
-- reference to one. Each stored file now gets a row, written in the same
-- transaction that ends the job DONE. Links are signed when the job is read,
-- from storage_key, so they outlive the presign TTL.
--
-- A job finished before V23 has no rows; the first read lists its storage
-- prefix and inserts them. Those jobs age out with the 90-day retention.
--
-- The rows go with the media: the retention purge and account closure
-- delete them together with the job's files.

CREATE TABLE generation_job_outputs (
    id              BIGSERIAL       PRIMARY KEY,
    job_id          UUID            NOT NULL REFERENCES generation_jobs(id) ON DELETE CASCADE,
    output_index    INT             NOT NULL CHECK (output_index >= 0),
    storage_key     VARCHAR(1024)   NOT NULL,
    content_type    VARCHAR(128)    NOT NULL,
    size_bytes      BIGINT          NOT NULL CHECK (size_bytes >= 0),
    revised_prompt  TEXT,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_generation_job_outputs_job_index UNIQUE (job_id, output_index)
);
