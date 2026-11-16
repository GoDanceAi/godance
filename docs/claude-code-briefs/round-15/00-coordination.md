# Round 15 — Navigation and existing-feature completion

**Drafted:** 2026-11-16 · **Design checked against:** `origin/main` at `8219e0497cb12a7be48b80915601811e361726bf`.
**Status:** implementation briefs; no production activation is authorized by these documents.

## Goal

Make the features built in rounds 7–14 discoverable and finish two bounded screens using existing APIs. Most Studio, recipe, batch, MCP, community and BYOK interfaces already exist. Reuse them; do not rebuild the backend or invent destinations.

Five workstreams:

| ID | Brief | Branch | PR base |
| --- | --- | --- | --- |
| 05 | [Admin feature status](05-admin-features.md) | `codex/r15-admin-features` | `main` |
| 04 | [Site links and docs](04-site-links.md) | `codex/r15-site-links` | `main` |
| 01 | [App links](01-app-links.md) | `codex/r15-app-links` | `main` |
| 02 | [Billing screens](02-billing-screens.md) | `codex/r15-billing-screens` | `main` |
| 03 | [Chat history](03-chat-history.md) | `codex/r15-chat-history` | `main` |

**All five branches start at the same pinned `origin/main` commit, B0.** They do not depend on another feature branch. Billing and Chat use existing routes and the existing app shell; they must not edit the shared navigation. This replaces the contradictory proposal to target child PRs at 01 while also merging 01 first.

Suggested implementation order is 05, 04, 01, 02, 03. Implementation may overlap when ownership is respected. Suggested eventual integration order is 05/04, then 01, then 02/03; that is an operator instruction, not authorization for this session to merge anything.

## Setup and worktrees

1. Read applicable project instructions, this contract, [session openings](session-prompts.md), the owned brief in full, and `backend/CLAUDE.md`. Verify names against current source; old memory summaries are not a substitute for code.
2. Inspect the root checkout, staged/unstaged/untracked work and existing worktrees. Preserve them. Fetch without pruning and record the full verified `origin/main` SHA as B0; do not advance a feature branch onto unrelated work silently.
3. Keep the root checkout and these briefs intact. The authoritative planning pack is on `origin/codex/round-15-briefs` and may not yet be on main. If absent locally, read it with `git show origin/codex/round-15-briefs:docs/claude-code-briefs/round-15/<filename>` or copy only these eight planning files into an ignored reading snapshot; record its planning commit SHA. Root copies must match that ref. Do not switch the shared root, base implementation on the planning branch merely to obtain instructions, or commit duplicate briefs on five implementation branches. The planning PR is separate from the five implementation PRs.
4. Set `REPO` to the original project root. Create a dedicated worktree for each stream under `$REPO/.claude/worktrees/r15-*`. Confirm with resolved paths and mount inspection that they remain on `/mnt/disco-grande`, the data volume. Reuse only a matching, verified worktree; never reset or remove existing work to claim a name.
5. A detached baseline worktree may also live there. Store raw local evidence under ignored `.claude/round-15-evidence/`; keep the private handoff and all reconciliation backups ignored and untouched.
6. Each stream records its base/head SHA, writes a 3–5 bullet implementation plan in the session before code, and commits in reviewable steps (`area: summary`).

## Features contract: links appear only when usable

A usable link leads to an existing permitted page or a legitimate sign-in/onboarding/empty state. A user's empty History, Recipes or Batches does not make the page unavailable. Feature configuration, account eligibility, content readiness and provider readiness are different facts.

