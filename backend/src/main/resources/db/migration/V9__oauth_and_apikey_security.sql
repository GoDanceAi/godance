-- =====================================================================
-- V9 — OAuth sign-in identities + API-key IP allowlist + spending-cap
--      soft-warning bookkeeping.
--
-- Pairs with:
--   ai.godance.domain.OAuthIdentity         (oauth_identities)
--   ai.godance.security.ApiKeyAuthFilter    (api_keys.ip_allowlist)
--   ai.godance.service.SpendingLimitService (*_cap_warned_at columns)
--
-- OAuth sign-in (Google / Apple / X) runs alongside — not replacing —
-- email+password. A social login links to an existing user by verified
-- email, else creates one. Email+password and existing JWTs are
-- unaffected.
-- =====================================================================

-- ---------- oauth_identities -----------------------------------------
-- One row per (provider, external-subject). A user may link several.
CREATE TABLE oauth_identities (
    id          UUID         PRIMARY KEY,
    user_id     UUID         NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    provider    VARCHAR(32)  NOT NULL,           -- GOOGLE | APPLE | X
    subject     VARCHAR(255) NOT NULL,           -- provider's stable user id
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_oauth_identities_provider_subject UNIQUE (provider, subject)
);
CREATE INDEX ix_oauth_identities_user_id ON oauth_identities (user_id);

-- ---------- api_keys.ip_allowlist ------------------------------------
-- Optional per-key CIDR allowlist. NULL or empty array = allow any IP.
-- Enforced in the API-key auth filter; off-list clients get 403.
ALTER TABLE api_keys
    ADD COLUMN ip_allowlist JSONB;

-- ---------- spending-cap soft-warning bookkeeping --------------------
-- The 80% soft-cap email fires once per period. *_cap_warned_at records
-- the last warning; a warning re-fires once the timestamp falls in a
-- previous period (i.e. the cap window has reset).
ALTER TABLE users
    ADD COLUMN daily_cap_warned_at   TIMESTAMPTZ,
    ADD COLUMN monthly_cap_warned_at TIMESTAMPTZ;
ALTER TABLE api_keys
    ADD COLUMN daily_cap_warned_at   TIMESTAMPTZ,
    ADD COLUMN monthly_cap_warned_at TIMESTAMPTZ;
