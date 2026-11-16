-- V39: published recipes, profiles, follows and reports (round 14 brief 01).
--
-- An adult account with a verified email and a profile publishes one of its
-- recipes: a version, a licence, and one to four of its own finished jobs as
-- examples. Publishing is pre-moderated. A published recipe is PENDING until a
-- moderator approves it; only then is it PUBLIC, and only PUBLIC rows reach a
-- public route. A report on a PUBLIC recipe puts it back in front of a
-- moderator; it stays public unless the moderator removes it.
--
-- What is public is a copy, so nothing that changes later reaches the public
-- routes unreviewed: the recipe's title and the chosen version are copied when
-- the author publishes, and the examples' files are copied when a moderator
-- approves. The 90-day purge of the source jobs never touches those copies;
-- unpublishing does.

-- One profile per account. The handle is the account's public name and the
-- key of /v1/public/profiles/{handle}; it cannot change once a moderator has
-- approved the profile, so a public link never moves to another account.
--
-- display_name and link are what the account last set. approved_display_name
-- and approved_link are what a moderator last approved, and the only values
-- the public routes show: a change made after approval waits for review.
CREATE TABLE profiles (
    user_id                UUID          PRIMARY KEY REFERENCES users(id) ON DELETE CASCADE,
    handle                 VARCHAR(30)   NOT NULL
        CONSTRAINT ck_profiles_handle CHECK (handle ~ '^[a-z0-9][a-z0-9-]{1,28}[a-z0-9]$'),
    display_name           VARCHAR(60)   NOT NULL,
    link                   VARCHAR(300)
        CONSTRAINT ck_profiles_link CHECK (link IS NULL OR link LIKE 'https://%'),
    approved_display_name  VARCHAR(60),
    approved_link          VARCHAR(300),
    -- When a moderator first approved the profile; NULL until then.
    approved_at            TIMESTAMPTZ,
    -- Why a moderator last refused a change to the profile, shown to its owner;
    -- NULL when nothing was refused since the owner last changed it.
    review_reason          TEXT,
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_profiles_handle UNIQUE (handle)
);

COMMENT ON COLUMN profiles.approved_display_name IS
    'The display name a moderator last approved: the only one a public route shows';

-- A published recipe. public_id names it on every route; the row id never
-- leaves the server. Title, modality and the version (template, variables,
-- input slots, model, params) are copied here when it is published.
CREATE TABLE published_recipes (
    id                      UUID          PRIMARY KEY,
    public_id               VARCHAR(12)   NOT NULL
        CONSTRAINT ck_published_recipes_public_id CHECK (public_id ~ '^[a-z0-9]{12}$'),
    user_id                 UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    -- The author's recipe and version; NULL once the author deletes the recipe,
    -- which unpublishes it first.
    recipe_id               UUID          REFERENCES recipes(id) ON DELETE SET NULL,
    version_id              UUID          REFERENCES recipe_versions(id) ON DELETE SET NULL,
    version_number          INTEGER       NOT NULL CHECK (version_number >= 1),
    title                   VARCHAR(200)  NOT NULL,
    modality                VARCHAR(16)   NOT NULL CHECK (modality IN ('IMAGE', 'VIDEO')),
    template                TEXT          NOT NULL,
    variables               JSONB         NOT NULL DEFAULT '[]'::jsonb,
    inputs                  JSONB         NOT NULL DEFAULT '[]'::jsonb,
    model                   VARCHAR(128)  NOT NULL,
    params                  JSONB         NOT NULL DEFAULT '{}'::jsonb,
    licence                 VARCHAR(32)   NOT NULL,
    tags                    JSONB         NOT NULL DEFAULT '[]'::jsonb,
    -- What it was made from: a gallery recipe, or another published recipe.
    made_from_gallery       VARCHAR(80)
        CONSTRAINT ck_published_recipes_gallery CHECK (made_from_gallery ~ '^[a-z0-9-]{1,80}$'),
    made_from_published_id  UUID          REFERENCES published_recipes(id) ON DELETE SET NULL,
    status                  VARCHAR(16)   NOT NULL
        CONSTRAINT ck_published_recipes_status CHECK (status IN ('PENDING', 'PUBLIC', 'REJECTED', 'REMOVED')),
    -- The last moderator decision: who, when, and the reason the author sees
    -- (rejected or removed). An author's own unpublish leaves reviewed_by NULL.
    reviewed_by             UUID          REFERENCES users(id) ON DELETE SET NULL,
    reviewed_at             TIMESTAMPTZ,
    reason                  TEXT,
    submitted_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    -- When a moderator first approved it: the order of the public lists.
    published_at            TIMESTAMPTZ,
    removed_at              TIMESTAMPTZ,
    updated_at              TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_published_recipes_public_id UNIQUE (public_id),
    CONSTRAINT ck_published_recipes_made_from
        CHECK (made_from_gallery IS NULL OR made_from_published_id IS NULL),
    CONSTRAINT ck_published_recipes_public_when
        CHECK (status <> 'PUBLIC' OR published_at IS NOT NULL)
);

-- At most one published recipe per recipe is pending or public at a time.
CREATE UNIQUE INDEX uq_published_recipes_active ON published_recipes (recipe_id)
    WHERE status IN ('PENDING', 'PUBLIC');
