-- V28: where a job and a sign-up came from (round 10 brief 01).
--
-- Godance is built for people who try things in the Studio and later run
-- them through the API. These columns record enough to count how many do:
-- the admin console's funnel report (GET /v1/admin/funnel) reads them.
--
-- A job's origin. channel is how it was made: STUDIO for a signed-in
-- session, API for an API key. The server decides it from the caller's
-- credentials; no request field sets it. gallery_recipe is the slug of the
-- gallery recipe the request said it was a run of, checked for its shape
-- only: the gallery is static content on the marketing site and the backend
-- does not know which recipes exist.
--
-- Rows from before this migration keep NULL in both: nothing recorded how
-- they were made. Both columns stay when a job is purged. They say nothing
-- about what was generated, and the report needs them.

ALTER TABLE generation_jobs
    ADD COLUMN channel         TEXT,
    ADD COLUMN gallery_recipe  TEXT,
    ADD CONSTRAINT ck_generation_jobs_channel
        CHECK (channel IN ('STUDIO', 'API')),
    ADD CONSTRAINT ck_generation_jobs_gallery_recipe
        CHECK (gallery_recipe ~ '^[a-z0-9-]{1,80}$');

COMMENT ON COLUMN generation_jobs.channel IS
    'How the job was made: STUDIO (signed-in session) or API (API key); NULL on rows from before V28';
COMMENT ON COLUMN generation_jobs.gallery_recipe IS
    'Slug of the gallery recipe the request named, or NULL; format-checked only';

-- "This user's first job of each channel": the funnel takes the earliest
-- STUDIO job and the earliest API job of every user in a cohort.
CREATE INDEX ix_generation_jobs_user_channel_created
    ON generation_jobs (user_id, channel, created_at)
    WHERE channel IS NOT NULL;

-- The funnel's recipe table reads the jobs of a date range that name a recipe.
CREATE INDEX ix_generation_jobs_gallery_recipe_created
    ON generation_jobs (created_at)
    WHERE gallery_recipe IS NOT NULL;

-- A sign-up's first touch: what brought the account here, as the app saw it
-- before anyone signed in. A JSON object with any of utm_source, utm_medium,
-- utm_campaign, gallery_recipe, landing_path and referrer_host, each at most
-- 100 printable characters. Written once, by POST /v1/attribution/first-touch,
-- within godance.attribution.first-touch-window-hours of the account being
-- created; first_touch_at is when. Closing an account clears both.

ALTER TABLE users
    ADD COLUMN first_touch     JSONB,
    ADD COLUMN first_touch_at  TIMESTAMPTZ,
    ADD CONSTRAINT ck_users_first_touch_together
        CHECK ((first_touch IS NULL) = (first_touch_at IS NULL));

COMMENT ON COLUMN users.first_touch IS
    'What brought the account here (campaign tags, gallery recipe, landing path, referring host); written once';
