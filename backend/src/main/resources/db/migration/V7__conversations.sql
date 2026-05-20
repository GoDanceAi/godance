-- =====================================================================
-- Godance V7 — chat conversation persistence.
--
-- Conversations let the React app remember chat history across reloads
-- and switch between threads. The /v1/chat/completions endpoint stays
-- OpenAI-compatible (stateless); persistence lives behind a separate
-- /v1/conversations surface so pure OpenAI clients are unaffected.
--
-- Soft-delete pattern via archived_at — the message history is the
-- audit trail, deleting on cascade would lose context users may want
-- back. Restore is a future affordance.
-- =====================================================================

CREATE TABLE conversations (
    id              UUID            PRIMARY KEY,
    user_id         UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    title           VARCHAR(255)    NOT NULL,
    created_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ     NOT NULL DEFAULT now(),
    archived_at     TIMESTAMPTZ                                     -- NULL = active
);
CREATE INDEX ix_conversations_user_updated
    ON conversations (user_id, updated_at DESC)
    WHERE archived_at IS NULL;

CREATE TABLE conversation_messages (
    id                  UUID            PRIMARY KEY,
    conversation_id     UUID            NOT NULL REFERENCES conversations(id) ON DELETE CASCADE,
    role                VARCHAR(16)     NOT NULL,    -- system | user | assistant
    content             TEXT            NOT NULL,
    model               VARCHAR(128),                -- set on assistant messages
    input_tokens        INTEGER,                     -- set on assistant messages
    output_tokens       INTEGER,                     -- set on assistant messages
    cost_cents          BIGINT,                      -- set on assistant messages
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now()
);
CREATE INDEX ix_conversation_messages_conv_created
    ON conversation_messages (conversation_id, created_at);
