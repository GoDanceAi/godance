# Round 15 / 05 — Read-only admin feature configuration

Base B0: `8219e0497cb12a7be48b80915601811e361726bf`.
Planning reference: `3de1b9d8f1e6021825ca6435cedb06a8ac43c6e1`.
Branch: `codex/r15-admin-features`, PR base `main`.
Tested implementation head: `a32bb6116bbc0732b6f7c05f19e4b8cc2328619b` (subsequent evidence-only commits are identified in the PR).
Review and final verification: [PR 65](https://github.com/GoDanceAi/godance/pull/65).
Environment: Linux, Java 21.0.13, Node 22.23.5, npm 10.9.9; isolated data-volume worktree. No new dependency or production setting.

## Scope and derivation

`GET /v1/admin/features` uses the existing `@AdminRoles(ADMIN)` role/session/active/verified/2FA filter. The response contains exactly six groups and eight booleans. The controller receives only six immutable property objects; it has no repository, provider client, environment reader or mutation service.

| Response | Existing bound source |
| --- | --- |
| inputs.enabled / allow_people | InputsProperties.enabled / allowPeople |
| community.enabled | CommunityProperties.enabled |
| byok.enabled | nonempty ByokProperties.providers |
| routing_policies.enabled / allow_list_restricted | UserRoutingPolicyProperties.enabled / nonempty allowList |
| referrals.enabled | ReferralProperties.enabled (either reward positive) |
| mcp.enabled | ai.godance.mcp.McpProperties.enabled (existing default true) |

The ADMIN-only page explains configuration versus eligibility, content, marketing flags and health. It offers only GET/Refresh. Its feature-specific API helper explicitly maps boolean fields and rejects missing/nonboolean data. Account-keyed state and aborted/fenced reads prevent a late old-account response from appearing. Existing 2FA enrollment remains intact. Admin tabs wrap on mobile, and the new navigation landmark is labelled.

Public OpenAPI intentionally excludes all admin routes. The backend README therefore contains the concise route/response/auth reference; no public configuration catalog was added.

## Shared B0 baseline

The coordinator captured one detached B0 baseline (raw logs remain ignored under `.claude/round-15-evidence/`).

- App: `npm ci`, `npm run lint`, `npm run build`, `E2E_PORT=4180 npm run test:e2e -- --workers=2`: all passed; **1,340 browser cases**, desktop/mobile, no failures/skips.
- Marketing: `npm ci`, `npm run lint`, `npm run build`, `npm test`: all passed; **752 tests / 94 suites**, lint 109 files with no diagnostics, 8-page / 22-path default build.
- Backend: common `docker compose up -d`, then `mvn -B verify`: **2,297 passed**, zero failures/errors/skips, BUILD SUCCESS / exit 0 in 26:46. Maven completed packaging after a delayed Surefire fork-shutdown diagnostic; no test was disabled.

## Owned validation

- `npm ci`: passed, 99 packages audited, zero vulnerabilities. The initial restricted-sandbox native esbuild spawn failed with EPERM; the authorized rerun succeeded without code or dependency changes.
- `npm run lint`, `npm run build`: passed. Build retains the preexisting large-chunk advisory.
- `mvn -B -Dtest=AdminFeaturesControllerTest test`: **4 passed**, no failures/errors/skips. Exact default/alternate schema, synthetic private values redacted, referral source alternatives and independent routing restriction tested.
- `E2E_PORT=4185 R15_SCREENSHOTS=1 npm run test:e2e -- e2e/admin-features.spec.ts e2e/admin-roles.spec.ts e2e/admin-funnel.spec.ts e2e/admin-routing-margins.spec.ts`: **56 passed**, desktop/mobile, no failures/skips. This includes 24 new feature-status cases and 32 existing admin cases.
- New cases cover ADMIN/current link, SUPPORT/FINANCE hidden link/direct route, unresolved role, loading, failed GET/retry, malformed schema, backend denial, 2FA waiting, expired session, sign-in return path and late old-account reads. Refresh was activated using keyboard focus/Enter; page width fits both viewports. No feature endpoint mutation is made.
- Failing opening evidence: new schema test could not compile before the controller existed; new desktop/mobile feature case failed before the page/route existed. Raw red/green logs are ignored. A first green-attempt browser test incorrectly expected a query-string sign-in destination; it was corrected to the existing route-state auth contract and explicit return-path coverage was added.
- `mvn -B -Dtest=AdminFeaturesControllerTest,AdminFeaturesIntegrationTest,AdminRolesIntegrationTest test`: **150 passed** (4 schema, 12 endpoint, 134 exact-role inventory), zero failures/errors/skips, BUILD SUCCESS / exit 0. The route inventory covers ADMIN API keys and every role. Dedicated endpoint cases additionally cover unauthenticated/2FA/inactive/unverified accounts and denied POST/PUT/PATCH/DELETE.
- `mvn -B -Dtest=AdminFeaturesControllerTest,OpenApiDriftTest test`: **12 passed** (4 schema + 8 drift), zero failures/errors/skips. Admin routes remain excluded from public OpenAPI as before.
- The final full local `mvn -B verify` result and latest-head CI states are recorded in the [PR description and checks](https://github.com/GoDanceAi/godance/pull/65). The source under test is the implementation head above; the evidence update changes documentation only.
- Final latest-head PR CI requires all four named jobs and any additional required checks. The PR description records the verified head and run link; pending/skipped/failed runs do not count as passed.

## Safe visual evidence

All screenshots use an obviously synthetic `example.test` administrator and mock booleans. No real provider or customer operation occurs.

- [Desktop light](features-desktop-light.png)
- [Desktop dark](features-desktop-dark.png)
- [Mobile light](features-mobile-light.png)
- [Mobile dark](features-mobile-dark.png)

Screenshots were visually inspected. Visible/hidden links, keyboard focus, loading and denied states are independently asserted in the browser suite.

## Integration and operator decisions

Eventual operator integration order: **05/04, then 01, then 02/03**. All five branches independently start at B0 and target main. This stream owns only its new admin route/import in App.tsx and AdminLayout.tsx navigation, plus dedicated helper/page/tests and backend route/DTO/tests/docs. It changes no customer discovery, feature default, migration, provider probe or runtime switch. Marketing and backend capability activation remain operator decisions outside this implementation. No deployment or merge is performed.
