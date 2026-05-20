-- =====================================================================
-- Godance V6 — 24h grace window for webhook secret rotation.
--
-- When a user rotates their webhook signing secret, the prior value
-- stays valid for {@code WebhookSigner.GRACE_WINDOW} so in-flight
-- verifier deployments don't drop deliveries.
--
-- Outbound webhooks carry TWO signature entries when within the grace
-- window:
--   X-Godance-Signature: t=<unix>,v1=<sig-with-current>,v1=<sig-with-previous>
--
-- Clients walk all v1=... entries and accept the request if ANY matches
-- their stored secret. Mirrors Stripe's multi-signature pattern.
--
-- After GRACE_WINDOW elapses, the previous secret is no longer included
-- in outbound headers; effectively retired.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN webhook_signing_secret_previous   VARCHAR(64),
    ADD COLUMN webhook_signing_secret_rotated_at TIMESTAMPTZ;
