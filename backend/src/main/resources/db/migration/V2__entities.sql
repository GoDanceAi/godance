-- =====================================================================
-- Godance V2 — initial entity schema (single-tenant) plus tables for
-- the credit ledger, async generation jobs, and provider health metrics.
--
-- Conventions:
--   * UUID PKs (app-side generated via Hibernate @UuidGenerator TIME).
--   * All money is BIGINT cents (CLAUDE.md hard constraint #6).
--   * Timestamps are TIMESTAMPTZ.
--   * Enums stored as VARCHAR (Hibernate EnumType.STRING). No DB CHECK
--     constraints in v1 — kept enforcement at the app layer so enum
--     evolution doesn't require a migration.
--   * VARCHAR(N) for short strings (Hibernate default; validates clean
--     against entity @Column without length annotations). TEXT only for
--     genuinely free-form fields (note, error_message); those entities
--     carry @Column(columnDefinition="text") to match.
--   * Foreign keys declared at DB level; entities expose raw UUID FK
--     columns (no @ManyToOne) — REST-shaped, no lazy proxies.
-- =====================================================================

-- ---------- users ----------------------------------------------------
CREATE TABLE users (
    id                          UUID            PRIMARY KEY,
    email                       VARCHAR(320)    NOT NULL,
    password_hash               VARCHAR(255),
    full_name                   VARCHAR(255)    NOT NULL,
    role                        VARCHAR(32)     NOT NULL,    -- USER | ADMIN
    is_active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    credit_balance_cents        BIGINT          NOT NULL DEFAULT 0,
    daily_credit_cap_cents      BIGINT,
    monthly_credit_cap_cents    BIGINT,
    last_login_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    CONSTRAINT uq_users_email UNIQUE (email)
);

-- ---------- api_keys -------------------------------------------------
CREATE TABLE api_keys (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    name                        VARCHAR(255)    NOT NULL,
    key_hash                    VARCHAR(255)    NOT NULL,    -- CLAUDE.md hard constraint #2
    key_prefix                  VARCHAR(16)     NOT NULL,    -- first 8 chars for identification
    scopes                      JSONB,                       -- optional list of allowed scopes
    daily_credit_cap_cents      BIGINT,
    monthly_credit_cap_cents    BIGINT,
    is_active                   BOOLEAN         NOT NULL DEFAULT TRUE,
    last_used_at                TIMESTAMPTZ,
    expires_at                  TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX ix_api_keys_user_id    ON api_keys (user_id);
CREATE INDEX ix_api_keys_key_prefix ON api_keys (key_prefix);

-- ---------- usage_records (per-request billing rows) ----------------
CREATE TABLE usage_records (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    api_key_id                  UUID            REFERENCES api_keys(id) ON DELETE SET NULL,
    modality                    VARCHAR(32)     NOT NULL,    -- TEXT | IMAGE | VIDEO | AUDIO_TTS | AUDIO_STT | EMBEDDING
    model_name                  VARCHAR(128)    NOT NULL,
    provider                    VARCHAR(32)     NOT NULL,
    input_tokens                BIGINT          NOT NULL DEFAULT 0,
    output_tokens               BIGINT          NOT NULL DEFAULT 0,
    cost_cents                  BIGINT          NOT NULL,    -- what we charged the user
    provider_cost_cents         BIGINT,                      -- what the provider charged us
    region                      VARCHAR(32)     NOT NULL,    -- LOCAL | DIGITALOCEAN | ALICLOUD
    cache_hit                   BOOLEAN         NOT NULL DEFAULT FALSE,
    fallback_used               BOOLEAN         NOT NULL DEFAULT FALSE,
    request_id                  VARCHAR(128),
    recorded_at                 TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX ix_usage_records_user_recorded     ON usage_records (user_id, recorded_at DESC);
CREATE INDEX ix_usage_records_provider_recorded ON usage_records (provider, recorded_at DESC);

-- ---------- audit_logs (append-only) ---------------------------------
CREATE TABLE audit_logs (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            REFERENCES users(id) ON DELETE SET NULL,
    event_type                  VARCHAR(128)    NOT NULL,
    event_detail                JSONB,
    ip_address                  VARCHAR(64),
    outcome                     VARCHAR(32)     NOT NULL,    -- SUCCESS | FAILURE | DENIED | TIMEOUT
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX ix_audit_logs_event_type  ON audit_logs (event_type);
CREATE INDEX ix_audit_logs_created_at  ON audit_logs (created_at DESC);

-- ---------- scheduled_triggers (per-user Quartz tasks) ---------------
CREATE TABLE scheduled_triggers (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    task_kind                   VARCHAR(32)     NOT NULL,    -- AUTO_RECHARGE | DAILY_REPORT | WEEKLY_SUMMARY
    trigger_type                VARCHAR(32)     NOT NULL,    -- CRON | EVENT
    cron_expression             VARCHAR(128),
    timezone                    VARCHAR(64)     NOT NULL DEFAULT 'UTC',
    payload_json                JSONB,
    is_enabled                  BOOLEAN         NOT NULL DEFAULT TRUE,
    last_fired_at               TIMESTAMPTZ,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at                  TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX ix_scheduled_triggers_user    ON scheduled_triggers (user_id);
CREATE INDEX ix_scheduled_triggers_enabled ON scheduled_triggers (is_enabled) WHERE is_enabled = TRUE;

-- ---------- credit_ledger (NET-NEW, append-only signed-delta) --------
CREATE TABLE credit_ledger (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    delta_cents                 BIGINT          NOT NULL,    -- signed
    source                      VARCHAR(32)     NOT NULL,    -- STRIPE | COINBASE | DEBIT | REFUND | ADJUSTMENT | PROMO
    source_ref                  VARCHAR(255),                -- Stripe charge id / Coinbase charge id / request id
    idempotency_key             VARCHAR(128)    NOT NULL,
    occurred_at                 TIMESTAMPTZ     NOT NULL DEFAULT now(),
    note                        TEXT,
    CONSTRAINT uq_credit_ledger_idempotency UNIQUE (idempotency_key)
);
CREATE INDEX ix_credit_ledger_user_occurred ON credit_ledger (user_id, occurred_at DESC);

-- ---------- generation_jobs (NET-NEW, async image/video/long-audio) --
CREATE TABLE generation_jobs (
    id                          UUID            PRIMARY KEY,
    user_id                     UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    modality                    VARCHAR(32)     NOT NULL,    -- IMAGE | VIDEO | AUDIO_TTS | AUDIO_STT
    provider                    VARCHAR(32)     NOT NULL,
    model                       VARCHAR(128)    NOT NULL,
    prompt_json                 JSONB           NOT NULL,
    status                      VARCHAR(32)     NOT NULL,    -- QUEUED | RUNNING | DONE | FAILED | CANCELLED
    output_url                  VARCHAR(2048),
    estimated_cost_cents        BIGINT,
    actual_cost_cents           BIGINT,
    webhook_url                 VARCHAR(2048),
    error_message               TEXT,
    created_at                  TIMESTAMPTZ     NOT NULL DEFAULT now(),
    started_at                  TIMESTAMPTZ,
    completed_at                TIMESTAMPTZ
);
CREATE INDEX ix_generation_jobs_user_created   ON generation_jobs (user_id, created_at DESC);
CREATE INDEX ix_generation_jobs_status_created ON generation_jobs (status, created_at DESC);

-- ---------- provider_health (NET-NEW, rolling metrics) ---------------
CREATE TABLE provider_health (
    id                          UUID            PRIMARY KEY,
    provider                    VARCHAR(32)     NOT NULL,
    model                       VARCHAR(128)    NOT NULL,
    window_start                TIMESTAMPTZ     NOT NULL,
    window_end                  TIMESTAMPTZ     NOT NULL,
    request_count               BIGINT          NOT NULL DEFAULT 0,
    error_count                 BIGINT          NOT NULL DEFAULT 0,
    rate_limit_count            BIGINT          NOT NULL DEFAULT 0,
    p50_ms                      INTEGER,
    p95_ms                      INTEGER,
    p99_ms                      INTEGER,
    CONSTRAINT uq_provider_health_window UNIQUE (provider, model, window_start, window_end)
);
CREATE INDEX ix_provider_health_provider_window ON provider_health (provider, window_end DESC);
