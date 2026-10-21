# Brief 01 — Dependabot / Dependency Triage

**Branch:** `feature/dependency-triage` (worktree `../gd-deps`) off `main` @ `2be06f8`
**Baseline:** 283 backend tests green; both frontends build clean.

## Mission (one line)

Clear the safe Dependabot alerts (no behavior change), then evaluate the
major-version ones behind a build+test gate — landing the low-risk wins now
and writing up the risky ones rather than force-upgrading blindly.

## Why this is low-drama but worth doing

Most alerts sit in the **frontend build toolchain** (Vite/esbuild/PostCSS/
Astro), not code that runs in front of users at request time — the marketing
site ships as static HTML, the SPA as static assets. So the security urgency
is modest, but the alert count (40) is noise that hides any *real* future
alert. Goal: drive it down with safe bumps, and turn each remaining major
into a deliberate, documented decision.

## Read first

1. This brief + `00-coordination.md`.
2. GitHub → repo → Security → Dependabot: this is the **authoritative list**,
   and the ONLY place the ~13 backend/Maven alerts are visible (npm audit
   can't see them). Read the Maven ones there before touching `pom.xml`.
3. `app/package.json`, `marketing/package.json`, `backend/pom.xml`.

## Captured fix data (npm audit, 2026-09-03) — start here

### app/ (SPA)
Safe, non-breaking (`npm audit fix` handles these):
`browserslist`, `nanoid`, `postcss`, `postcss-selector-parser`,
`@babel/core`, `react-router-dom` / `react-router` / `@remix-run/router`.

Semver-major (needs judgment + a build/manual check):
- `vite` high (5.x → 7.x) — pulls the `esbuild` moderate with it.

### marketing/ (Astro)
Safe, non-breaking: `vite`, `svgo`, `postcss`, `nanoid`, `js-yaml`,
`browserslist`, `postcss-selector-parser`.

Semver-major (needs judgment):
- `astro` high (5.x → 7.3.1) — a two-major jump; carries the biggest
  breaking-change risk (config, integrations, content collections API).
- `sharp` high (→ pulled by the astro bump) — native image lib.
- `@astrojs/tailwind` (deprecated; Astro 5+ prefers `@tailwindcss/vite` or
  the standalone Tailwind setup) — note but don't chase in this round.

### backend/ (Maven — confirm against the Dependabot tab)
Current: Spring Boot 3.4.2, Testcontainers 2.0.5, stripe-java 28.4.0,
aws-sdk 2.30.7, Nimbus JOSE (transitive). Most Maven alerts will be
transitive (Netty/Spring/logback/etc.). Prefer a **Spring Boot BOM patch
bump** (3.4.2 → latest 3.4.x) to sweep managed transitives in one move;
add explicit `<dependencyManagement>` overrides only for alerts the BOM
bump doesn't resolve.

## Plan of work (commit per step)

1. **app safe fixes** — `cd app && npm audit fix` (NOT `--force`), then
   `npm ci && npm run build`. Commit: "app: safe dependency bumps (Dependabot)".
2. **marketing safe fixes** — same, in `marketing/`. Commit likewise.
3. **backend BOM patch** — bump Spring Boot to the latest 3.4.x patch in
   `pom.xml`; `docker compose up -d && mvn clean test`. Add targeted
   overrides for any Maven alert still open per the Dependabot tab. Commit:
   "backend: Spring Boot 3.4.x patch + targeted CVE overrides".
4. **Evaluate the majors — do NOT force-upgrade in this round unless clean.**
   For `vite` (app) and `astro`/`sharp` (marketing): attempt the upgrade on a
   scratch commit, run the build, and:
   - If the build passes untouched and the rendered output is unchanged →
     keep it, commit separately ("app: Vite 7 major upgrade").
   - If it needs config/API migration → **revert, and write findings** into
     `docs/dependency-triage-2026-09.md` (what breaks, est. effort, the
     specific advisories still open) rather than half-migrating. Astro 5→7
     especially is likely its own future workstream.
5. **Write `docs/dependency-triage-2026-09.md`**: before/after alert counts
   (from `npm audit` + the Dependabot tab), what was bumped, what was
   deferred and why, and the residual-risk note (build-time vs runtime).

## Verification (per the local-first gate)

- `cd backend && docker compose up -d && mvn clean test` → green (283+;
  isolate the Stability flake if it appears).
- `cd app && npm ci && npm run build` → green.
- `cd marketing && npm ci && npm run build` → green; spot-check the built
  `dist/` (e.g. the `/brand/` page and homepage) renders, since marketing
  ships static.
- `npm audit` before/after counts recorded in the doc.

## Acceptance criteria

- Safe bumps landed; both frontends build; backend suite green.
- Every major either upgraded-and-verified or explicitly deferred with a
  written reason. No half-done migrations on the branch.
- `docs/dependency-triage-2026-09.md` present.
- All commits on `feature/dependency-triage`; `main` untouched; no push/deploy.

## Out of scope

Runtime code changes, framework migrations beyond a clean drop-in, the
`@astrojs/tailwind` → `@tailwindcss/vite` migration (note it, defer it),
CI/workflow edits, and anything requiring new secrets.
