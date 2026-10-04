# Brief — Dynamic, DB-Backed Model Catalog + Daily Pricing Refresh

**Task #19** · **Branch:** `feature/dynamic-pricing` (off `main`, currently at `e10be63`, 202 tests green) · **Migrations: V11 onward** (V10 landed with observability — `V10__usage_record_observability.sql` in commit `37410a0`; V11 is the next free number) · Single workstream — own your worktree and **commit to your branch as you go**.

## Read first

In this order, before writing any code:

1. **This brief.**
2. `../00-COORDINATION.md` and `../round-2/00-COORDINATION.md` — global engineering conventions (ModelInfo / microcents / Provider enum / regional allowlist / WireMock cassettes / RFC 7807 / reactive end-to-end). Round 2 also captures the isolation lesson: commit to your branch as you go; use `git merge --no-ff -m "…"` to avoid editor stalls; do **not** push or deploy.
3. In the backend repo:
   - `gateway/ModelCatalog.java`, `gateway/CatalogContributor.java`, `gateway/catalog/{Text,Image,Video}Catalog.java` — the current code-based catalog you're replacing as the runtime source.
   - `gateway/ModelCatalog.ModelInfo` — the record whose shape the DB must serve.
   - `web/ModelsController.java` — the `/v1/models` response shape that must stay byte-for-byte compatible.
   - `service/AutoRechargeCronJob.java` + `config/QuartzJobsConfig.java` — the Quartz cron pattern you'll mirror (property-tunable cron, `@DisallowConcurrentExecution`, single-region gate).
   - The `Region` enum + `godance.region` config — for the region gate.

## Local prerequisites (do every session, before `mvn test`)

`@SpringBootTest` boots the full context, which includes `S3ObjectStorageClient.start()` doing a `headBucket` at `@PostConstruct`. If MinIO isn't running, the context fails to load and ~80 tests fail with `Connect to localhost:9000 refused`. So:

```bash
cd backend
docker compose up -d            # postgres :5433, redis :6380, minio :9000
docker compose ps               # confirm all three healthy
mvn clean test                  # baseline: should be 202 green at e10be63
```

If `docker` isn't on PATH: `export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"`.

## Why this build exists

