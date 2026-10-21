# Brief 02 — Status Tab / Provider Observability + Quartz-in-tests Polish

**Branch:** `feature/observability` · **Migration: V10** · Read both coordination docs first. Own worktree; commit as you go.

## Mission

Make `/v1/status` reflect real provider health, make the router outage-aware, and stop Quartz from polluting the test logs.

## Scope

**Gateway instrumentation (#6):** capture `latency_ms` and an `outcome` (ok / error / rate_limited) for every upstream call and persist them on `usage_records`. Migration **V10** adds `usage_records.latency_ms INT` and `usage_records.outcome VARCHAR`. (These fields are why `ProviderHealthAggregator` currently leaves p50/p95/p99 + error_count null — see PROGRESS Session 12.)

**Health aggregation:** populate `p50/p95/p99` and `error_count` in `ProviderHealthAggregator` from the new fields.

**Outage-aware routing:** when a model's error rate exceeds 10% over a trailing 5-minute window, auto-de-prefer it in the router and reflect that in `/v1/status`. The configured fallback chain should pick up transparently.

**`/v1/status` enrichment:** per `(provider, model)` return latency percentiles, error rate, and a coarse `state`. Keep the existing OpenAI-compatible shape conventions.

**Quartz-in-tests polish (#17):** in the test profile, park the scheduler / push triggers far into the future or disable the JDBC job store so the Quartz `SchedulerThread` does not poll Postgres during Testcontainers teardown. This removes the "terminating connection due to unexpected postmaster exit" stack-trace flood and the "Surefire is going to kill self fork JVM (exit elapsed 30s)" warning. **Land this commit early on your branch** — it speeds and cleans every instance's test runs.

## Tests

Aggregator fold populates non-null percentiles + error_count after seeded usage; an error spike (>10%/5min) de-prefers the model and the fallback is used; `/v1/status` returns the enriched shape; test logs are clean of Quartz teardown errors and the suite exits without the 30s kill. `mvn -q test` green on branch.

## Out of scope

The marketing `/status` **page UI** (→ brief 04) — this brief is the backend endpoint + instrumentation only. No new payment or auth code.
