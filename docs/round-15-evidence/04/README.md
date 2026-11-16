# Round 15 / 04 — Site discovery evidence

Base B0: `8219e0497cb12a7be48b80915601811e361726bf`. All five streams start
there and target main. The PR records its exact final head SHA and latest CI run.
No production setting or content was activated; no paid operation was used.

## Shared B0 baseline

Node v22.23.5 / npm 10.9.9, original lockfile. `npm ci` passed;
`npm run lint` inspected 109 files with zero diagnostics; `npm run build` emitted
8 pages / 22 complete paths; `npm test` passed **752 tests / 94 suites**, with
zero failed or skipped tests. Restricted subprocess streams initially prevented
Node tests from running; the approved normal-process rerun passed unchanged.
Raw local evidence remains ignored.

## Default output parity

B0 and candidate use Node 22, the same lockfile, UTC, `C.UTF-8`, footer year 2026,
all optional `PUBLIC_*`, `GALLERY_*`, `COMMUNITY_*` and `SITE_URL` variables unset,
and a fetch preload enforcing the existing offline catalog fallback by answering
`/v1/models` with 503. No external catalog/provider operation is performed.

`default-output-parity.json` contains every B0/candidate SHA-256, not a filtered
HTML comparison. Both builds have **22 paths**. No added or removed output;
the only changed bytes are exactly:

- `docs/index.html`
- `llms.txt`
- `llms-full.txt`

All shared CSS, hash filenames, assets, legacy pages, headers, redirects,
robots and sitemap bytes match. No documentation routes or sitemap exemptions
were added. New discovery styling is emitted inline only in enabled markup;
the legacy home component preserves the original output.

Recheck complete prepared build trees without any normalization:

```bash
node marketing/scripts/discovery/output-parity.mjs BASE_DIST CANDIDATE_DIST
```

## Acceptance checks

- Failing opening tests: 1 passed / 2 failed before implementation (missing enabled
  discovery and malformed-flag rejection). Focused implementation suite: 8/8 passed.
- Default and enabled lint: 119 files, zero errors/warnings/hints.
- Full default and enabled Node suites each passed **760 tests / 94 suites**,
  with zero failures/skips (116.81 seconds default; 106.90 seconds enabled).
  They include the original gallery schema,
  malformed configuration/content, empty gallery, comparison threshold, Run this,
  approved community/public profile, empty/error community, API and llms checks.
- New enabled-shell cases verify available/empty Gallery, comparison threshold,
  Run this off/on, and available/empty/API-failed Community. The discovery flag
  never creates content or bypasses the loaders.
- Internal links in every enabled output HTML target emitted paths. Product pages
  are exactly `/create` and `/integrations`, with canonical metadata/sitemap entries.
  App links use configured `PUBLIC_APP_URL` and existing protected routes; History
  guidance starts with selection, with no fabricated comparison/job ids.
- `npm audit --audit-level=high` passed: no high/critical findings; the existing
  Astro version reports one moderate advisory group. No dependency was added or
  changed in this stream.
- Four local Chromium journeys passed native-disclosure Enter/Tab/focus checks,
  main-navigation accessible names, expanded-menu visibility, no horizontal overflow
  at 390px/1440px, and no page errors. External requests were blocked.
- Screenshots contain only public explanatory UI and no identities, credentials,
  gallery fixtures or provider-generated media:
  `home-desktop-light.png`, `home-mobile-dark.png`, `create-desktop-dark.png`,
  `integrations-mobile-light.png`.

The marketing check verifies app destination construction. Real deployed sign-in,
onboarding and feature activation smoke checks are operator work after merge/deploy;
this session makes no production change. The separate app stream tests its existing
protected-route return behavior.

Operator checklist and exact public GitHub repository build inputs are in
`marketing/README.md`. Operator decisions remain real media/content rights,
licence/NOTICE/removal contact, community moderation, backend/account availability
and activation. Eventual integration order: **05/04, then 01, then 02/03**.
