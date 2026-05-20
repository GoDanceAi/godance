-- =====================================================================
-- Godance V3 — email verification columns on users.
--
-- Per outcome brief §"Users & Access (v1)": "Self-serve signup with
-- email verification". Single pending token per user; cleared on verify.
--
-- v1 email sending is a stub (logs the verification URL); real provider
-- integration (Postmark/SES per brief) lands in a later session.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN email_verified              BOOLEAN NOT NULL DEFAULT FALSE,
    ADD COLUMN email_verification_token    VARCHAR(64),
    ADD COLUMN email_verification_sent_at  TIMESTAMPTZ;

CREATE INDEX ix_users_email_verification_token
    ON users (email_verification_token)
    WHERE email_verification_token IS NOT NULL;
