# Dependency triage — September 2026 (round 6, brief 01)

Branch `feature/dependency-triage`, off `main` @ `2be06f8`. Written 2026-09-03.
All numbers below were measured on that day; the "after" column describes
the state of this branch, not the Security tab (which only recomputes once
the branch is merged and pushed).

## Outcome at a glance

| Surface | Source of truth | Before | After (this branch) |
|---|---|---|---|
| Whole repo | GitHub → Security → Dependabot (via API) | **40** open: 18 high, 16 moderate, 6 low | expected **9**: 2 high, 4 moderate, 3 low — all on the Astro line in `marketing/` |
| `app/` (React SPA) | `npm audit` | 10 (4 high, 4 moderate, 2 low) | **0** |
| `marketing/` (Astro, static) | `npm audit` | 11 (8 high, 3 low) | **3** (astro, esbuild, and `@astrojs/tailwind` only because it depends on astro) |
| `backend/` (Maven) | OSV scan of the resolved `mvn dependency:list` — Dependabot cannot see the backend at all, see below | **105** advisories on 35 of 203 artifacts, incl. 2 CRITICAL | **16** on 10: 14 compile-scope, all on Spring Boot/Data/Security lines that have no 3.4.x fix; 2 test-scope |

Every commit was gated: `npm ci && npm run build` in both frontends,
`docker compose up -d && mvn clean test` for the backend (289 tests — the
brief's 283 predates the OAuth-hardening merge, which added six), and a
browser check of the built bundle behind each frontend major.

## The Dependabot tab shows zero Maven alerts — and that is a blind spot, not a clean bill

The brief expected ~13 backend alerts. The API returns none in any state
(open, fixed, dismissed, auto-dismissed). The reason is visible in the
repo's dependency-graph SBOM: every Spring/Flyway/Testcontainers artifact
from `backend/pom.xml` is listed with version `?`, because GitHub's Maven
parser does not resolve versions managed by the Spring Boot parent BOM and
does not walk transitives. Dependabot therefore only "sees" the five
artifacts with literal versions (stripe-java 28.4.0, testcontainers-bom
2.0.5, aws-sdk bom 2.30.7, wiremock 3.10.0, git-commit-id plugin 9.0.1) —
none of which has an advisory. Netty, Jackson, Spring Framework, Spring
Security, logback, the JDBC driver: all invisible.

The substitute used here is OSV (`https://api.osv.dev/v1/querybatch`) over
the artifacts printed by `mvn dependency:list` (203 entries). The recipe is
in the appendix; the raw before/after lists were captured for each backend
commit. If the team wants this to be continuous rather than manual, the
options are a `dependency-submission` GitHub Action for Maven (makes
Dependabot see the resolved tree) or an OSV/OWASP step in the backend
workflow — both are CI/workflow edits and therefore out of scope for this
round.

## What landed (one commit per step)

| Commit | Change | Closes | Gate |
|---|---|---|---|
| `62c0b1b` app: safe dependency bumps | `npm audit fix` (no `--force`); lockfile only | 10 of the 17 app alerts: babel core, react-router 6.30.6, browserslist, nanoid, postcss, postcss-selector-parser | build green on Vite 5.4.21 |
| `f0899da` marketing: safe dependency bumps | `npm audit fix`; lockfile only (astro 5.18.1 → 5.18.2, vite 6.4.3, svgo 4.1.0, js-yaml 4.3.2, browserslist, nanoid, postcss, postcss-selector-parser) | 13 of the 23 marketing alerts | build green; `dist/` byte-identical except the `<meta name="generator">` version |
| `b6f7b63` marketing: sharp 0.35 via npm `overrides` | Astro 5 declares `sharp ^0.34` (optional); the libvips fixes are in 0.35, out of range | #27 (high) | build green; `dist/` byte-identical; nothing in `src/` uses astro:assets, so sharp never runs |
| `5db0d19` backend: Spring Boot 3.4.2 → 3.4.13 | parent bump; 106 managed transitives move | OSV 105 → 86 advisories (Spring Security CVE-2025-41232 CRITICAL, reactor-netty CVE-2025-22227, nimbus CVE-2025-53864, json-smart CVE-2024-57699, …) | 289 tests green |
| `20e9f98` app: Vite 5 → 7 | `vite ^7.3.6`; plugin-react 4.7.0 unchanged (already declares `^7`) | #2, #5, #6 (vite), #1 (esbuild) | tsc + build untouched; bundle loaded in a browser, empty console |
| `6508090` app: React Router 6 → 7 | `react-router-dom ^7.18.3` | #7, #8, #9 — none has a 6.x fix | tsc + build untouched; redirect/state, query-string and link navigation verified in a browser |
| `d8001ea` backend: targeted CVE overrides | version properties + one exclusion on top of 3.4.13 (details below) | OSV 86 → 16 | 289 tests green |

Versions actually locked after this branch: app — vite 7.3.6, esbuild
0.28.2, react-router 7.18.3, postcss 8.5.28, nanoid 3.3.18, browserslist
4.28.8, @babel/core 7.29.7; marketing — astro 5.18.2, vite 6.4.3, esbuild
0.27.7 (plus 0.25.12 nested under vite), sharp 0.35.4, svgo 4.1.0, js-yaml
4.3.2; backend — Spring Boot 3.4.13 with Spring Framework 6.2.19, Spring
Security 6.4.13, Netty 4.1.137, Jackson 2.18.10, reactor-netty 1.2.13,
Micrometer 1.15.12, logback 1.5.38, PostgreSQL JDBC 42.7.13.

### Backend overrides in detail (`backend/pom.xml`)

Same-line patch bumps unless noted. Each property name is the one the
Spring Boot BOM itself uses, so the override flows through the BOM's
dependency management rather than through ad-hoc `<dependencyManagement>`
entries.

| Property | 3.4.13 managed | Now | Why |
|---|---|---|---|
| `netty.version` | 4.1.130.Final | 4.1.137.Final | 35 advisories across netty-codec-http/-http2/-dns/-handler/-resolver-dns/-epoll (request smuggling, HTTP/2 DoS, SNI/TLS, DNS cache poisoning — all HIGH/MODERATE, 2026) |
| `jackson-bom.version` | 2.18.5 | 2.18.10 | 9 advisories in jackson-core/-databind: CVE-2026-54512/54513 (PolymorphicTypeValidator bypass, HIGH), CVE-2026-54514 (SSRF), CVE-2026-18401 + follow-up (async parser DoS), … |
| `spring-framework.version` | 6.2.15 | 6.2.19 | 17 advisories in spring-webflux/-web/-expression/-core (CVE-2026-41839…41854, CVE-2026-22735/22737/22740/22741/22745). Framework 6.2 is still patched because Boot 3.5 shares it |
| `postgresql.version` | 42.7.8 | 42.7.13 | CVE-2026-42198, CVE-2026-54291 (HIGH) |
| `logback.version` | 1.5.22 | 1.5.38 | CVE-2026-1225/9828/10532 (LOW) |
| `log4j2.version` | 2.24.3 | 2.25.5 | CVE-2026-49844 in log4j-api (only present via the log4j-to-slf4j bridge) |
| `micrometer.version` | 1.14.14 | 1.15.12 | **cross-minor** — the 1.14 line has no fix for CVE-2026-40984 (HTTP server instrumentation DoS, HIGH). 1.15 is what Boot 3.5 pairs with; the suite boots actuator + WebFlux instrumentation on every integration test and passed |
| exclusion: `com.mchange:mchange-commons-java` on `spring-boot-starter-quartz` | 0.2.15 | removed | Quartz declares it for its own c3p0 pool; the Boot BOM already excludes c3p0 (and HikariCP-java7), so nothing on the classpath references it. CVE-2026-27727 / CVE-2026-55153 (JNDI RCE, HIGH). No source references `mchange` or `c3p0` |

Not touched, deliberately: stripe-java 28.4.0, aws-sdk 2.30.7, wiremock
3.10.0, testcontainers 2.0.5, git-commit-id 9.0.1 — no OSV findings for the
versions in use, and the brief limits overrides to open advisories.

## Major-version decisions

### app: Vite 5.4 → 7.3.6 — upgraded and kept

No 5.x backport exists for any of the four vite/esbuild advisories, so a
major was unavoidable. Vite 7 was the right target: `@vitejs/plugin-react`
4.7.0 already declares `^7.0.0`, the project pins `build.target: 'es2022'`
explicitly (so Vite 7's new default browser target does not apply), and
`tsc -b && vite build` passed with no config or source change. Vite 7 needs
Node ^20.19 or ≥22.12; CI builds on Node 22. The emitted `index.html` is
structurally identical (same script/stylesheet tags, hashed names aside).
The built bundle was served with `vite preview` and checked in a browser:
`/login` renders, `/signup` is reachable client-side, console empty.

Vite 8.2.2 (npm's `latest`) was **not** taken: it swaps Rollup for
Rolldown and needs plugin-react ≥ 5.2 — a bundler change, not a security
fix. 7.x still receives security patches (7.3.5 shipped the fix for #5/#6).

### app: React Router 6.30 → 7.18.3 — upgraded and kept

Not in the brief's captured `npm audit` data (which was grouped per
package), but three Dependabot alerts on react-router (#7, #8, #9) have no
6.x fix at all — #8 is explicitly "no patched version" for
`react-router-dom 6.30.2–6.30.4`. The SPA uses only declarative-mode APIs
(`BrowserRouter`, `Routes`, `Route`, `Link`, `NavLink`, `Navigate`,
`Outlet`, `useNavigate`, `useLocation`, `useSearchParams`) and has no splat
routes, so the v7 defaults (the former `v7_*` future flags) change nothing.
Build passed untouched. Browser checks on the built bundle: `/dashboard`
redirects to `/login` carrying `state.from = "/dashboard"`,
`/verify-email?token=…` reads its query string, and login ↔ signup link
navigation works; the only console entry is the expected refused fetch to
the absent local API. Bundle grows 227 kB → 243 kB (70 → 76 kB gzip).

Applicability note: the SPA's only runtime-influenced navigation targets
are `location.state.from` (set internally from `location.pathname`) and an
encoded `oauth_error` query value, so the open-redirect advisories were
not directly reachable from a crafted URL — but they were still open
alerts, and v7 is where the fixes live.

### marketing: sharp 0.34 → 0.35 via `overrides` — kept

The alert is HIGH but the exposure is nil here: sharp is an optional
dependency of Astro used only by `astro:assets`, and nothing under
`marketing/src` imports `astro:assets`, `<Image>`, `<Picture>` or
`getImage`. The override pins sharp 0.35.4 (Linux x64/arm64 binaries are
recorded in the lockfile for CI); `npm ci && npm run build` is green and
`dist/` is byte-identical to the build before the override. **Remove the
override when Astro is upgraded** to a line whose own sharp range is
≥ 0.35 (Astro 7 resolves 0.35.4 by itself).

### marketing: Astro 5.18 → 7.3.1 — deferred (attempted, reverted)

This is the one that keeps the remaining 9 alerts open, so it was tried
properly on a scratch commit (reverted; nothing from it is on the branch):

- `npm install astro@^7.3.1 @astrojs/sitemap@latest` succeeds, but only
  because npm overrides a peer conflict with a warning: the deprecated
  `@astrojs/tailwind` 5.1.5 declares `astro ^3 || ^4 || ^5`. Every install
  would carry that ERESOLVE warning until the integration is replaced.
- Astro 7.3.1 pulls Vite 8.2.2 (Rolldown), esbuild 0.28.2 and sharp 0.35.4,
  and requires Node ≥ 22.12 (CI is on 22, fine).
- `astro build` passes untouched and emits the same five pages.
- **The rendered output is not unchanged**, which is the brief's bar for
  keeping a major: Astro 7 changed the default `compressHTML` from `true`
  to `'jsx'`, which strips whitespace inside inline elements — e.g. every
  nav link goes from `<a …> Home </a>` to `<a …>Home</a>`, and all five
  pages differ by 170–400 bytes each on that pattern. The stylesheet is
  also re-minified by Vite 8 (`::backdrop` added to the Tailwind reset,
  shorthand rewrites, different hashed filename). None of it is *wrong*,
  but it is a visual-diff exercise, not a drop-in.
- Nothing else from the v6/v7 upgrade guides applies: no content
  collections, no `Astro.glob`, no `<ViewTransitions />`, no markdown
  pages (the Sätteri pipeline change), no `src/fetch.ts`, no unclosed tags
  (the stricter Rust compiler accepted the site).

Estimated effort for the follow-up workstream: about half a day.
(1) Replace `@astrojs/tailwind` — either `@tailwindcss/vite` (Tailwind 4,
which means porting the theme tokens in `tailwind.config.mjs` to CSS
`@theme`) or keep Tailwind 3 through a plain `postcss.config.cjs` and an
explicit `@tailwind` import in `global.css`; (2) bump `astro` to `^7.3.1`
and `@astrojs/sitemap` to `^3.7.4`; (3) set `compressHTML: true` in
`astro.config.mjs` to keep today's whitespace behaviour, or accept `'jsx'`
and eyeball inline spacing; (4) visually diff the five pages and the
sitemap. That closes the 8 astro alerts and the esbuild one below.

### marketing: esbuild 0.27.7 (GHSA-g7r4-m6w7-qqqr, #15, low) — deferred with Astro

Astro 5 pins `esbuild ^0.27.3`; the fix is in 0.28.1. The advisory is an
arbitrary file read by the **dev server on Windows**; the site is built on
macOS/Linux and ships static files, so there is no exposure. A scoped
`overrides` entry could force 0.28 under Astro 5, but esbuild is invoked
on every build and 0.x minors are breaking by convention — not worth the
risk for a low, dev-only, Windows-only advisory that the Astro upgrade
closes anyway.

### backend: Spring Boot 3.5 / 4.x — deferred (line change; findings below)

What 3.4.13 + overrides cannot fix is everything whose fix lives only on a
newer Spring Boot line. Fourteen compile-scope advisories remain:

| Artifact (3.4.13 line) | Advisories | First fixed in |
|---|---|---|
| spring-security-web 6.4.13 | **CVE-2026-22732 CRITICAL**, CVE-2026-47838 | Spring Security 6.5.9 / 6.5.11 (Boot 3.5.12+ / 3.5.15+) |
| spring-security-core 6.4.13 | CVE-2026-22751 (moderate), CVE-2026-22746 (low) | 6.5.10 |
| spring-security-oauth2-jose 6.4.13 | CVE-2026-22748 (moderate) | 6.5.10 |
| spring-boot-starter-actuator 3.4.13 | CVE-2026-22731, CVE-2026-22733 (both HIGH) | Boot 3.5.12 / 4.0.4 |
| spring-boot 3.4.13 | CVE-2026-40973 (HIGH) | Boot 3.5.14 / 4.0.6 |
| spring-boot-autoconfigure 3.4.13 | CVE-2026-41001 (moderate) | Boot 3.5.15 / 4.0.7 |
| spring-data-commons 3.4.13 | CVE-2026-41695, CVE-2026-41716 (HIGH), CVE-2026-41711, CVE-2026-41721 | Spring Data 3.5.12 (Boot 3.5.12+) |
| spring-data-keyvalue 3.4.13 | CVE-2026-41719 (moderate) | 3.5.12 |

Two test-scope leftovers never ship: assertj-core 3.26.3 (CVE-2026-24400,
fixed 3.27.7) and commons-lang3 3.17.0 (CVE-2025-48924, fixed 3.18.0).

Support picture (spring.io / endoflife.date, 2026-09-03):

| Line | Latest | OSS support ends | Commercial |
|---|---|---|---|
| 3.4 (current) | 3.4.13 (2025-12-18) | **ended 2025-12-31** | 2026-12-31 |
| 3.5 | 3.5.16 (2026-06-25) | **ended 2026-06-30** | 2032-06-30 |
| 4.0 | 4.0.8 (2026-08-20) | 2026-12-31 | 2027-12-31 |
| 4.1 | 4.1.1 (2026-08-20) | 2027-07-31 | 2028-07-31 |

So the 3.4.13 + overrides state on this branch is the ceiling for the 3.4
line, and it will not move again. Two options for the next workstream:

1. **Spring Boot 3.5.16 as a stopgap.** Its BOM manages Spring Security
   6.5.11, Spring Data 3.5.13, Micrometer 1.15.12, Netty 4.1.137 and Spring
   Framework 6.2.19, which closes all fourteen; an OSV scan of the 3.5.16
   tree (from the probe below) shows 4 advisories left on 2 artifacts —
   three moderate jackson-databind ones, because 3.5.16 manages Jackson
   2.21.4 and the fixes are in 2.21.5 (pin `jackson-bom.version` to 2.21.6),
   plus the test-scope commons-lang3. It is a minor line, but it is already
   past OSS end-of-life, so it buys today's fixes and nothing after June
   2026. Things the test suite does **not** cover and that need a manual
   check: Quartz 2.3 → 2.5 against the Flyway-managed `qrtz_*` schema (prod
   uses the JDBC job store with `PostgreSQLDelegate`; tests run the
   in-memory store), Jackson 2.18 → 2.21 deserialization defaults (38 source
   files use `ObjectMapper`/`@JsonProperty`/`@JsonInclude`), Lettuce 6.4 →
   6.6 against the production Redis, the actuator heapdump endpoint
   defaulting to `access: NONE`, stricter boolean/profile-name parsing (no
   offending values found in the YAML), and the removed `taskExecutor` bean
   alias (no code refers to it). Flyway 10.20 → 11.7 is a major too, but the
   migration set runs against Testcontainers Postgres on every suite run, so
   that one *is* covered.
   **Probe result (scratch commit, reverted — nothing from it is on the
   branch):** with only the parent set to `3.5.16` and the Jackson 2.18 pin
   dropped, `mvn clean test` passed untouched — 289 tests, 0 failures, 08:40
   — with Quartz 2.5.2 initialising (in-memory store) and the Flyway 11.7.2
   migrations V1–V9 applying against Testcontainers Postgres; no new
   deprecation warnings beyond the pre-existing ones. So 3.5.16 *is* a
   drop-in by the test gate. It was deliberately not landed: the brief
   scoped the backend to a 3.4.x patch bump, the production-only paths
   listed above are outside the suite, and 3.5 is already out of OSS
   support. If the stopgap is wanted, it is a small change on top of this
   branch: parent `3.4.13 → 3.5.16`; `jackson-bom.version 2.18.10 → 2.21.6`;
   keep the `netty.version` pin (3.5.16 manages 4.1.135, which still lacks
   the 4.1.136/137 fixes), the `postgresql.version` pin (3.5.16: 42.7.11;
   CVE-2026-54291 needs 42.7.12) and the `log4j2.version` pin (3.5.16:
   2.24.3); the `spring-framework.version`, `micrometer.version` and
   `logback.version` pins become no-ops (3.5.16 manages 6.2.19, 1.15.12 and
   1.5.34, which already carry the fixes) and can be deleted; keep the
   Quartz exclusion. Expected OSV result: 0 compile-scope advisories, 1
   test-scope (commons-lang3).
2. **Spring Boot 4.1 as the durable target** (OSS-supported to 2027-07-31).
   Migration guide headlines that apply here: Spring Framework 7 / Jakarta
   EE 11; Jackson 3 (`tools.jackson` group — 38 backend source files import
   `com.fasterxml.jackson`, mostly `@JsonProperty`/`@JsonInclude`/`ObjectMapper`,
   so this is mechanical but wide); modular starters (Flyway and Quartz must
   be pulled in through their own starters — Flyway is currently declared as
   `flyway-core` + `flyway-database-postgresql`); Spring Security 7, Spring
   Data 4, Hibernate 7; `@MockBean` → `@MockitoBean` (not used here, so
   nothing to do). Java 21 already satisfies the baseline; Testcontainers is
   pinned at 2.0.5 independently of the BOM.

Recommendation: because the only runtime-facing CRITICAL on the whole
list (spring-security-web CVE-2026-22732) has no fix on the 3.4 line, land
3.5.16 as a short follow-up — it is a proven drop-in against the suite, so
the remaining work is the manual checks above on staging (JDBC job store,
Redis, heapdump access) — and plan 4.1 as its own brief for the durable
line, where the cost is mostly the Jackson 3 migration.

## Residual risk: what is still open, and whether it can reach a user

| Open item | Where it runs | Reachable? | Plan |
|---|---|---|---|
| 8 astro advisories (#13, #14, #18, #19, #20, #22, #24, #25): XSS via `define:vars`, spread attribute names, slot names, `transition:*` values, view-transition animation props; server-island replay; Host-header SSRF in prerendered error pages | Build time / SSR runtime | The site is `output: 'static'` with hard-coded content; `src/` uses none of `define:vars`, spread props, `transition:*`, named slots or server islands, and there is no adapter, so no SSR code path. Nothing user-controlled reaches the renderer | Astro 7 workstream |
| esbuild #15 (arbitrary file read, dev server, Windows) | Dev server only | Not exposed (macOS/Linux, static output) | closes with Astro 7 |
| Backend: 14 Spring Boot/Data/Security advisories incl. CVE-2026-22732 CRITICAL (spring-security-web) and two HIGH actuator issues | **Runtime, on the API** | Yes — this is the one genuinely runtime-facing residue on the whole list, and it is invisible to Dependabot. Mitigations already in place are the usual ones (actuator exposure limits, OAuth2 resource-server config), but the fix is a Boot line change | Spring Boot 4.1 (or 3.5.16 stopgap) — next brief |
| Backend test-scope: assertj, commons-lang3 | Test JVM only | No | pick up whenever the BOM moves |

Everything that was closed in the frontends was build-toolchain code
(Vite/esbuild/PostCSS/babel/browserslist/svgo/js-yaml/sharp) except React
Router, which is the one frontend runtime library that moved.

## Verification log

```
# baseline (main @ 2be06f8, worktree gd-deps)
app:       npm ci && npm run build            -> vite 5.4.21, green
marketing: npm ci && npm run build            -> 5 pages, green
backend:   docker compose up -d && mvn clean test -> 289 tests, 0 failures (16:42)

# after safe bumps            app 10 -> 4 audit findings; marketing 11 -> 4
# after sharp override        marketing dist byte-identical
# after Spring Boot 3.4.13    mvn clean test -> 289 tests, 0 failures (08:19)
# after Vite 7                tsc -b && vite build green; vite preview + browser: /login, /signup, console empty
# after React Router 7        tsc -b && vite build green; browser: /dashboard->/login w/ state, /verify-email?token=, links; audit 0
# after backend overrides     mvn clean test -> 289 tests, 0 failures (07:59); OSV 86 -> 16
# probe (scratch, reverted)   Spring Boot 3.5.16, no other change -> mvn clean test 289 tests, 0 failures (08:40); OSV 16 -> 4
# marketing final             npm ci && npm run build green; astro preview + browser: / and /pricing/ render, console empty
# secrets sweep over the branch diff: clean (grep exit 1)
```

`StabilitySdxlProviderTest` (the known ~5 s load flake) ran in 0.05–0.2 s
on all three backend runs and never needed isolation. Backend runs were
made with `JAVA_HOME` pointed at the Microsoft JDK 21 (Homebrew `mvn`
otherwise picks JDK 25, which breaks Mockito inline mocks).

## Maintenance notes — things to undo later

- `marketing/package.json` → `overrides.sharp`: delete when Astro is on a
  line that manages sharp ≥ 0.35.
- `backend/pom.xml` → the seven `*.version` properties and the
  `mchange-commons-java` exclusion: each one exists only because the 3.4 BOM
  is frozen. When the parent moves to 3.5/4.x, delete them and let the BOM
  manage the versions again (keep the exclusion — it is correct on any
  line that still ships Quartz 2.x with the c3p0 leftovers). The Micrometer
  1.15 pin is the only cross-minor one and is flagged in the pom.
- Dependabot counts on the Security tab will only reflect this branch
  after merge + push; the expected post-merge state was computed by
  evaluating every open alert's vulnerable range against the versions in
  the two lockfiles (17/17 app alerts close, 14/23 marketing alerts close).

## Follow-up 2026-10-21 — Spring Boot 3.5.16 landed

The stopgap recommended above, re-measured against OSV on the day it landed.
Advisories have been published since this report, so the recipe changed in
one place.

- **Parent 3.4.13 → 3.5.16.** This brings Spring Security 6.5.11, Spring Data
  3.5.13, Quartz 2.5.2, Flyway 11.7.2, Lettuce 6.6.0 and Hibernate 6.6.53.
- **Overrides kept:** `netty.version` 4.1.137.Final, `postgresql.version`
  42.7.13, `log4j2.version` 2.25.5, and the `mchange-commons-java`
  exclusion.
- **`jackson-bom.version` is 2.21.7, not 2.21.6.** Four HIGH jackson-core and
  jackson-databind advisories (CVE-2026-89425, -89407, -91776, -91777) appeared
  after this report and are fixed only in 2.18.11, 2.21.7 or 2.22.3. `main`
  on 2.18.10 had them too.
- **Overrides dropped:** `spring-framework.version`, `logback.version` and
  `micrometer.version`. 3.5.16 manages 6.2.19, 1.5.34 and 1.15.12, and none of
  them has an OSV finding.

| | Advisories | Outside test scope |
|---|---|---|
| `main` on 3.4.13 + overrides (2026-10-21) | 22 on 13 artifacts | 20 |
| 3.5.16 with no overrides at all | 30 on 11 artifacts | 29 |
| 3.5.16 + the overrides above | **3 on 2 artifacts** | 2 |

What is left:
- **spring-webflux 6.2.19 has CVE-2026-47892 and CVE-2026-47890, both
  CRITICAL.** OSV lists no 6.2.x fix, only Spring Framework 7.0.9, which means
  Spring Boot 4. Neither is reachable here:
  - CVE-2026-47892 needs WebFlux functional endpoints with header predicates.
    The backend has no `RouterFunction`.
  - CVE-2026-47890 needs SSE with view fragments. The backend has no template
    engine, and its SSE streams are plain `ServerSentEvent`s.
- **commons-lang3 3.17.0** is test scope only.

The manual checks listed under "Spring Boot 3.5.16 as a stopgap" have now
been done:

- **Quartz 2.3 → 2.5.** The `tables_postgres.sql` shipped in quartz 2.3.2 and
  2.5.2 is identical, so `V8__autorecharge_and_quartz.sql` stays valid. Upgrade
  path, simulated on the compose Postgres:
  1. `main` (3.4.13) booted in the default profile, which uses the JDBC store
     with `PostgreSQLDelegate` as prod does. It applied V1–V11 and persisted 2
     jobs with `WAITING` triggers.
  2. 3.5.16 booted against the same database. Flyway reported "up to date",
     validated all 11 migrations, and the Quartz 2.5.2 JDBC store resumed both
     triggers.
- **Configuration keys.** `spring-boot-properties-migrator` was on the
  runtime classpath for that boot and reported no renamed or removed keys.
- **Lettuce 6.6 against Redis 7.** No application code calls Redis directly.
  `/actuator/health` reported `UP`, then `DOWN` with Redis stopped, then `UP`
  again after a restart.
- **Heapdump `access: NONE` default.** No effect: only `health` and `info` are
  exposed.
- **Removed `taskExecutor` alias.** Not referenced anywhere.
- **Stricter boolean and profile parsing.** Every boolean is a plain
  `true`/`false`, and the only profile expression is `!test`.
- **Jackson 2.18 → 2.21.** No custom `ObjectMapper` or stream-read limits are
  configured, and the suite covers the DTOs.

`mvn clean verify`: 353 tests, 0 failures. 3.5 is itself past OSS support
(2026-06-30), so Spring Boot 4.1 remains the durable target. It is also the
only line that fixes the two spring-webflux advisories.

## Follow-up 2026-10-21 — Spring Boot 4.1.1

The durable target from above, measured the same way.

- **Parent 3.5.16 → 4.1.1.** This brings Spring Framework 7.0.9, Spring
  Security 7.1.1, Spring Data 2026.0.1, Hibernate 7.4.5, Flyway 12.4.0,
  Lettuce 7.5.2, Netty 4.2.17, Jackson 3.1 and JUnit 6. Quartz stays on
  2.5.2 and Testcontainers on 2.0.5, now BOM-managed, so the pom's own
  Testcontainers BOM import is gone.
- **OSV.** 4.1.1 with no overrides has 7 advisories, all on Jackson 3.1.5
  (CVE-2026-89425, -89407, -91776, -91777 and three more), fixed in 3.1.7.
  The pom pins `jackson-bom.version` to 3.1.8, the latest 3.1.x, which gives
  **0 advisories on 235 artifacts**, test scope included. The netty,
  postgresql and log4j2 pins are dropped because 4.1.1's own versions are
  clean. The two spring-webflux CRITICALs are closed by Framework 7.0.9. The
  `mchange-commons-java` exclusion stays.
- **Modular starters.** Boot 4 auto-configures Flyway only through
  `spring-boot-starter-flyway`. The OAuth2 starters are renamed to
  `spring-boot-starter-security-oauth2-{client,resource-server}`.
  `WebClient.Builder` now needs `spring-boot-starter-webclient`: without it
  every `@SpringBootTest` context failed to load. Tests use
  `spring-boot-starter-webflux-test`, and `@AutoConfigureWebTestClient` moved
  to `org.springframework.boot.webtestclient.autoconfigure`.
- **Jackson 2 → 3.** Imports move to `tools.jackson`; annotations keep
  `com.fasterxml.jackson.annotation`. `spring.jackson.use-jackson2-defaults`
  keeps the HTTP mapper on Jackson 2 behaviour, and the hand-built mappers use
  `JsonMapper.builderWithJackson2Defaults()`. A jshell side-by-side of
  2.21.7 against 3.1.8 showed one hazard: `asString()` throws on container
  nodes where Jackson 2's `asText()` returned `""`. The four bare call sites
  therefore use `asString("")`, so a malformed pricing-feed value still
  becomes an ANOMALY row rather than aborting the refresh.

Verification:
- `mvn clean verify`: 355 tests, 0 failures, rebased onto `main` @ 05f0ef9.
- **Upgrade path.** On the compose database that 3.4.13 and 3.5.16 had
  already used, Flyway 12 reported "up to date" and validated V1–V11, and the
  Quartz JDBC store resumed both `WAITING` triggers.
  `spring-boot-properties-migrator` reported no renamed or removed keys.
- **Lettuce 7.** `/actuator/health` reported `UP`, then `DOWN` with Redis
  stopped, then `UP` again.
- **Wire format.** `main` (3.5.16) and this branch were booted in turn and
  ten responses were captured from each: `/healthz`, `/v1/models` anonymous
  and authenticated, signup success, duplicate and invalid, and the 401, 404
  and 400 chat errors. After normalising timestamps, UUIDs, JWTs and the test
  email, 8 of 10 are byte-identical. The other two, both `/v1/models`, differ
  only in key order inside `godance: {...}`. That object is a `Map.of`, whose
  iteration order the JDK randomises on every JVM start, so it changes on any
  restart, on either version.

Behaviour changes that come with Boot 4 and Framework 7 and were kept:
- Problem details no longer include `"type":"about:blank"`. RFC 9457 §3.1.1
  defines an absent `type` as `about:blank`, and the SPA reads only `detail`
  and `title`.
- `/actuator/health` gains `"groups":["liveness","readiness"]`, and the
  `/actuator/health/liveness` and `/readiness` endpoints now exist (probes are
  on by default in Boot 4). The load balancer and deploy checks use
  `/healthz`, which is unchanged.
- The two 422 responses in `ProposalApprovalService` use
  `HttpStatus.UNPROCESSABLE_CONTENT`. `UNPROCESSABLE_ENTITY` is deprecated, so
  the problem-detail title now reads "Unprocessable Content".

## Appendix — re-running the backend scan

```bash
cd backend
export JAVA_HOME=/Users/ericgu/Library/Java/JavaVirtualMachines/ms-21.0.8/Contents/Home
mvn -B -q dependency:list -DoutputFile=/tmp/deps.txt -DoutputAbsoluteArtifactFilename=false
python3 - <<'PY'
import json, re, urllib.request
line = re.compile(r"^\s+([\w.\-]+):([\w.\-]+):[\w\-]+(?::[\w\-]+)?:([\w.\-]+):(\w+)")
deps = {}
for l in open("/tmp/deps.txt"):
    m = line.match(l)
    if m: deps[f"{m[1]}:{m[2]}"] = (m[3], m[4])
items = list(deps.items())
body = {"queries": [{"package": {"name": k, "ecosystem": "Maven"}, "version": v[0]} for k, v in items]}
req = urllib.request.Request("https://api.osv.dev/v1/querybatch", data=json.dumps(body).encode(),
                             headers={"Content-Type": "application/json"})
res = json.load(urllib.request.urlopen(req))["results"]
for (k, v), r in zip(items, res):
    ids = sorted({x["id"] for x in r.get("vulns", [])})
    if ids: print(f"{k}@{v[0]} [{v[1]}]: {', '.join(ids)}")
PY
```

Each id can be looked up at `https://api.osv.dev/v1/vulns/<id>` (the
`affected[].ranges[].events[].fixed` entries give the fixed versions per
line) or at `https://github.com/advisories/<GHSA id>`.
