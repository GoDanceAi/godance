-- V29: a user's own recipes, their versions, and which one a job ran
-- (round 10 brief 03).
--
-- A recipe is a setup its owner wants to keep: a prompt template with
-- variables, a model and its parameters. Saving a change makes a new
-- version; a version never changes once written, so a job that ran version
-- 3 can always be read against exactly what version 3 said.
--
-- Recipes are private. Nothing here is the gallery's: source_gallery_recipe
-- only remembers the slug of the gallery recipe a recipe was started from.

CREATE TABLE recipes (
    id                     UUID          PRIMARY KEY,
    user_id                UUID          NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title                  VARCHAR(200)  NOT NULL,
    modality               VARCHAR(16)   NOT NULL CHECK (modality IN ('IMAGE', 'VIDEO')),
    source_gallery_recipe  VARCHAR(80)   CHECK (source_gallery_recipe ~ '^[a-z0-9-]{1,80}$'),
    created_at             TIMESTAMPTZ   NOT NULL DEFAULT now(),
    updated_at             TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- A user's recipes, newest first, and the count held against the limit.
CREATE INDEX ix_recipes_user_created ON recipes (user_id, created_at DESC, id DESC);

-- template uses {{name}} markers (RecipeTemplate). variables is a JSON list
-- of { "name", "default" }; params holds request fields as the API names
-- them (size, n, seconds, ...). number counts 1, 2, 3 within a recipe.
CREATE TABLE recipe_versions (
    id          UUID          PRIMARY KEY,
    recipe_id   UUID          NOT NULL REFERENCES recipes(id) ON DELETE CASCADE,
    number      INTEGER       NOT NULL CHECK (number >= 1),
    template    TEXT          NOT NULL,
    variables   JSONB         NOT NULL DEFAULT '[]'::jsonb,
    model       VARCHAR(128)  NOT NULL,
    params      JSONB         NOT NULL DEFAULT '{}'::jsonb,
    created_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT uq_recipe_versions_number UNIQUE (recipe_id, number)
);

-- The version a job ran, when it was run from a recipe. Deleting the recipe
-- deletes its versions and leaves its jobs, which then point at nothing.
-- The values the job used for the variables are not here: they are in the
-- job's prompt_json, so the purge that redacts the prompt redacts them too.
ALTER TABLE generation_jobs
    ADD COLUMN recipe_version_id UUID REFERENCES recipe_versions(id) ON DELETE SET NULL;

COMMENT ON COLUMN generation_jobs.recipe_version_id IS
    'The recipe version the job was run from, or NULL; set NULL when the recipe is deleted';

-- A recipe's attempts: its jobs, their count and what they cost.
CREATE INDEX ix_generation_jobs_recipe_version
    ON generation_jobs (recipe_version_id)
    WHERE recipe_version_id IS NOT NULL;
