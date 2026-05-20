-- =====================================================================
-- Godance V4 — link users to Stripe customer records.
--
-- Lazily populated: created on first top-up Checkout Session. Persisting
-- the customer lets Session 5 attach saved payment methods for
-- auto-recharge without re-collecting card details.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN stripe_customer_id VARCHAR(64);

CREATE UNIQUE INDEX ux_users_stripe_customer_id
    ON users (stripe_customer_id)
    WHERE stripe_customer_id IS NOT NULL;
