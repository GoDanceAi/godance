-- =====================================================================
-- Godance V22 — two-factor sign-in (TOTP, RFC 6238).
--
-- One row per account that has started enrolling. Its own table, not
-- columns on users: users is saved whole by many unrelated writers, and a
-- stale copy written back must not reset the replay guard or the failed
-- attempt count. Verification locks this row, so attempts on one account
-- run one at a time.
--
-- secret_encrypted      the TOTP secret, AES-256-GCM (KeyVault), bound to
--                       this user id.
-- enabled_at            NULL while enrolment waits for its first code; set
--                       once that code is confirmed. 2FA is on when set.
-- last_used_step        the highest accepted TOTP time step. A code is
--                       refused unless its step is above this, so no code
--                       works twice (RFC 6238 section 5.2).
-- recovery_code_hashes  JSON array of SHA-256 hex hashes of the one-time
--                       recovery codes that are still unused. A code is
--                       removed when it is used.
-- failed_attempts       wrong codes since the last right one or lockout.
-- locked_until          no code is accepted before this, even a right
--                       one. Kept here, not in Redis: the Redis limiter
--                       fails open, and this limit must not.
-- =====================================================================

CREATE TABLE user_two_factor (
    user_id               UUID         PRIMARY KEY REFERENCES users (id) ON DELETE CASCADE,
    secret_encrypted      VARCHAR(128) NOT NULL,
    enabled_at            TIMESTAMPTZ,
    last_used_step        BIGINT       NOT NULL DEFAULT 0,
    recovery_code_hashes  JSONB        NOT NULL DEFAULT '[]'::jsonb,
    failed_attempts       INTEGER      NOT NULL DEFAULT 0,
    locked_until          TIMESTAMPTZ,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now()
);
