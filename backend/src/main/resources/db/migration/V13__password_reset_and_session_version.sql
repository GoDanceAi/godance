-- =====================================================================
-- Godance V13 — password reset + JWT revocation.
--
-- password_reset_token_hash  SHA-256 (hex) of the single pending reset
--                            token. The plaintext only goes out in the
--                            reset email; cleared on use.
-- password_reset_sent_at     when that token was issued: drives the
--                            one-hour expiry and the resend cooldown.
-- session_version            copied into every JWT the user is issued
--                            (the session_version claim). JWTs are
--                            stateless, so a password reset bumps this to
--                            reject every JWT issued before it. 0 matches
--                            the JWTs minted before the claim existed.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN password_reset_token_hash  VARCHAR(64),
    ADD COLUMN password_reset_sent_at     TIMESTAMPTZ,
    ADD COLUMN session_version            INTEGER NOT NULL DEFAULT 0;

CREATE UNIQUE INDEX ux_users_password_reset_token_hash
    ON users (password_reset_token_hash)
    WHERE password_reset_token_hash IS NOT NULL;