| Surface | Source of truth | Required behavior |
| --- | --- | --- |
| Core app | Existing protected routes: `/dashboard`, `/chat`, `/create/image`, `/create/video`, `/history`, `/recipes`, `/batches`, `/keys`, `/billing`, `/settings` | Keep reachable for eligible signed-in users; preserve destination after sign-in. No invented flags. |
| Compare | History selection and existing `/compare` flow | Guide users to select compatible jobs; do not fabricate job IDs or promise a populated comparison from a bare link. |
| Inputs | `/v1/auth/me.inputs`, adult/terms rules, selected model capabilities | Reuse existing gates and terms flow. No upload/animate affordance that bypasses them. |
| Referrals | Existing `/v1/referrals/me` provider, `enabled: true` | Show Invite only when enabled; retain current reward and account rules. |
| Community in app | Existing `/v1/profile` / community provider | Show Follow/Publish/profile discovery only when permitted. Unknown, denied or failed probes do not become enabled. |
| BYOK | Existing `/v1/provider-credentials` and Settings component | Reuse support/status/used-for rules, including management of an existing unsupported credential. Do not interpret a nonempty array as permission to add every provider. |
| Admin | Existing `/v1/admin/me`, role and 2FA handling | Preserve role-specific routes. Feature status in 05 is ADMIN-only. |
| Gallery on site | `loadGallery().on` and existing `runThis` rule | Navigation only when real valid content and required configuration exist; Run this remains a separate gate. |
| Community on site | `loadCommunity().on` | No link without readable approved public content. A build failure/empty response must not create a broken destination. |
| MCP | Existing API-key setup panel; backend configured status is in 05 only | A setup/docs link is not a health claim. Never probe API-key-only `/mcp` with a browser session or expose admin status to customers. |
| User routing | Existing API documentation only | No invented `/settings/routing` or policy editor in this round. |

Per-user probe results must be tagged to the current account, cleared on logout/account change, and ignored when stale. Do not poll admin endpoints to decide customer navigation. No new customer capability endpoint is authorized. Routing-policy authoring and batch-fallback creation controls are deferred; this round links to existing workflows and accurate API documentation.

The new `/v1/admin/features` reports backend configuration only. It cannot establish a user's eligibility, published content, a reachable provider, or marketing deployment readiness.

## Backend and data boundaries

The only new backend behavior is 05's read-only `GET /v1/admin/features`, its narrowly scoped DTO, authorization and tests/documentation. No other controller/service behavior changes, migration, configuration default changes, switch setters, runtime override storage, provider probes or new payment behavior.

Existing API wrappers and feature-specific modules can be added in the app. Do not invent endpoints for receipts, complete usage history, card removal, conversation rename/restore/hard delete, message editing or idempotency.

No new runtime dependency without first explaining the need and alternatives. Prefer the dependencies and native browser controls already present.

## Money and chat rules

- Use integer cents for money arithmetic; validate safe integers and convert decimal input strings without floating-point rounding. Display conversion is only at the boundary. The server remains authoritative for configurable amount bounds.
- A card-saving choice is explicit and unchecked by default. Enabling or changing enabled auto-recharge requires a reviewed confirmation stating both the balance threshold and the recharge amount, and explaining off-session charging. Never initiate/retry a charge configuration from render, navigation, URL parameters or an effect.
- `providerCostCents` currently exists in `/v1/usage`. 02 must discard it with an explicit frontend field allowlist before view/export state; it must not render it in any form. This round does not remove it from the backend response.
- Chat generation and conversation persistence are separate. A save failure cannot hide a completed answer, restart generation, or spend again.
- New chats stay ephemeral until an **ADULT** explicitly chooses to save that conversation. Saved-history reads/writes and controls are adult-only. Children keep the existing ephemeral chat behavior and make no conversation-history calls. This preserves the current `ParentalNotice.java` promise without changing legal/backend scope.
- No automatic retry of conversation create/append POSTs. Ambiguous network outcomes require reconciliation before replay; the API does not offer exactly-once persistence. Do not store transcripts in localStorage, URLs or telemetry.

## Shared-file ownership and conflict watch

| Owner | Owned surfaces |
| --- | --- |
| 01 | `app/src/components/Layout.tsx`, Dashboard, Recipes/library discovery, Following empty-state links, app navigation tests |
| 02 | `BillingPage.tsx`, new billing components/API helpers, billing tests |
| 03 | `ChatPage.tsx`, new chat-history components/API helpers, chat tests |
| 04 | Marketing navigation/footer/home/docs/discovery modules/pages/tests, marketing build environment wiring in `deploy-frontends.yml` |
| 05 | New admin feature-status DTO/controller/tests, `AdminRolesIntegrationTest`, admin feature-status page/helper, `AdminLayout.tsx`, the single new route in `App.tsx`, backend API documentation if needed |

