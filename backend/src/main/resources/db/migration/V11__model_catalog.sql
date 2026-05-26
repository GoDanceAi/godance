-- =====================================================================
-- V11 — DB-backed model catalog + price-change staging lane.
-- Pairs with ai.godance.gateway.ModelCatalog (Task #19, round 3).
--
-- Three tables:
--   * model_catalog          runtime source of truth (replaces the code
--                            CatalogContributor beans as read path).
--   * model_price_history    append-only observed-price time series.
--   * model_change_proposal  staging lane — every refresh-job delta lands
--                            here first, never a direct live-price write.
--
-- Money columns stay integer microcents end-to-end (1 microcent =
-- 0.0001¢ = $0.000001), matching ai.godance.gateway.ModelCatalog.ModelInfo
-- and the rest of the credit ledger.
--
-- Seed values are pulled verbatim from the three CatalogContributor beans
-- at commit e10be63 (the parent commit of this branch). The six #14
-- verified price corrections are already in those beans so the seed
-- picks them up automatically. Do NOT round these "for cleanliness" —
-- ImageGenerationIntegrationTest and friends lock the downstream
-- billing math against these exact integers.
-- =====================================================================

CREATE TABLE model_catalog
(
    id                          BIGSERIAL    PRIMARY KEY,
    model_name                  VARCHAR(255) NOT NULL UNIQUE,
    provider                    VARCHAR(64)  NOT NULL,   -- matches ai.godance.domain.Provider enum string
    modality                    VARCHAR(32)  NOT NULL,   -- matches ai.godance.domain.Modality enum string
    input_microcents_per_1k     BIGINT       NOT NULL DEFAULT 0,
    output_microcents_per_1k    BIGINT       NOT NULL DEFAULT 0,
    per_image_microcents        BIGINT       NOT NULL DEFAULT 0,
    per_second_microcents       BIGINT       NOT NULL DEFAULT 0,
    default_max_output_tokens   INT,
    markup_override             DOUBLE PRECISION,        -- null = use BillingProperties tier default
    requires_async              BOOLEAN      NOT NULL DEFAULT FALSE,
    status                      VARCHAR(32)  NOT NULL,   -- ACTIVE | STAGED | DISCOVERED | DEPRECATED
    cost_source                 VARCHAR(255),
    last_verified_at            TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at                  TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX ix_model_catalog_status   ON model_catalog (status);
CREATE INDEX ix_model_catalog_provider ON model_catalog (provider);

CREATE TABLE model_price_history
(
    id            BIGSERIAL    PRIMARY KEY,
    model_name    VARCHAR(255) NOT NULL,    -- FK-style, not enforced so renames/discovery rows survive
    field         VARCHAR(32)  NOT NULL,    -- input | output | per_image | per_second
    old_value     BIGINT,
    new_value     BIGINT,
    source        VARCHAR(64),              -- openrouter | litellm | llm_fallback | manual | seed
    source_url    TEXT,
    observed_at   TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_model_price_history_model_observed
    ON model_price_history (model_name, observed_at DESC);

CREATE TABLE model_change_proposal
(
    id              BIGSERIAL    PRIMARY KEY,
    type            VARCHAR(32)  NOT NULL,  -- PRICE_CHANGE | NEW_VERSION | NEW_MODEL
    model_name      VARCHAR(255) NOT NULL,
    provider        VARCHAR(64),
    modality        VARCHAR(32),
    payload         JSONB        NOT NULL,
    source          VARCHAR(64),
    source_url      TEXT,
    detected_at     TIMESTAMPTZ  NOT NULL,
    status          VARCHAR(32)  NOT NULL,  -- PENDING | APPROVED | REJECTED | ANOMALY
    reviewed_by     VARCHAR(255),
    reviewed_at     TIMESTAMPTZ
);

CREATE INDEX ix_model_change_proposal_status_detected
    ON model_change_proposal (status, detected_at DESC);

-- =====================================================================
-- Seed model_catalog from the CatalogContributor beans at e10be63.
-- Three sections mirror TextCatalog / ImageCatalog / VideoCatalog.
-- =====================================================================

-- ----- TextCatalog -----
INSERT INTO model_catalog (model_name, provider, modality,
                           input_microcents_per_1k, output_microcents_per_1k,
                           default_max_output_tokens, markup_override,
                           requires_async, status, cost_source, last_verified_at) VALUES
    ('gpt-4o-mini',                'OPENAI',            'TEXT',     150,    600,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gpt-4o',                     'OPENAI',            'TEXT',   2500,  10000,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gpt-4.1',                    'OPENAI',            'TEXT',   2000,   8000,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('claude-3-5-sonnet-20241022', 'ANTHROPIC',         'TEXT',   3000,  15000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('claude-3-5-haiku-20241022',  'ANTHROPIC',         'TEXT',    800,   4000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gemini-1.5-pro',             'GOOGLE_VERTEX',     'TEXT',   1250,   5000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gemini-1.5-flash',           'GOOGLE_VERTEX',     'TEXT',     75,    300,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gpt-5.5',                    'OPENAI',            'TEXT',   5000,  30000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gpt-5.4',                    'OPENAI',            'TEXT',   2500,  15000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('claude-opus-4-7',            'ANTHROPIC',         'TEXT',   5000,  25000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('claude-sonnet-4-6',          'ANTHROPIC',         'TEXT',   3000,  15000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gemini-3.1-pro',             'GOOGLE_VERTEX',     'TEXT',   2000,  12000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('deepseek-v4-pro',            'DEEPSEEK',          'TEXT',   1740,   3480,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('doubao-1.6-pro',             'VOLCENGINE_ARK',    'TEXT',    110,   1100,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('grok-4',                     'XAI',               'TEXT',   3000,  15000,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('ernie-5.1',                  'BAIDU',             'TEXT',    590,   2650,  8192, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gpt-5.4-nano',               'OPENAI',            'TEXT',    200,   1250,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gemini-3.1-flash-lite',      'GOOGLE_VERTEX',     'TEXT',    250,   1500,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('claude-haiku-4-5',           'ANTHROPIC',         'TEXT',   1000,   5000,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('deepseek-v4-flash',          'DEEPSEEK',          'TEXT',    140,    280,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('kimi-k2.6',                  'MOONSHOT',          'TEXT',    950,   4000,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('qwen-3.6',                   'ALIBABA_DASHSCOPE', 'TEXT',    325,   1950,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('qwen-3.6-coder',             'ALIBABA_DASHSCOPE', 'TEXT',    650,   3250,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('glm-4.7-flash',              'ZHIPU',             'TEXT',     60,    400,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('minimax-m2.7',               'MINIMAX',           'TEXT',    300,   1200,  4096, NULL, FALSE, 'ACTIVE', 'seed:e10be63', NOW());

-- ----- ImageCatalog -----
INSERT INTO model_catalog (model_name, provider, modality,
                           per_image_microcents, markup_override,
                           requires_async, status, cost_source, last_verified_at) VALUES
    ('gpt-image-2',         'OPENAI',            'IMAGE',  53000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('gpt-image-1.5',       'OPENAI',            'IMAGE',  40000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('seedream-5.0',        'VOLCENGINE_ARK',    'IMAGE',  35000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('qwen-image-2.0',      'ALIBABA_DASHSCOPE', 'IMAGE',  35000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('qwen-image-2.0-pro',  'ALIBABA_DASHSCOPE', 'IMAGE',  75000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('flux-2-pro',          'BFL',               'IMAGE',  30000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('flux-1.1-pro',        'BFL',               'IMAGE',  40000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('nano-banana',         'GOOGLE_VERTEX',     'IMAGE', 134000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('imagen-4',            'GOOGLE_VERTEX',     'IMAGE',  40000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('sdxl',                'STABILITY',         'IMAGE',  50000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW());

-- ----- VideoCatalog -----
INSERT INTO model_catalog (model_name, provider, modality,
                           per_second_microcents, markup_override,
                           requires_async, status, cost_source, last_verified_at) VALUES
    ('seedance-2.0',        'VOLCENGINE_ARK', 'VIDEO', 140000, 15.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('sora-2',              'OPENAI',         'VIDEO', 100000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('sora-2-pro',          'OPENAI',         'VIDEO', 300000, 10.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('sora-2-pro-hd',       'OPENAI',         'VIDEO', 500000, 10.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('veo-3.1',             'GOOGLE_VERTEX',  'VIDEO', 400000, 10.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('veo-3.1-fast',        'GOOGLE_VERTEX',  'VIDEO', 150000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('kling-3.0',           'KLING',          'VIDEO',  84000, 15.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('runway-gen4',         'RUNWAY',         'VIDEO', 100000, 12.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW()),
    ('minimax-hailuo-2.3',  'MINIMAX',        'VIDEO',  90000, 15.0, TRUE, 'ACTIVE', 'seed:e10be63', NOW());
