# Round 15 / 02 — Billing on existing APIs

Branch `codex/r15-billing-screens`; PR base `main`.
B0: `8219e0497cb12a7be48b80915601811e361726bf`.
Planning pack: `3de1b9d8f1e6021825ca6435cedb06a8ac43c6e1` (all eight root briefs verified byte-identical).
The final commit SHA is recorded in the PR description and coordinator report.

## Behavior and existing contracts

| Control | Existing endpoint | Behavior |
| --- | --- | --- |
| Card / ACH / SEPA top-up | POST /v1/billing/topup | Explicit checkout click; unchecked save-card choice sends save_card=true only when selected. Saving alone does not enable recurring charging. |
| USDT top-up | POST /v1/billing/nowpayments/topup | Explicit click; never sends save-card intent. |
| Balance refresh | GET /v1/auth/me | Read only; URL return parameters never confirm payment. |
| Recent usage | GET /v1/usage?limit=200 | Immediate explicit customer-field allowlist; internal provider cost and all other unlisted fields are discarded before view state. Filters/totals describe only loaded rows. No complete-history or export API is invented. |
| Auto-recharge status | GET /v1/billing/auto-recharge | Confirmed amounts, method availability, failure status; loading/error/refresh states. |
| Enable/update enabled auto-recharge | PUT /v1/billing/auto-recharge | Review dialog states both exact USD amounts and off-session meaning; only explicit Confirm submits. Cancel submits nothing. |
| Disable auto-recharge | Same PUT, enabled=false | Separate explicit action, no automatic retry. |

Money input parsing, summation and amount formatting use integer cents/BigInt; the maximum-safe-integer boundary retains its final cent. Zero threshold is accepted. Deployment amount bounds remain authoritative on the server; the UI does not promise fixed bounds. Draft consent wording lives in the single marked DRAFT_AUTO_RECHARGE_CONSENT source constant for operator review.

Pending actions have synchronous duplicate-submit guards. Failed writes retain drafts and the last confirmed server status; a 409 refetches status because the server may clear a non-card saved method. Other failures require an explicit status refresh before another review. There is no write from render, effects, route entry, parameters or automatic retry. Account-keyed components and fenced/aborted reads prevent cross-account results and redirects.

## Shared baseline and owned validation

Linux; Node 22.23.5; npm 10.9.9; synthetic browser API mocks; no real payment, saved card or provider operation.

- One common B0 app baseline in the detached r15-baseline worktree: npm ci, npm run lint, npm run build, E2E_PORT=4180 npm run test:e2e -- --workers=2; **1340 passed**, desktop/mobile, no failures/skips. Install audited 99 packages with zero vulnerabilities. Raw common logs remain ignored.
- This branch: npm ci, npm run lint, npm run build passed. Existing large-bundle advisory remains. No dependency was added.
- Final focused command: `E2E_PORT=4182 npm run test:e2e -- e2e/billing.spec.ts e2e/billing-features.spec.ts e2e/auth.spec.ts e2e/session.spec.ts e2e/referral.spec.ts --workers=2`: **90 passed**, desktop/mobile, no failures/skips.
- Tests cover confirmation/cancellation, enabled updates, zero threshold, invalid/unsafe cents, precise maximum-safe-integer review, pending duplicate submission, separate disable, missing card, 409/refetch, 502 failure, GET denial/failure/retry, expired session, checkout return, existing top-ups and sign-in/session paths. Distinctive synthetic internal cost is absent from visible text and accessibility snapshots; customer cost remains exact.
- Opening test proved failure before implementation: the review/threshold controls did not exist. An initial visual check found focus wrapping needed explicit handling; the fixed dialog passed keyboard Enter, Shift+Tab wrap, Escape and focus restoration. Component screenshots were then recaptured and visually inspected.
- Independent peer review verified money/write boundaries and identified the maximum-safe-integer formatting case, now fixed and tested.

The final PR must pass all four latest-head required jobs: backend (mvn verify), app (build), app (e2e), marketing (build). Local focused success does not replace full PR CI.

## Safe screenshots

Synthetic example.test account and fabricated usage only. The screenshots show the owned components; the inherited B0 mobile shell is changed independently by stream 01.

| View | Usage | Review |
| --- | --- | --- |
| Desktop light | [usage](desktop-light-usage.png) | [review](desktop-light-review.png) |
| Desktop dark | [usage](desktop-dark-usage.png) | [review](desktop-dark-review.png) |
| Mobile light | [usage](mobile-light-usage.png) | [review](mobile-light-review.png) |
| Mobile dark | [usage](mobile-dark-usage.png) | [review](mobile-dark-review.png) |

## Ownership, integration and operator decisions

Stream 02 owns BillingPage, dedicated billing components/helper/tests, and the explicitly coordinated minimal shared mock-api.ts hunk adding read-only billing/usage defaults for existing billing/referral/session flows. Other streams use feature-specific mocks. No shared navigation, chat, backend behavior, default or payment integration changes.

Eventual operator integration order: **05/04, then 01, then 02/03**. All five branches start at B0 and target main. No merge, auto-merge or deployment is authorized. Operator review of the marked off-session consent wording remains open; production activation, provider availability and marketing content approval are separate decisions.
