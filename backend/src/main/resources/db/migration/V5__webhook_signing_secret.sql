-- =====================================================================
-- Godance V5 — per-user webhook signing secret.
--
-- Outbound webhooks (POST to client's webhook_url on async job completion)
-- carry an HMAC-SHA256 signature so clients can verify Godance is the
-- sender. Pattern matches Stripe: header X-Godance-Signature: t=<unix>,v1=<hex>
-- where the signed body is "<t>.<rawPayload>".
--
-- Secret is lazily generated on first webhook send OR first explicit
-- fetch via GET /v1/webhooks/secret. Stored as the raw shared secret
-- (32 bytes, base64-encoded). Rotation = clear column + next webhook
-- regenerates.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN webhook_signing_secret VARCHAR(64);
