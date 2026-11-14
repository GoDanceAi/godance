# Round 12 — Coordination

**Drafted:** 2026-11-13 · **Parent:** `main` with round 11 merged
**Workstreams:**
- 01 Recipe batches (backend)
- 02 Batches in the app (app)
- 03 Hand-over: capped keys, code, the AI-assistant prompt, n8n (backend,
  app, marketing, integrations)
- 04 OpenAI's video shape and docs for AI assistants (backend, marketing)

Context: the people Godance is built for end up generating in batches. A
recipe run over a sheet of products, names or frames is what makes their
work possible. They get there through one of three routes:
- the Studio, which is easiest
- a script their AI assistant writes
- a no-code tool such as n8n, which is how non-coders reach kie.ai and fal
  today

This round builds the batch, and makes each of the other two routes work
from the first try. See `docs/research/2026-11-prompt-gallery-competitors.md`
§5.6 and §5.7.

**Prerequisites.** Rounds 10 and 11 on `main`: recipes with variables and
input slots, origin and funnel, per-key image responses, and input files.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-batches    -b feature/recipe-batches main
git worktree add ../gd-batches-ui -b feature/batches-app main
git worktree add ../gd-handover   -b feature/hand-over main
git worktree add ../gd-video-oai  -b feature/openai-videos main
```

## Reserved resources

| Resource | 01 batches | 02 batches app | 03 hand-over | 04 videos and docs |
|---|---|---|---|---|
| Flyway | **V36** | none | none | **V37** (only if needed) |
| Backend | new `service/Batch*`, `web/BatchController`, `domain/Batch*`, a Quartz job, the funnel (one column) | none | `ApiKeyController`, `ApiKeyService`, `ApiKeyDtos` | new `web/OpenAiVideoController`, `backend/src/main/resources/static/openapi.yaml`, a route-drift test |
| App | none | new batch pages, recipe page (one button), admin funnel (one column) | Studio "View code", recipe page panel, API keys page | none |
| Marketing | `docs/index.astro` (last commit) | none | gallery recipe page panel, `scripts/n8n/**` (the template check), `docs/index.astro` (one section) | `public/llms-full.txt`, `public/llms.txt`, `docs/index.astro` |
| Other | none | none | new `integrations/n8n/**` | `docs/openai-dropin-verification.md` |
| Config | `godance.batches.*` | none | none | none |

## Shared contract

Additions only.

### Recipe batches (01)

A batch runs one recipe version over many rows. Each row becomes one job.

```
POST /v1/recipe-batches/estimate   (same body as create)  → 200 Estimate
POST /v1/recipe-batches                                    → 201 Batch
GET  /v1/recipe-batches?limit=&before=                     → [Batch], newest first
GET  /v1/recipe-batches/{id}                               → Batch
GET  /v1/recipe-batches/{id}/rows?limit=&after=            → [BatchRow], in row order
POST /v1/recipe-batches/{id}/cancel                        → Batch
POST /v1/recipe-batches/{id}/resume                        → Batch
GET  /v1/recipe-batches/{id}/results.csv
GET  /v1/recipe-batches/{id}/results.zip
```

Session or API key.

```json
POST /v1/recipe-batches
{ "version_id": "…", "model": "gpt-image-2", "params": { "size": "1024x1024" },
  "rows": [ { "variables": { "product": "blue mug" }, "inputs": { "product photo": { "file_id": "…" } } } ],
  "max_concurrency": 2, "webhook_url": "https://…" }

Estimate
{ "rows": 120, "total_cents": 720, "balance_cents": 1500, "enough": true }

Batch
{ "id": "…", "recipe_id": "…", "version_id": "…", "model": "gpt-image-2",
  "status": "RUNNING", "pause_reason": null,
  "rows_total": 120, "rows_done": 40, "rows_failed": 1,
  "estimated_total_cents": 720, "spent_cents": 238,
  "created_at": "…", "completed_at": null }

BatchRow
{ "index": 0, "status": "DONE", "variables": { … }, "job_id": "…",
  "cost_cents": 6, "error": null }
```

- **Status values:**
  - batch: `QUEUED`, `RUNNING`, `PAUSED`, `DONE` or `CANCELLED`
  - `pause_reason`: `insufficient_credit` or `spending_cap`
  - row: `PENDING`, `RUNNING`, `DONE`, `FAILED`, `SKIPPED` (after cancel)
- **Rows.** Each row's variables and inputs follow round 10's and round 11's
  recipe rules.
  - Model and params default to the version's.
  - A row that would fail validation fails the whole create with 400,
    naming the row index. Nothing is charged.
- **Charging.** Each job pre-debits when it starts, as any job does. A
  pre-debit refused for credit or for a spending cap pauses the batch.
  `resume` continues from where it stopped.
- **Webhook.** One signed webhook when the batch ends: event
  `recipe_batch.completed`, signed as job webhooks are. Jobs in a batch send
  no webhooks of their own.
- **Errors:** `batch_too_large` (400), `batch_not_found` (404),
  `batch_not_resumable` (409).
- **Jobs in a batch** carry `channel: "BATCH"`. Clients of round 10's
  `channel` must accept this new value.
- **`JobView`** gains `batch`: `{ "id": "…", "row": 17 }` or null.

### API keys (03)

- `POST /v1/keys` and `PATCH /v1/keys/{id}` accept `daily_cap_cents`,
  `monthly_cap_cents` and `expires_at`. A cap of null means none.
- `ApiKeySummary` gains those three fields, plus `spent_today_cents` and
  `spent_month_cents`.
- These routes stay session only.

### OpenAI's video shape (04)

These routes follow OpenAI's Videos API. 04 checks the current reference
before building.

```
POST   /v1/videos                 (JSON or multipart with input_reference)
GET    /v1/videos?limit=&after=
GET    /v1/videos/{id}
GET    /v1/videos/{id}/content?variant=video|thumbnail
DELETE /v1/videos/{id}
```

- They are a second view of Godance video jobs, not a separate store. A
  video's id is its job's id.
- `/v1/videos/generations` and `/v1/jobs` are unchanged.
- `input_reference` becomes a first-frame input under round 11's policy.

## Shared-file conflict watch

- `config/SecurityConfig.java`:
  - 01 adds `/v1/recipe-batches/**` to the API-key rule
  - 04 adds `/v1/videos/**` (the exact paths) and `/v1/openapi.yaml` (public)
- `service/AuditLogService.java`: 01 adds `batch.*`; 03 adds
  `api_key.caps_changed`.
- `app/src/lib/api.ts`, `app/e2e/mock-api.ts`: 02 and 03.
- The app's recipe page: 02 adds "Run as batch"; 03 adds the "Run at scale"
  panel. 03 rebases onto 02.
- `marketing/src/pages/docs/index.astro`: all four. 01, 03 and 04 in their
  last commit, each in its own section.
- `marketing/public/llms.txt`: 04 only. The other workstreams hand it their
  lines in their summaries.

## Hard rules (all sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- Run the full suite on the parent commit and record the count first.
- `app/` lint, build and e2e stay green. `marketing/` lint, build and test
  stay green.
- **A batch never charges more than its jobs.** Each job is pre-debited and
  settled by the existing path, and the batch adds no charge of its own.
- **No row is ever submitted twice.** Save a row's job id before the
  provider is called.
- Billing changes carry a test in the same change. Money in integer cents.
- The regional allowlist is not touched, and batches route through it like
  any job.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

1. **01** first.
2. **02** after 01. It develops against mocks before that.
3. **03** at any time, rebased onto 02 for the recipe page.
4. **04** at any time.

## Operator decisions

- **Batch limits:**
  - `godance.batches.max-rows`: suggested 500
  - `max-concurrency` per batch: suggested 2, with a cap of 4
  - concurrent batches per account: suggested 2
- **Whether a batch holds its whole estimate up front.** The default here is
  no: each job pre-debits when it starts, and the batch pauses when credit
  runs out. Holding up front would need a new ledger entry type.
- **How long results links last.** Links in `results.csv` are signed for at
  most the storage `presign-ttl`. Re-download for fresh links.
- **The size cap for `results.zip`.** Suggested: 500 MB.
- **Publishing the n8n templates** in n8n's template library under the
  Godance name. That needs an n8n account.
- **The AI-assistant prompt's wording.** 03 ships a draft in one constant.
- **Whether `/v1/openapi.yaml` is public.** Suggested: yes.
