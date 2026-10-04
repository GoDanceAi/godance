# Brief 04 — Frontend Consolidation — Completion Report

**Status:** ✅ Complete and merged to `main`
**Branch:** `feature/frontend` · **Worktree:** `gd-frontend/`
**Merge commit:** `156edb8` (last of the 01→02→03→04 round-2 sequence)

---

## What was delivered

All five brief-04 deliverables, across the React/Vite SPA (`app/`) and the
Astro marketing site (`marketing/`).

| # | Deliverable | Files |
|---|---|---|
| 1 | **Model picker** — Smart/Fast/Cheap/Creative presets resolved from live `GET /v1/models`, plus an Advanced exact-model dropdown. Default surface shows presets only — no model name. | `app/src/pages/ChatPage.tsx` |
| 2 | **Pricing/docs regen** — `/models` and `/pricing` generated at build time from `/v1/models` with a static fallback; USDT added to payment-method copy. | `marketing/src/lib/catalog.ts` (new), `marketing/src/pages/models.astro`, `pricing.astro`, `index.astro`, `docs/index.astro`, `components/Footer.astro` |
| 3 | **Billing USDT top-up** — NOWPayments USDT method alongside Stripe card flow, one credit balance. | `app/src/pages/BillingPage.tsx` |
| 4 | **Status page** — enriched `/v1/status`: latency p50/p95/p99 + error-rate columns, state pills. | `marketing/src/pages/status.astro` |
| 5 | **OAuth login** — Google/Apple/X buttons + `/oauth/callback` route. | `app/src/pages/LoginPage.tsx`, `OAuthCallbackPage.tsx` (new), `App.tsx`, `lib/api.ts` |

---

## How it was executed

Briefs 01–03 had **not** been merged when brief 04 started. Per the operator's
decision, all five deliverables were built **speculatively** against the API
contracts described in briefs 01/02, then reconciled once 01–03 landed.

### Speculative phase (commit `83c69de`)
- `/v1/status` and `/v1/billing/nowpayments/topup` already existed on `main`
  from round 1 — those two deliverables were **not actually blocked**.
- Only OAuth was genuinely speculative (brief 01 unmerged).
- No literal `"USDC"` string existed (Session 15 already removed it) — the
  brief item was satisfied by surfacing **USDT** in the payment-method copy.
- A dark-mode contrast bug was found via screenshots (selected billing card
  used a static-light `bg-brand-50`) and fixed with theme-aware tokens.

### Reconciliation phase (commit `cc3b7b1`, after merging 01–03)
Two mismatches between the speculative code and the shipped backend:

1. **OAuth callback contract.** Assumed `?token=<jwt>` (query param). Brief 01's
   `OAuthLoginSuccessHandler` actually returns the JWT in the URL **fragment**:
   `/oauth/callback#access_token=<jwt>&token_type=Bearer&expires_at=<iso>`.
   `OAuthCallbackPage` now parses `window.location.hash` for `access_token` and
   scrubs it from history. Registration ids (`google`/`apple`/`x`) and the
   `/oauth/callback` path were correct as-guessed.
2. **Status `errorRate` field.** Brief 02's `StatusController` added an
   `errorRate` field (fraction of `errors + rate-limits / requests`). The page
   originally computed `errorCount/requestCount` only, which would disagree
   with the `state` pill. It now consumes the backend field directly.

---

## Verification

- **Builds:** both apps build clean after merging 01–03 — `tsc -b` + Vite
  (SPA), `astro build` (marketing). No type errors.
- **Marketing catalog:** build-time fetch confirmed pulling the live round-1
  catalog from `api.godance.ai` (Qwen 3.6, Claude Haiku 4.5, etc.); falls back
  to a static snapshot when the API is unreachable.
- **Screenshots:** 16 light/dark Playwright screenshots of every changed page,
  reviewed. Dark-mode contrast bug caught and fixed this way.
- **OAuth fragment round-trip:** Playwright test — callback with
  `#access_token=…` lands on `/dashboard` with the token stored.
- **Status page:** re-verified with the real `errorRate` shape — a
  rate-limited model shows 13.1% matching its `degraded` pill.
- **Merge:** frontend↔backend file sets were disjoint — **zero conflicts**.

---

## Commit trail

```
156edb8  Merge feature/frontend — model picker, USDT top-up, status UI, OAuth buttons
cc3b7b1  Reconcile frontend with the merged brief 01/02 backend contracts
5c510ae  Merge main into feature/frontend (briefs 01-03 backend)
83c69de  Surface round-2 backend features across both frontends
```

---

## Remaining operator steps

- **Not pushed** to `origin` — `git push origin main` when ready.
- **Not deployed** — run the `wrangler pages deploy` builds per
  `SESSION-HANDOFF.md` (`godance-app` + `godance-marketing` projects).
- **OAuth credentials** — real client IDs/secrets for Google, Apple
  (Services ID + signing key), and X must go in `.env.prod`; dev placeholders
  are in `application.yml`. The SPA callback URL is `/oauth/callback`
  (`OAUTH_POST_LOGIN_REDIRECT_URI`).
- **Worktree cleanup** — `git worktree remove gd-frontend` once satisfied.
