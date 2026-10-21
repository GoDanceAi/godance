# Brief 04 — Frontend Consolidation (merges last)

**Branch:** `feature/frontend` · **No migration** · Read both coordination docs first. Own worktree; commit as you go. **Merge after briefs 01–03** — this consumes their new endpoints.

## Mission

Surface everything the backend batches added, in one frontend pass. This is deliberately a single instance so the heavily-shared frontend pages (pricing, nav, billing) aren't fought over by parallel instances.

## Scope

Two apps: the React/Vite SPA (`frontend-app`) and the Astro marketing site (`frontend-marketing`). Preserve routing, auth, SSE streaming, SEO/JSON-LD, and the light/dark `[data-theme]` token system throughout.

- **Model picker:** populate the Advanced dropdown and the Smart/Fast/Cheap/Creative presets from `GET /v1/models` across text, image, and video — replacing the static fallback list. A first-time user should still never see a model name on the default surface.
- **Pricing + docs (marketing):** regenerate the pricing/docs pages from `/v1/models` so they reflect the full new catalog. **Fix the hero copy "USDC" → "USDT".**
- **Billing UI:** add the NOWPayments USDT top-up option alongside the Stripe card flow (one credit balance, "pay any way you like" framing — not "crypto-native"; see the project's brand rule).
- **Status page (`/status`):** render the enriched `/v1/status` (latency percentiles, error rate, state pills). Depends on brief 02.
- **Login:** add Google / Apple / X.com buttons wired to the OAuth endpoints, keeping email/password. Depends on brief 01.

## Build / verify

Build per `SESSION-HANDOFF.md` (`wrangler pages deploy`). Capture Playwright screenshots (light + dark) of every changed page — model picker, pricing, billing/top-up, status, login — and eyeball them before pushing.

## Out of scope

Backend logic — consume the APIs only. Do not touch Java. Do not lead any copy with "blockchain"/"crypto-native".
