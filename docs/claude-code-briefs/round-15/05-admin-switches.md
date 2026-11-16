# Brief 05 — Admin switches panel

**Branch:** `feature/admin-switches` (worktree `../gd-switches`) off `main`
**Reserved migration:** none
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Show staff, read-only, which feature switches are on in this deployment and
what each one hides while off, so "why can't I see X" has an answer in the
console.

## Why now

Most of what rounds 7–14 built ships off and appears only when its switch is
on. From the app, nobody can tell whether a missing link is a switch or a
bug. The test team needs this too: its plan sets switches on staging, and
must be able to confirm them.

## Read first

1. This brief and `00-coordination.md` (the `GET /v1/admin/features`
   contract is binding)
2. `backend/CLAUDE.md`
3. The properties classes behind each switch:
   - the referral and signup-credit settings (`PromoProperties` or as named
     on `main`)
   - `ImagesProperties`
   - the inputs, community, BYOK and routing settings in `config/`
   - `godance.region`
4. `web/AdminConsoleController.java`, `security/AdminRoles`, how other admin
   routes are declared and tested
5. App:
   - `app/src/pages/admin/AdminLayout.tsx`, `OverviewPage.tsx`
   - `app/src/lib/gallery.ts` (`GALLERY_ORIGIN`, `loadGalleryIndex`)
   - `app/src/lib/api.ts`, `app/e2e/mock-api.ts`, `app/e2e/admin-roles.spec.ts`

## Scope — backend

- **The route.** `GET /v1/admin/features`, exactly as in the contract, with
  `@AdminRoles({SUPPORT, FINANCE, ADMIN})`. It is built from the bound
  properties at request time.
- **Never secrets.** No key, secret, credential, private URL or allow-list
  member is ever included. `check_model` is a model id, which is public.
- **Tests:**
  - each role's access
  - a USER and an API key refused
  - the values reflect the properties
  - a serialisation test that the response has exactly the contract's keys

## Scope — app

- **Where.** A "Switches" tab in the admin console, at `/admin/switches`,
  for the same roles.
- **One row per switch:**
  - its state (on or off, and the value where it is a number or list)
  - the environment variable that sets it
  - what it shows in the app and on the site when on, for example "Invite in
    the header; the Billing referral line"
- **Marketing site.** One row for the site's gallery, read from
  `loadGalleryIndex()`: on when the index has recipes. Say that the site's
  community switch cannot be read from here, and how to check it: a build
  variable, as in round 15 brief 04.
- **Region.** Show the region at the top.

## Hard rules

- Edits only in the new backend controller and service, its tests,
  `AdminLayout.tsx`, the new `SwitchesPage.tsx`, one route in `App.tsx`,
  `app/src/lib/**` and `app/e2e/**`.
- **Read-only.** Nothing in the console can change a switch.
- No migration, and no change to any switch's default.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baselines. List every switch rounds 7–14 added, with its property
and environment variable, from `application.yml`. Write the failing test: a
SUPPORT session gets `GET /v1/admin/features` with the contract's keys, and
a USER gets 403. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green.
- Backend and e2e tests as listed. The panel shows correctly with everything
  off, and with a mock where everything is on.
- A screenshot of the panel in light and dark.
- All work committed on `feature/admin-switches`; `main` untouched.

## Out of scope (do not start)

- Changing switches from the console.
- Per-user feature flags.
- Reading the marketing build's community switch.
