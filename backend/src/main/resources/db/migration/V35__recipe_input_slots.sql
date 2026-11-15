-- V35: input slots on a recipe version (round 11 brief 05).
--
-- A recipe may name the images it works from: "product photo", "first
-- frame". A slot is a name, a role and a hint, as the round-11 contract has
-- them:
--   [{"slot": "product photo", "role": "image", "hint": "One product on a plain background"}]
-- The role is one the recipe's modality uses: "image" for an image recipe;
-- "first_frame", "last_frame" or "reference_image" for a video. A version
-- has at most four slots.
--
-- A slot never holds a file. Running the recipe names an input for each
-- slot ("recipe": {…, "inputs": {"product photo": {"file_id": "…"}}}), and
-- the job records what it was given, as any job with inputs does.
--
-- Slots are part of what a version is: changing them writes a new version.
-- Every existing version has none.

ALTER TABLE recipe_versions
    ADD COLUMN inputs JSONB NOT NULL DEFAULT '[]'::jsonb;

COMMENT ON COLUMN recipe_versions.inputs IS
    'The version''s input slots: [{slot, role, hint}], at most four; no file is stored here';
