# Godance — Backend Bootstrap Handoff

> Historical document. This is the brief that bootstrapped the backend in
> Session 1. It's kept in the repo because the architectural decisions
> (regional allowlist, entity model, session trajectory) are still
> load-bearing — but the day-to-day project status is in the code, the
> tests, and `CLAUDE.md`, not here.

Godance (godance.ai) is a multimodal AI aggregator with text, image,
video, and audio behind one OpenAI-compatible API. Stripe primary
billing, Coinbase Commerce for crypto top-ups, single credit ledger.

---

## Step 1 — Read Before Coding

1. **[`./CLAUDE.md`](./CLAUDE.md)** — persistent project memory. Hard
   constraints, tech stack, code conventions.
2. **[`./README.md`](./README.md)** — quickstart + dev environment notes.

---

## Step 2 — Session 1 Goal: Scaffold Only

The first session produced exactly this and stopped:

1. **Maven project scaffold** at the folder root:
   - `pom.xml` — Java 21, Spring Boot 3.4+, WebFlux, JPA, Flyway, Spring
     Security, Lombok, Testcontainers, JUnit 5, Mockito, WireMock.
   - `src/main/java/ai/godance/...` package structure.
   - `src/main/resources/application.yml` (dev profile) and
     `application-prod.yml` skeleton.
   - `src/test/java/ai/godance/...` test root.
2. **Application bootstrap:**
   - `GodanceApplication.java` — main class.
   - A `/healthz` endpoint returning `200 OK` with build info (version,
     git SHA, region name from `REGION` env var). This endpoint is the one
     an external load balancer / DNS failover polls.
3. **Local dev `docker-compose.yml`:**
   - Postgres 16, Redis 7, MinIO (S3-compatible object storage).
   - `docker compose up` works; `application.yml` connects.
4. **Flyway scaffolding:**
   - `src/main/resources/db/migration/` folder.
   - `V1__init.sql` containing only a comment header listing the entity
     port plan (no DDL yet).
5. **`README.md`** at the folder root.
6. **`.gitignore`** — standard Java + Maven + IDE + macOS.

**Session 1 acceptance criteria:**

- `mvn clean verify` passes with zero tests (placeholder test allowed).
- `docker compose up -d` brings up Postgres, Redis, MinIO without errors.
- `mvn spring-boot:run` starts the app.
- `curl localhost:8080/healthz` returns 200 with JSON body containing
  `region: "local"`, `version`, and `git_sha`.

**Out of scope for Session 1:**

- Entity port — Session 2.
- Provider integrations — Sessions 6+.
- Billing — Sessions 4–5.
- Frontend — Sessions 10–11.
- CI/CD — later.

---

## Step 3 — Entity Model (Session 2 Reference)

Godance is **single-tenant** by design. No `tenant_id` columns on any
entity. Every concept below is per-user.

**Core entities** (ported from a Python predecessor; the source is not
public, so the names + columns below are the authoritative reference):

| Entity | Notes |
|---|---|
| `User` | `credit_balance_cents` (long), `daily_credit_cap_cents` (Long, nullable), `monthly_credit_cap_cents` (Long, nullable). Plus auto-recharge fields added in a later session. |
| `ApiKey` | Per-user. Optional per-key spend caps. |
| `UsageRecord` | One row per upstream call. Adds `region` (enum), `cache_hit` (boolean), `fallback_used` (boolean) beyond the obvious billing columns. |
| `AuditLogEntry` | Append-only — no `@Modifying` queries against this table. |
| `ScheduledTrigger` | Used by Quartz for per-user cron tasks (auto-recharge, daily reports). |

**Godance-specific entities (designed from scratch):**

- **`CreditLedger`** — append-only ledger of all balance changes.
  - Columns: `id`, `user_id` (FK), `delta_cents` (signed long), `source`
    (enum: `STRIPE`, `COINBASE`, `DEBIT`, `REFUND`, `ADJUSTMENT`, `PROMO`),
    `source_ref` (Stripe charge ID, Coinbase charge ID, request ID, etc.),
    `occurred_at`, `idempotency_key` (UNIQUE), `note` (text, optional).
  - Reads compute balance via SUM — no separate `credit_balance` mutation.
    The `User.credit_balance_cents` is a denormalized cache, updated in
    the same transaction as the ledger insert.
- **`GenerationJob`** — async image / video / long-audio jobs.
  - Columns: `id`, `user_id` (FK), `modality` (enum: `IMAGE`, `VIDEO`,
    `AUDIO_TTS`, `AUDIO_STT`), `provider`, `model`, `prompt_json` (jsonb),
    `status` (enum: `QUEUED`, `RUNNING`, `DONE`, `FAILED`, `CANCELLED`),
    `output_url`, `estimated_cost_cents`, `actual_cost_cents`,
    `created_at`, `started_at`, `completed_at`, `webhook_url`,
    `error_message`.
- **`ProviderHealth`** — rolling per-provider metrics, aggregated every
  ~minute from raw `UsageRecord` rows by a Spring `@Scheduled` job.
  - Columns: `id`, `provider`, `model`, `window_start`, `window_end`,
    `request_count`, `error_count`, `rate_limit_count`, `p50_ms`,
    `p95_ms`, `p99_ms`.

**Session 2 acceptance criteria:**

- Flyway migrations apply cleanly against a fresh Postgres.
- JPA entities compile and have basic `JpaRepository` interfaces.
- A Testcontainers-backed integration test persists and reads one row
  per table successfully.
- The regional allowlist constant (`AlicloudProviderAllowlist`) exists
  even though the gateway isn't built yet — establishes the policy in
  code before any code path could violate it.

---

## Step 4 — Session Trajectory

Historical sketch; the actual sessions may have split or combined as the
work surfaced new decisions.

| Session | Focus |
|---|---|
| 3 | Spring Security + JWT, signup/login, API key CRUD |
| 4 | Credit ledger + Stripe top-up + Stripe webhook handler |
| 5 | Coinbase Commerce webhook + idempotency replay tests |
| 6 | Provider gateway skeleton + OpenAI text provider + `/v1/chat/completions` (non-streaming) |
| 7 | SSE streaming + Anthropic + Google Vertex + fallback chain |
| 8 | Image gen (gpt-image + Flux) + `GenerationJob` + `/v1/images/generations` |
| 9 | Video gen (Volcengine Ark Seedance, Sora) + async polling + completion webhooks |
| 10 | Marketing site (Astro) + initial chat UI (Vite/React) |
| 11 | React frontend scaffold — auth, dashboard, billing UI |
| 12 | Dashboard data surfaces: `/v1/models`, `/v1/usage`, `/v1/jobs`, `/v1/status`, conversation history, CORS |
| 13 | Embedded Quartz + auto-recharge (off-session Stripe PaymentIntent) |

---

## Hard Stops — Verify Before Coding

1. **Java 21 confirmed.** Spring Boot 3.4+ minimum is 17 but Java 21 LTS
   gives virtual threads and pattern matching. Set
   `<maven.compiler.release>21</maven.compiler.release>`.
2. **Maven, not Gradle.**
3. **Regional routing is code, not config.** The
   `ProviderGateway.route(request)` method must check the region constant
   against a hardcoded allowlist before dispatching to any provider. A
   config flag is NOT enough — config can be mis-set in prod. The check
   is in the code path, with a unit test for the negative case (OpenAI
   request from `REGION=alicloud` → 503).

---

## Reporting Back

Use the repository's GitHub issues / discussions / pull requests for
status updates, design discussion, and questions. Append notable
architectural decisions to `CLAUDE.md` so future contributors don't have
to re-derive them.
