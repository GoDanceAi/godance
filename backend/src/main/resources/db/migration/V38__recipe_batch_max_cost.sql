-- Round 13 brief 01: the most a recipe batch may cost, when its caller set one
-- (the MCP tool start_batch's max_cost_cents). A batch prices each row when the
-- row starts, so a price that rises while the batch runs could otherwise take it
-- past what its caller allowed. The runner gives each row's job only what is left
-- of this ceiling; a row that would pass it fails before anything is charged.
-- NULL: no ceiling, as for every batch made before this and every batch made
-- through POST /v1/recipe-batches.
ALTER TABLE recipe_batches
    ADD COLUMN max_cost_cents BIGINT
        CONSTRAINT ck_recipe_batches_max_cost CHECK (max_cost_cents IS NULL OR max_cost_cents >= 0);

COMMENT ON COLUMN recipe_batches.max_cost_cents IS
    'The most the whole batch may cost, in cents, or NULL for no ceiling; each row''s job gets what is left';