Catalog + prices live in Java today (`ModelCatalog` + the three `CatalogContributor` beans, money in microcents). Provider pricing is fluid — drops when new versions ship, spikes when GPUs get scarce — and a stale cost basis silently erodes margin. (Concrete example we just lived: Seedance carried at $0.052/s vs a true ~$0.14/s — that's billing the user less than we pay ByteDance.) Move catalog into the DB and add a daily job that **tracks price moves and discovers new versions / new models — staging every change for human approval, never auto-applying to live billing.**

## Decisions already made (build to these, don't relitigate)

- **Source:** structured aggregator feeds **+** an LLM fallback for what they miss.
- **Apply policy:** **stage for approval.** The job never mutates a live, billable price. It writes history and creates proposals; a human approves.
- **Cadence:** **daily** (property-tunable cron, region-gated to DigitalOcean only).

## What to build

### 1. Schema (Flyway, start at V11)

- **`model_catalog`** — runtime source of truth, replaces the code beans. Columns: `id BIGSERIAL PK`, `model_name VARCHAR UNIQUE NOT NULL`, `provider VARCHAR NOT NULL` (matches the `Provider` enum string), `modality VARCHAR NOT NULL`, `input_microcents_per_1k BIGINT`, `output_microcents_per_1k BIGINT`, `per_image_microcents BIGINT`, `per_second_microcents BIGINT`, `default_max_output_tokens INT`, `markup_override DOUBLE PRECISION NULL`, `requires_async BOOLEAN NOT NULL`, `status VARCHAR NOT NULL` (`ACTIVE` / `STAGED` / `DISCOVERED` / `DEPRECATED`), `cost_source VARCHAR`, `last_verified_at TIMESTAMPTZ`, `created_at` / `updated_at`. Money stays integer microcents.
- **`model_price_history`** — append-only time series (answers "did Opus 4.7 go up or down"). Columns: `id BIGSERIAL PK`, `model_name VARCHAR NOT NULL` (FK-style, no enforced FK so renames survive), `field VARCHAR NOT NULL` (`input` / `output` / `per_image` / `per_second`), `old_value BIGINT`, `new_value BIGINT`, `source VARCHAR`, `source_url TEXT`, `observed_at TIMESTAMPTZ NOT NULL`. Index on `(model_name, observed_at DESC)`.
- **`model_change_proposal`** — the staging lane. Columns: `id BIGSERIAL PK`, `type VARCHAR NOT NULL` (`PRICE_CHANGE` / `NEW_VERSION` / `NEW_MODEL`), `model_name VARCHAR NOT NULL`, `provider VARCHAR`, `modality VARCHAR`, `payload JSONB NOT NULL` (proposed values), `source VARCHAR`, `source_url TEXT`, `detected_at TIMESTAMPTZ NOT NULL`, `status VARCHAR NOT NULL` (`PENDING` / `APPROVED` / `REJECTED` / `ANOMALY`), `reviewed_by VARCHAR`, `reviewed_at TIMESTAMPTZ`. Index on `(status, detected_at DESC)`.

### 2. Make the catalog DB-backed (keep `/v1/models` byte-identical)

- **Seed `model_catalog` deterministically in the V11 migration via SQL `INSERT`s** — pull the exact values from the current `CatalogContributor` beans at `e10be63`. The six #14 price corrections are already in those beans (committed in this same commit), so the seed picks them up automatically. Mark every seeded row as `status=ACTIVE`, `cost_source='seed:e10be63'`, `last_verified_at=NOW()`.
- **Retire the runtime path through `CatalogContributor`:** `ModelCatalog` now loads from `model_catalog` into an in-memory cache (refreshed by an approval write or a short interval). Keep the `ModelInfo` record shape and the `GET /v1/models` response **byte-for-byte compatible** with `Session12EndpointsIntegrationTest.models_*` assertions. Adapters stay keyed by the `Provider` enum — **do not touch any adapter**.
- The `CatalogContributor` beans themselves can be deleted in the same PR once seeded, or kept as dead-but-documented seed source — your call, but the DB is the only runtime read path.

### 3. Daily refresh job (Quartz, region-gated)

Mirror `AutoRechargeCronJob`: a Quartz `@DisallowConcurrentExecution` job with cron string driven by `godance.pricing.refresh-cron` (default `0 0 4 * * ?` = 04:00 UTC daily). **Run only when `godance.region == DIGITALOCEAN`** — same single-region gotcha as auto-recharge; an Alicloud failover must not double-fire. Each run:

1. **Probe first, code second.** Before writing the parser, fetch `https://openrouter.ai/api/v1/models` and a snippet of `https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json` once and build the mapping around the *real* shapes. Save short fixtures of those responses as test cassettes (WireMock).
2. **Pull structured feeds:** OpenRouter (`pricing.{prompt,completion,image,request}` in USD per token/unit) and LiteLLM (`input_cost_per_token` / `output_cost_per_token` etc., good at day-0 new models). Map their model ids → Godance `model_name`s via a maintained alias map (`pricing-alias-map.yml` or similar). Convert USD-per-token → microcents-per-1k integer (no `double` arithmetic — use `BigDecimal` then `longValue`).
3. **LLM fallback for the gaps** — Doubao, Qwen, Kimi, GLM, MiniMax, Seedream, Seedance, Kling, Hailuo, Wan-on-Volcengine, ERNIE: server-side `WebClient` `fetch` the provider's pricing page (HTML), pass it to a **cheap catalog model** (e.g. `gemini-3.1-flash-lite` or `deepseek-v4-flash`) with a structured-extraction prompt asking for `{model_id, input_usd_per_1m, output_usd_per_1m | per_image_usd | per_second_usd, source_url}` JSON. Cap pages/run, cap tokens/page, timeout per fetch. Capture the source URL + the extracted JSON + a short raw-snippet excerpt for the audit row. Best-effort — always lands as a proposal, never a direct write.
4. **Diff + record:** for each observed model+field, compare against `model_catalog`. **Always write the observation to `model_price_history`** (so the time series exists). If the delta exceeds `godance.pricing.noise-threshold-pct` (default 1%), open a `PRICE_CHANGE` proposal.
5. **Discovery:** an unseen model id whose stem matches an existing family (e.g. seen `claude-opus-4-8`, family `claude-opus-*`) → `NEW_VERSION` proposal; an entirely new id (e.g. `claude-mythos-5.0`) → `NEW_MODEL` proposal inserted as `status=DISCOVERED` — non-billable until a human adds an adapter mapping + markup decision.
6. **Sanity guards:** reject `≤ 0`, `> 10×` the prior value, `NaN`/non-finite, or values from a source with `429`/`5xx`. These become `status=ANOMALY` proposals, never silent.
7. **Alert:** a daily digest summarizing proposals (count by type, top % movers, anomalies). Use the existing `AuditLogService` + the email-stub infra — **do not build new notification plumbing**.

### 4. Approval surface (API, admin-only)

- `GET /v1/admin/pricing/proposals?status=PENDING` — list proposals.
- `POST /v1/admin/pricing/proposals/{id}/approve` — apply payload to `model_catalog`, write a `model_price_history` row, write an audit row, refresh the cache. Idempotent (re-approving a closed proposal returns the current state, doesn't double-apply).
- `POST /v1/admin/pricing/proposals/{id}/reject` — record decision, no catalog change.

Admin auth: the auth-security workstream did **not** ship a role distinction (every authenticated user is equal in `SecurityConfig`). Add it here as the lightest thing that works for a single-tenant operator-only system: a `godance.admin.emails` config allowlist matched against the JWT subject, gated by a small `AdminAuthorizationFilter` on `/v1/admin/**`. Operator email(s) live in `.env.prod`. **No schema change for this** — keep the V11 migration focused on the catalog tables. No UI in this brief.

### 5. Config (`godance.pricing.*`, env-overridable)

`refresh-cron`, `noise-threshold-pct`, sanity bounds (`min-microcents`, `max-multiplier`), LLM-fallback model id, page allowlist (regex of approved pricing pages), feed URLs (so they can be swapped in tests). Placeholders only — **no secrets committed**.

## Conventions & guardrails (recap)

Integer microcents end-to-end; reactive `Mono`/`Flux`; RFC 7807; the regional allowlist is untouched; WireMock cassettes for both feeds; **the job must never write a live billable price** (stage only). Don't add a single-stack alert/email/webhook — reuse what's there.

## Tests (target ~10–15 new)

- Feed-parsing: WireMock cassettes of OpenRouter and LiteLLM → correct `ObservedPrice` records (including USD/token → microcents/1k conversion).
- LLM fallback: stubbed page fetch + stubbed LLM response → proposal created with source URL captured.
- Diff: identical value → no proposal, history row still written; delta within noise → no proposal; delta above noise → `PRICE_CHANGE` proposal.
- Discovery: unseen family member → `NEW_VERSION`; brand-new id → `NEW_MODEL` with `status=DISCOVERED` and non-billable (a chat request to it returns 404).
- Sanity: `0`, `-1`, `100× prior`, `NaN` → `ANOMALY`, not `PROPOSAL`.
- Approval: approve → catalog + history + audit updated, `/v1/models` reflects the new value.
- Region gate: cron does nothing when `region != DIGITALOCEAN`.
- Boot integration: with the V10 seed loaded, `/v1/models` is byte-identical to the pre-migration response for the existing model set (lock the regression).

## Acceptance criteria

`cd backend && docker compose up -d && mvn clean test` green on branch. After V11 migrations:
- `model_catalog` is seeded from the e10be63 code values; `/v1/models` shape and per-model microcents are unchanged.
- A manual job run against recorded WireMock fixtures writes `model_price_history` rows and creates the expected proposals **without altering any live price**.
- Approving a proposal updates the catalog and the next `/v1/models` reflects it.
- Discovered models surface as `DISCOVERED`, non-billable, never charged a user.

## Out of scope

- The admin approval **UI** (later frontend pass).
- Auto-applying changes to billing.
- Changing markup tiers or `BillingProperties`.
- Building adapters for newly-discovered models (discovery only flags them).
- Any change to the regional-routing policy, the `Provider` enum, or any existing `ProviderAdapter`.
- Pushing to `origin` or deploying — the operator does that.

## Test-ripple heads-up (one specific gotcha)

Hardcoded cost assertions in existing integration tests (e.g. `ImageGenerationIntegrationTest.generate_gptImage2_...` asserts `6¢` from the `53 000 µ¢ × 1.12` math) lock the *billing outcome* downstream of the catalog. Because your V11 seed values match what's in code at e10be63, those tests stay green as-is. **Don't tweak the seed values "to round numbers" or anything similar** — if you do, the integration tests will ripple and you'll waste a round-trip.

## When you're done

`mvn clean test` green on `feature/dynamic-pricing`, all your commits on that branch (not on `main`), **no push**, **no deploy**. Stop and hand back — the operator will merge to `main` locally and decide on push/deploy later.
