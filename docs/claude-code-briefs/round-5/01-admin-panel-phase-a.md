# Brief 01 — Admin Control Panel, Phase A (ops-critical)

**Branch:** `feature/admin-panel` (worktree `../gd-admin-panel`) off `main` @ `c9d87b1`
**Reserved migration:** V12 (only if schema is actually needed)
**Baseline:** full suite green on parent (~234 tests; confirm exact count first)

## Mission (one line)

Give the operator a browser UI for the four admin jobs that today require
curl + SSH + psql: approving pricing proposals, managing the model catalog,
looking up users / adjusting credits, and seeing revenue vs cost.

## Why now

The daily pricing cron (round 3) stages `model_change_proposal` rows that can
only be approved via `POST /v1/admin/pricing/proposals/{id}/approve` with a
JWT — no UI exists. Once Stripe flips live (P1), user lookup + manual credit
adjustment become support's first needs. Full context:
`../../Godance-Project-Review-2026-07-02.html` (Admin Control Panel tab).

## Read first

1. This brief + `00-coordination.md` (worktree rules, reserved numbers)
2. `backend/CLAUDE.md` — engineering conventions + hard constraints
3. Existing admin plumbing (extend, don't reinvent):
   - `web/AdminPricingController.java` (`/v1/admin/pricing` — proposals list/approve/reject)
   - `security/AdminAuthorizationFilter.java` + `config/AdminProperties.java`
     (email allowlist on `/v1/admin/**`, fail-closed — your endpoints inherit
     this for free by living under `/v1/admin/`)
4. Domain you'll read/write: `domain/` catalog entities (`model_catalog`,
   `model_price_history`, `model_change_proposal`), credit ledger service +
   entities, `UsageRecord` (+ V10 observability columns), audit-event pattern
   (grep `billing.autorecharge.fired` for the shape)
5. Frontend: `app/src/App.tsx` (react-router v6), `app/src/lib/api.ts`
   (client + the existing `role: 'USER' | 'ADMIN'` type), `BillingPage.tsx`
   as the reference page style; design system = "Unified Ledger"
   (violet/cyan, `[data-theme]` light/dark) — match existing components.

## Scope — backend (all under `/v1/admin/**`)

1. `GET /v1/admin/me` → 200 `{email}` if caller passes the admin filter
   (the SPA uses this to reveal the Admin nav; non-admins get the filter's 403).
2. **Users:** `GET /v1/admin/users?q=` (email substring or UUID → id, email,
   role, balance, created, key count, caps) ·
   `GET /v1/admin/users/{id}/ledger?page=` (existing ledger, paged) ·
   `POST /v1/admin/users/{id}/credit-adjust` `{amountCents, note}` — positive
   or negative, append-only ledger entry via the existing ledger service
   (follow its idempotency pattern; entry type: reuse an existing enum value
   if one fits, else add `ADMIN_ADJUSTMENT` — check whether the type column
   is a Postgres enum or varchar before assuming a migration is needed).
   **Every adjustment writes an audit event** (`admin.credit.adjusted` with
   admin email, target user, amount, note).
3. **Catalog:** `GET /v1/admin/pricing/models` (full catalog incl. disabled,
   with current price + markup) · `PATCH /v1/admin/pricing/models/{id}`
   `{enabled?, markupPercent?}` — audited (`admin.catalog.updated`).
   Price history for one model: `GET /v1/admin/pricing/models/{id}/history`.
4. **Overview:** `GET /v1/admin/usage/summary?from=&to=&groupBy=day` →
   per-day `{date, revenueCents, costCents, requests}` + top-5 models and
   top-5 users by spend in the window. Aggregate from `UsageRecord`; keep it
   one repository query per series (no N+1).

## Scope — frontend (`app/src/pages/admin/**`)

- Route tree `/admin/*` behind the existing auth guard **plus** an admin
  check: call `/v1/admin/me` once after login; render the Admin nav entry
  and routes only on 200. (Server remains the real gate.)
- Four pages, in priority order:
  1. **Proposals** — table of pending proposals (type, model, old→new price,
     source, age), Approve/Reject buttons with confirm; empty state.
  2. **Catalog** — table with enable/disable toggle + markup editor inline;
     price-history sparkline or mini-table per row (fetch on expand).
  3. **Users** — search box → result table → detail drawer: balance, caps,
     keys, paged ledger, credit-adjust form (amount + required note,
     confirm dialog showing resulting balance).
  4. **Overview** — revenue vs cost per day (last 30d) as a simple chart
     (no new heavy chart dep; a lightweight SVG/CSS bar approach is fine),
     top models/users tables.

## Hard rules

- Commit to `feature/admin-panel` as you go; `git merge --no-ff -m "..."`.
- **No `git push`. No deploy.** No edits outside: `backend/src/**` (admin
  packages + tests), `app/src/**`, V12 migration if needed.
- Do NOT touch `.env.prod.template` (02 owns it this round), `routing/`,
  `backend/deploy/`, `marketing/`.
- Before tests: `cd backend && docker compose up -d`.
- Every mutating endpoint: audit event + integration test proving a
  non-allowlisted JWT gets 403 and an allowlisted one succeeds
  (`AdminPricingControllerIntegrationTest` shows the harness pattern).

## Opening move

Probe before building: boot the stack locally, hit the existing
`/v1/admin/pricing/proposals` with an allowlisted test JWT (see how the
existing integration test mints one), and read the ledger service's write
path end-to-end. Then post your 3–5 bullet build plan before coding.

## Acceptance criteria

- `mvn clean test` green (baseline + your new tests; expect +15 or more).
- `cd app && npm ci && npm run build` green (tsc + vite).
- All four pages functional against local stack; screenshot or curl
  transcript of each admin endpoint in the final summary.
- 403 coverage for every new endpoint; audit events for every mutation.
- All work committed on `feature/admin-panel`; `main` untouched.

## Out of scope (Phase B/C — do not start)

Refunds, failed auto-recharge queue, provider ops UI, audit-log viewer page,
key management across users, alerts config, routing policy editor, margin
dashboard, any email sending (brief 02's territory).
