-- =====================================================================
-- V10 — Per-request latency + outcome on usage_records.
--
-- Feeds the ProviderHealthAggregator fold (p50/p95/p99 latency percentiles
-- and error_count) and the outage-aware router. Both columns are nullable:
-- rows written before this migration — and any non-instrumented path —
-- simply carry NULL, which the aggregator treats as "successful, latency
-- unknown". Adding nullable columns is metadata-only on Postgres (no table
-- rewrite), so this is safe to apply online.
-- =====================================================================

ALTER TABLE usage_records
    ADD COLUMN latency_ms INT,
    ADD COLUMN outcome     VARCHAR(32);   -- OK | ERROR | RATE_LIMITED (CallOutcome)
