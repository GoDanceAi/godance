-- V42: webhook signing secrets go through the key vault.
--
-- No plaintext at rest (backend CLAUDE.md, hard constraint 2). Until now
-- users.webhook_signing_secret and webhook_signing_secret_previous held the
-- whsec_ secret in clear. From here on each holds KeyVault's AES-256-GCM
-- value (v1:...), bound to the user and the slot: the context is
-- webhook:<user_id>:current or webhook:<user_id>:previous, so a value copied
-- onto another row, or from one column to the other, does not decrypt.
--
-- A KeyVault value is about 110 characters, so the columns widen from
-- VARCHAR(64). That is all this file does.
--
-- The rows themselves are encrypted by V42.1, a Java migration
-- (ai.godance.security.WebhookSigningSecretVaultMigration), because the key
-- lives in the environment and SQL can't reach it. Flyway runs it right after
-- this one, before the application serves a request. In one transaction it
-- encrypts every value still in clear (a value already starting with v1: is
-- left alone, so it is safe to run again), turns a blank value into NULL
-- (the signer always treated blank as no secret), and then adds
-- ck_users_webhook_signing_secret_vault, which refuses any value that isn't a
-- KeyVault value from then on.

ALTER TABLE users
    ALTER COLUMN webhook_signing_secret          TYPE TEXT,
    ALTER COLUMN webhook_signing_secret_previous TYPE TEXT;

COMMENT ON COLUMN users.webhook_signing_secret IS
    'KeyVault value (v1:...), context webhook:<user_id>:current; the secret is never stored in clear';
COMMENT ON COLUMN users.webhook_signing_secret_previous IS
    'KeyVault value (v1:...), context webhook:<user_id>:previous; signs alongside the current one for 24 h after a rotation';
