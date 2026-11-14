-- V30: how each API key receives image results (round 10 brief 04).
--
-- Every image model runs as an async job, so POST /v1/images/generations
-- answers 202 with a job id to poll. Standard OpenAI SDK code expects the
-- images in the response instead. A key can now choose:
--
--   ASYNC  today's behaviour: 202 and a job to poll
--   SYNC   the request waits for the job, up to godance.images.sync-wait,
--          and answers in OpenAI's shape; past the limit it answers 202
--
-- Existing keys are ASYNC, so nothing changes for them. A new key gets the
-- value it asks for, or godance.images.new-key-response (async by default).
-- The setting changes only how the result is delivered: the job, its price,
-- its settlement and its refund on failure are the same either way.

ALTER TABLE api_keys
    ADD COLUMN image_response TEXT NOT NULL DEFAULT 'ASYNC',
    ADD CONSTRAINT ck_api_keys_image_response CHECK (image_response IN ('ASYNC', 'SYNC'));

COMMENT ON COLUMN api_keys.image_response IS
    'How image requests made with this key answer: ASYNC (202 and a job) or SYNC (wait, then OpenAI''s shape)';