-- The public lists, newest first, and one author's public recipes.
CREATE INDEX ix_published_recipes_public ON published_recipes (published_at DESC, id DESC)
    WHERE status = 'PUBLIC';
CREATE INDEX ix_published_recipes_author_public ON published_recipes (user_id, published_at DESC, id DESC)
    WHERE status = 'PUBLIC';
CREATE INDEX ix_published_recipes_tags ON published_recipes USING GIN (tags jsonb_path_ops)
    WHERE status = 'PUBLIC';
-- The moderation queue, oldest first, and an author's submissions per day.
CREATE INDEX ix_published_recipes_pending ON published_recipes (submitted_at, id)
    WHERE status = 'PENDING';
CREATE INDEX ix_published_recipes_user_submitted ON published_recipes (user_id, submitted_at DESC);

-- A published recipe's examples: one row per output of each chosen job.
-- source_job_id is never shown publicly. storage_key is NULL until a moderator
-- approves, which copies the output to community/<public_id>/...; the copy is
-- what the public routes serve, so the source job's purge cannot remove it.
CREATE TABLE published_recipe_examples (
    id                    UUID          PRIMARY KEY,
    published_recipe_id   UUID          NOT NULL REFERENCES published_recipes(id) ON DELETE CASCADE,
    position              INTEGER       NOT NULL CHECK (position >= 1),
    source_job_id         UUID          REFERENCES generation_jobs(id) ON DELETE SET NULL,
    source_output_index   INTEGER       NOT NULL CHECK (source_output_index >= 0),
    storage_key           VARCHAR(255),
    content_type          VARCHAR(100),
    size_bytes            BIGINT,
    created_at            TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_published_recipe_examples_position UNIQUE (published_recipe_id, position)
);

-- An account following another. Who follows whom is never shown: a profile
-- shows only how many follow it.
CREATE TABLE follows (
    follower_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    followee_id  UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (follower_id, followee_id),
    CONSTRAINT ck_follows_not_self CHECK (follower_id <> followee_id)
);

CREATE INDEX ix_follows_followee ON follows (followee_id);

-- A report on a published recipe or a profile. An open report on a PUBLIC
-- recipe puts it back in the moderation queue.
CREATE TABLE reports (
    id                   UUID          PRIMARY KEY,
    reporter_id          UUID          REFERENCES users(id) ON DELETE CASCADE,
    kind                 VARCHAR(32)   NOT NULL
        CONSTRAINT ck_reports_kind CHECK (kind IN ('published_recipe', 'profile')),
    published_recipe_id  UUID          REFERENCES published_recipes(id) ON DELETE CASCADE,
    profile_user_id      UUID          REFERENCES users(id) ON DELETE CASCADE,
    reason               TEXT          NOT NULL,
    status               VARCHAR(16)   NOT NULL DEFAULT 'OPEN'
        CONSTRAINT ck_reports_status CHECK (status IN ('OPEN', 'RESOLVED')),
    resolved_by          UUID          REFERENCES users(id) ON DELETE SET NULL,
    resolved_at          TIMESTAMPTZ,
    created_at           TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_reports_target CHECK (
        (kind = 'published_recipe' AND published_recipe_id IS NOT NULL AND profile_user_id IS NULL)
        OR (kind = 'profile' AND profile_user_id IS NOT NULL AND published_recipe_id IS NULL))
);

-- One open report per reporter and target; the open reports, oldest first.
CREATE UNIQUE INDEX uq_reports_open_recipe ON reports (reporter_id, published_recipe_id)
    WHERE status = 'OPEN' AND published_recipe_id IS NOT NULL;
CREATE UNIQUE INDEX uq_reports_open_profile ON reports (reporter_id, profile_user_id)
    WHERE status = 'OPEN' AND profile_user_id IS NOT NULL;
CREATE INDEX ix_reports_open ON reports (created_at, id) WHERE status = 'OPEN';
-- The reports one account made in the last hour: what the report limit counts.
CREATE INDEX ix_reports_reporter ON reports (reporter_id, created_at DESC);
CREATE INDEX ix_reports_recipe ON reports (published_recipe_id) WHERE published_recipe_id IS NOT NULL;
CREATE INDEX ix_reports_profile ON reports (profile_user_id) WHERE profile_user_id IS NOT NULL;

-- The published recipe a job ran (round 10's origin record): runs and runners
-- are counted from these. The job never records the author's recipe version,
-- so the author's own recipe and its attempts are untouched by others' runs.
ALTER TABLE generation_jobs
    ADD COLUMN published_recipe_id UUID REFERENCES published_recipes(id) ON DELETE SET NULL;

COMMENT ON COLUMN generation_jobs.published_recipe_id IS
    'The published recipe the job ran, or NULL; set by the server from recipe.published_id';

CREATE INDEX ix_generation_jobs_published_recipe ON generation_jobs (published_recipe_id, user_id)
    WHERE published_recipe_id IS NOT NULL;

-- A recipe started from a published recipe remembers it, as source_gallery_recipe
-- remembers a gallery recipe (V29); publishing it records made_from.
ALTER TABLE recipes
    ADD COLUMN source_published_recipe_id UUID REFERENCES published_recipes(id) ON DELETE SET NULL;
