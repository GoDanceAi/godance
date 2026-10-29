-- =====================================================================
-- Godance V17 — one card charge per auto-recharge, credited exactly once
-- (spec risk R-04).
--
-- Auto-recharge charged Stripe before writing the ledger credit, with an
-- idempotency key built from the current minute. A crash or rollback
-- between the two left a charge with no credit, and a retry a minute later
-- used a new key and charged the card again. A Stripe timeout after the
-- charge went through was treated as a decline: auto-recharge was turned
-- off and the charge was never credited.
--
-- An attempt row is now committed before the charge is sent. Its id is the
-- Stripe idempotency key and rides on the PaymentIntent as metadata, so a
-- PENDING attempt can always be matched to the charge it made, if any.
-- At most one attempt per user is PENDING at a time.
-- =====================================================================

CREATE TABLE auto_recharge_attempts (
    id                  UUID            PRIMARY KEY,
    user_id             UUID            NOT NULL REFERENCES users(id) ON DELETE CASCADE,
    amount_cents        BIGINT          NOT NULL CHECK (amount_cents > 0),
    status              VARCHAR(16)     NOT NULL,    -- PENDING | SUCCEEDED | FAILED
    payment_intent_id   VARCHAR(255),
    failure_reason      TEXT,
    created_at          TIMESTAMPTZ     NOT NULL DEFAULT now(),
    resolved_at         TIMESTAMPTZ
);

CREATE UNIQUE INDEX ux_auto_recharge_attempts_one_pending
    ON auto_recharge_attempts (user_id) WHERE status = 'PENDING';
