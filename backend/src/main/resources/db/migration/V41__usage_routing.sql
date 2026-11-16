-- Round 14 brief 04, round 4's Phase 2: which routing policy and which of its
-- rules served a request made to a virtual model id (godance-fast,
-- godance-auto, godance-quality). Set on every usage record such a request
-- writes, the served attempt's and any failed attempt's, so the rows join
-- into a margin per policy and rule (GET /v1/admin/routing/margins).
-- NULL: a direct model call, and every record written before this.
ALTER TABLE usage_records
    ADD COLUMN routed_policy VARCHAR(128),
    ADD COLUMN routed_rule   VARCHAR(128),
    ADD CONSTRAINT ck_usage_records_routed_rule
        CHECK (routed_rule IS NULL OR routed_policy IS NOT NULL);

COMMENT ON COLUMN usage_records.routed_policy IS
    'The virtual model id the caller asked for, when routing chose the model; NULL for a direct call.';
COMMENT ON COLUMN usage_records.routed_rule IS
    'The policy rule that matched (or "default"), when routed_policy is set.';

-- The margin view reads routed rows by time range.
CREATE INDEX ix_usage_records_routed_recorded ON usage_records (recorded_at)
    WHERE routed_policy IS NOT NULL;
