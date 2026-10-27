-- =====================================================================
-- Godance V15 — settle fractions of a cent instead of rounding them away.
--
-- The ledger stays in whole cents. A request's exact marked-up cost in
-- microcents (1 cent = 10,000 microcents) is added to the user's carry;
-- every full cent in the carry is billed through the ledger and the
-- remainder (0..9999) waits for the next request. Before this, a request
-- whose cost rounded below half a cent was free (spec risk R-06).
--
-- usage_records keeps the exact amounts too, so revenue and provider-cost
-- reports no longer lose the fractions: cost_cents remains what was
-- actually billed for the row, cost_microcents is what it cost.
-- =====================================================================

ALTER TABLE users
    ADD COLUMN pending_microcents BIGINT NOT NULL DEFAULT 0
        CONSTRAINT ck_users_pending_microcents CHECK (pending_microcents >= 0 AND pending_microcents < 10000);

ALTER TABLE usage_records
    ADD COLUMN cost_microcents BIGINT,
    ADD COLUMN provider_cost_microcents BIGINT;

COMMENT ON COLUMN users.pending_microcents IS
    'Fraction of a cent (0..9999 microcents) carried to the next billable request';
COMMENT ON COLUMN usage_records.cost_microcents IS
    'Exact marked-up cost of this request; cost_cents is what the carry billed for it';
COMMENT ON COLUMN usage_records.provider_cost_microcents IS
    'Exact provider list cost of this request, no markup';
