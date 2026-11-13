# Round 7 — Coordination

**Date opened:** 2026-11-12 · **Parent:** `main` @ `bce9491`
**Workstreams:** 01 Job API foundations (backend) · 02 Studio and History (app) ·
03 Signup credit (backend, ships disabled)

Context: image and video generation work through the API but cannot be used
from the app, and a finished job cannot be read back in full (no prompt, one
output only, a link that expires before the file does). This round makes a
generation fully readable through the API, makes each model describe what it
accepts, and builds the app's Studio and History pages on top.

**Prerequisite:** the generation billing fix (charge for delivered outputs;
price an omitted `seconds` on the adapter's default) must be on `main`
before the round starts. Update the parent commit above when it is.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-job-api       -b feature/job-api-foundations main
git worktree add ../gd-studio        -b feature/studio-history main
git worktree add ../gd-signup-credit -b feature/signup-credit main
```

## Reserved resources

| Resource | 01 job-api | 02 studio | 03 signup-credit |
|---|---|---|---|
| Flyway migrations | **V23** (job outputs), **V24** (catalog metadata) | none | none expected (**V25** only if forced) |
| Backend | jobs, generation services, model catalog, `/v1/models`, new estimate endpoint, retention | none | new `service/SignupCredit*`, new `config/PromoProperties`, five call sites |
| Frontend | none | `app/src/**`, `app/e2e/**` | none |
| Marketing | `docs/index.astro`, `public/llms.txt` (last commit only) | none | none |
| Config / env | none | none | `godance.promo.*`, `PROMO_SIGNUP_CREDIT_CENTS` |

**V12 is unused and stays unused.** Flyway would reject a version below the
ones already applied.

## Shared contract (01 builds it; 02 codes against it with mocks)

Additions only. Every field and route that exists today keeps its shape.

**`GET /v1/jobs/{id}`** — existing `JobView` fields unchanged, plus:

```json
{
  "prompt": "a lighthouse at dusk",
  "params": { "size": "1024x1024", "n": 2 },
  "outputs": [
    { "index": 0, "url": "https://…signed…", "contentType": "image/png",
      "sizeBytes": 812345, "revisedPrompt": null }
  ],
  "outputUrl": "https://…signed…",
  "purged": false,
  "mediaExpiresAt": "2027-02-10T09:00:00Z"
}
```

- `params` uses the request's field names: `size`, `n`, `style`, `quality`
  for images; `size`, `seconds`, `n` for video.
- `outputs[].url` is signed when the job is read. `outputs` is empty until
  the job is `DONE`, and empty again once purged.
- `outputUrl` stays: the first output, freshly signed.
- After purge: `prompt` is null, `params` is `{}`, `purged` is true.
- `mediaExpiresAt` is null when the files are not scheduled for deletion.
  Nothing sets that this round, but a later round will keep saved
  generations past the retention period, so clients must accept null.

**`GET /v1/jobs`** — still a bare JSON array of `JobView`, newest first. New
optional query parameters: `limit` (1–100), `before` (a job id; returns jobs
older than it), `modality` (`IMAGE` | `VIDEO`), `status`. Without `limit` the
full list is returned, as today.

**`GET /v1/models`** — each concrete entry's `godance` block gains
`display_name`, `description` and `capabilities`. All three may be absent.

```json
"capabilities": { "sizes": ["1024x1024", "1536x1024"], "default_size": "1024x1024", "max_n": 4 }
"capabilities": { "sizes": ["1280x720"], "default_size": "1280x720",
                  "durations": [5, 10], "default_seconds": 5, "max_n": 1 }
```

Absent `capabilities` means unknown, not unlimited. A missing `max_n` is 1.

**`POST /v1/estimates`** — session or API key. No charge, no job.

```json
{ "model": "flux-1.1-pro", "n": 1, "size": "1024x1024" }
→ 200 { "model": "flux-1.1-pro", "modality": "IMAGE", "n": 1, "seconds": null,
        "estimated_cost_cents": 4, "currency": "USD" }
```

The figure is the exact pre-debit a matching generation request would take.
Text models answer 400.

## Shared-file conflict watch

- `config/SecurityConfig.java` — 01 adds one rule (`/v1/estimates`). 03 does not touch it.
- `service/AuditLogService.java` — 03 adds event constants. 01 adds none.
- `backend/src/main/resources/application.yml` — 03 adds `godance.promo.*`. 01 adds nothing.
- `service/CreditLedgerService.java` — 01 calls `reconcile`, 03 calls `topUp`.
  Neither changes a signature. If either must, stop and flag it.
- `app/src/lib/api.ts` — 02 only.

## Hard rules (all sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks.
- **`main` auto-deploys** the API and both frontends. Nothing reaches it
  without the operator.
- Before backend tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- Baseline: run the full suite on the parent commit and record the count
  before changing anything. `StabilitySdxlProviderTest` has a known timing
  flake; re-run it alone before calling it a regression.
- Frontends: `npm ci && npm run lint && npm run build` stays green in both
  `app/` and `marketing/`. The app also runs `npm run test:e2e`.
- Billing and ledger changes carry a test in the same change.
- The regional provider allowlist is not touched this round.
- Public repo: no secrets, private URLs or operator notes in committed files.
  Secrets only as `${ENV_VAR:visibly_fake_dummy}` placeholders.

## Merge order

1. **01 first.** Deploy and verify: `/healthz` shows the new commit, and
   reading a fresh job returns `outputs`.
2. **02 after 01 is live.** The app still falls back to `outputUrl` when
   `outputs` is absent, so a rollback of 01 does not break it.
3. **03 at any point.** It ships disabled.

## Operator decisions

- **Default page size for `GET /v1/jobs`.** 01 keeps the unlimited default
  for compatibility. Decide separately whether to cap it.
- **Signup credit amount: decided, $1.00.** 03 still ships with a code
  default of 0, so a fresh deployment of this public repo grants nothing.
  Production turns it on with `PROMO_SIGNUP_CREDIT_CENTS=100`. Every grant
  is real provider spend.
