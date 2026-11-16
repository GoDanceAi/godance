# Round 15 / 01 — App navigation evidence

Base B0: `8219e0497cb12a7be48b80915601811e361726bf`.
Reviewed implementation: `ad9457b` (plus this evidence-only commit).
Planning pack: `3de1b9d8f1e6021825ca6435cedb06a8ac43c6e1`, read from the original checkout; planning files are not duplicated on this branch.

## Result and ownership

The existing routes are grouped into Create, Library, Developer tools and Account, behind one native Menu disclosure. Every permitted core destination is visible on desktop and mobile without horizontal scrolling; Batches has its own entry and current-page state. Dashboard explains History selection for comparisons, versioned recipes, batches and the existing Run at scale options. Recipes now keeps gallery bookmarks in a separate section with reload, empty, unavailable-example, read-error, removal-error and removal states. Generated outputs remain in History.

Invite and Following use the existing per-account referral/community providers. Provider reads and follow callbacks are fenced to the current session; discovery is cleared on account change/logout, including a later session for the same account. Following's empty state offers the existing public community index only after it can read successful HTML containing published-recipe links on that site. No admin-feature endpoint, provider probe, invented editor, generation, terms acceptance, payment or key write is added to navigation.

This stream owns Layout, Dashboard, Recipes/library, Following, their tests, and the coordinated account-fencing hunks in `community-context.tsx` and `referral.tsx`. It does not edit App.tsx, billing, chat, admin navigation, backend, global styles or shared API/mock helpers.

## Shared B0 baseline

2026-11-16; Node v22.23.5, npm 10.9.9, Linux, America/Toronto. One detached B0 worktree on the data volume supplied the common app baseline. `npm ci`, `npm run lint`, `npm run build` passed. `E2E_PORT=4180 npm run test:e2e -- --workers=2`: **1,340 passed**, no failed or skipped tests, 6.8 minutes. The coordinator retains raw logs in the ignored common evidence directory.

## Branch verification

Same toolchain and locked dependencies, isolated app/gallery/API origins and synthetic browser mocks. `npm ci`, `npm run lint`, `npm run build`: passed. Vite retains its existing large-chunk warning; no dependency or audit policy changed.

- Opening navigation tests against B0: 6 failed / 2 passed, demonstrating absent grouped destinations and public discovery. Non-current account results were already fenced for some paths; coverage was expanded to same-account sessions.
- Focused navigation/library checks: **100 passed**, desktop/mobile. An earlier focused run caught unequal Dashboard link height after grouping; the implementation was corrected and the original no-wrap assertion retained.
- Final `E2E_PORT=4181 R15_CAPTURE_EVIDENCE=1 npm run test:e2e -- --workers=2`: **1,358 passed**, no failed or skipped tests, 5.7 minutes. The 18 added cases cover core destinations, optional loading/denied/error/shown states, late same/different-account results, keyboard menu focus, 320px layout, verified/unavailable/empty/unreadable community output, bookmarks/reload/errors/removal and signed-out batch return.
- Existing full-suite input age/terms, pending-consent, auth/social return, first-touch, compare selection, saved-output, Studio, BYOK/profile/Publish and batch flows pass unchanged in behavior.
- `git diff --check`: passed. Required latest-head remote CI is reported separately in the PR; local browser success is not a backend or remote-CI claim.

Browser tests verify Enter/Space opening and closing Menu, focus retention and keyboard progression, current-page indication, and no side scrolling. Core and optional link states cause no generation/payment/configuration write. Public discovery uses a no-credentials read and fails closed on content/site/CORS failure.

## Safe screenshots

All accounts, balances, prompts and bookmarks below are synthetic. No real provider, card or account operation was performed. The images were visually reviewed for layout and light/dark rendering.

| Surface | Light | Dark |
| --- | --- | --- |
| Desktop Dashboard/menu | [Light](dashboard-desktop-light.png) | [Dark](dashboard-desktop-dark.png) |
| Mobile Dashboard/menu | [Light](dashboard-mobile-light.png) | [Dark](dashboard-mobile-dark.png) |
| Desktop Recipes/bookmarks | [Light](library-desktop-light.png) | [Dark](library-desktop-dark.png) |
| Mobile Recipes/bookmarks | [Light](library-mobile-light.png) | [Dark](library-mobile-dark.png) |

## Limits and operator decisions

The public site's current headers do not grant cross-origin reads of `/community`. Consequently the empty-state public-discovery CTA remains hidden on such deployments, even when the backend community is open. A readable same-origin deployment or an independently reviewed operator CORS decision is needed to show it. This branch deliberately does not alter site headers, defaults or production settings. The existing Following route and account profile/publishing controls retain their own backend gates. An empty/error/unreadable public site is never advertised as available.

Suggested eventual integration order: 05/04, then 01, then 02/03. All five PRs target main from the common B0; merging, deployment and activation remain operator actions.