Use feature-specific helpers built on exported `apiFetch`, and feature-specific mock helpers. Avoid parallel edits to `app/src/lib/api.ts`, `app/e2e/mock-api.ts`, global app CSS and global marketing CSS. If an existing shared file really needs an additive hunk, name its sole owner before editing and record the integration requirement. Do not resolve another stream's conflicts by overwriting its work.

## Marketing default-build contract

04 introduces one public build flag, `PUBLIC_SITE_DISCOVERY_ENABLED`, default false. It gates the new discovery shell/homepage and `/create` + `/integrations` product pages. Existing gallery/community gates remain independent and cannot be bypassed by this flag. This is a marketing rollout flag, not a backend activation switch.

With all optional repository/build variables unset, legacy emitted files must be byte-identical to the B0 build except the named documentation outputs allowed in 04. There are no blanket exemptions for generated CSS, JS, hash filenames or timestamps. Test deterministic builds; Tailwind can change shared CSS merely by scanning new classes in a disabled component.

No production variables, real content, moderation decisions, backend flags or deployment state are changed by the implementation session. Deliver a precise activation checklist alongside the opt-in preview; operator activation is separate from opening green PRs.

## Checks and evidence

Capture **one shared baseline at B0** for each surface, with commands, environment, counts and any failures. Each stream references that baseline and checks its owned surface; it need not rerun every baseline independently.

| Surface | Commands / gate |
| --- | --- |
| Backend | Start/check backing services once with `cd backend && docker compose up -d`; then `mvn -B verify`. 05 also runs focused endpoint/role tests and final full verify. |
| App | `npm ci`, `npm run lint`, `npm run build`, `npm run test:e2e`. 01 runs the full E2E suite after shared navigation changes; other streams run focused owned flows locally, with full final PR CI. |
| Marketing | `npm ci`, `npm run lint`, `npm run build`, `npm test`; 04 also supplies default/enabled build comparisons and link checks. Marketing Node tests are a required local check even if CI omits them. |
| Final PRs | The final PR CI run passes `backend (mvn verify)`, `app (build)`, `app (e2e)` and `marketing (build)`, plus every additional required check, on the latest head. Queued/running/skipped/failed is not green; report the exact state. |

Serialize backend integration/full runs: compose uses shared names/ports. Assign app worktrees distinct `E2E_PORT` values (for example 4181, 4182, 4183, 4185). Never tear down another run's services.

If Docker or another prerequisite is unavailable, record the concrete blocker, run independent checks, and use required PR CI as the backend gate; do not report the local suite as passed. Investigate baseline and change-induced failures separately, without weakening tests or audits. Continue independent work while a stream is blocked.

Evidence must include visible and hidden link states, deep-link/sign-in behavior, affected desktop/mobile flows, keyboard navigation, and light/dark screenshots of representative new UI. Use synthetic accounts/data only. Keep raw logs/private data ignored; publish only reviewed summaries and safe screenshots under stream-owned `docs/round-15-evidence/<id>/` or accessible CI artifacts.

## Git and completion

The implementation prompt authorizes pushing the five feature branches and opening one PR per stream. Never push to main, merge a PR, enable auto-merge, bypass branch rules, or deploy. Do not force-push someone else's branch. Preserve the current root branch and local work.

Each PR description explains the user-visible problem/result, scope, base B0/head SHA, baseline reference, focused checks/counts, evidence, limitations, and the eventual integration order. No private handoff, ignored backups, operator identities, real credentials or raw sensitive payloads in commits, PR descriptions, logs or screenshots. Reserved example.test identities and obviously unusable test placeholders are fine.

Done means every acceptance criterion is met, all five PRs are open, and all four named CI jobs plus every additional required check are green on each latest head. If any criterion/check is unresolved, report partial completion honestly instead of declaring the round finished. Final report lists each branch, PR, head SHA, tests before/after, evidence, anything undone, integration order and open operator decisions.

Operator decisions remain: activation of the marketing preview/backend capabilities; real gallery content, media rights/contact/licence and community moderation; review of the marked auto-recharge consent wording. Safe code defaults do not decide those matters.
