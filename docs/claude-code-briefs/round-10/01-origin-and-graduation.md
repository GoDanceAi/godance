# Brief 01 — Origin and graduation

**Branch:** `feature/origin-graduation` (worktree `../gd-origin`) off `main` with rounds 7–9 merged
**Reserved migration:** V28
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Record where each job and each sign-up came from, and show the operator how
many people go from the gallery to the Studio to the API.

## Why now

Round 8 left this open: "Not covered by any brief yet: measuring how many
first runs start from a recipe."

Godance is built for people who try things in the Studio first and later
run them through the API. Whether that happens is the measure that matters.
Nothing records it today: a job does not know whether it came from the
Studio or from an API key, and a sign-up does not know what brought it. The
rest of rounds 10–14 cannot be judged without this.

## Read first

1. This brief and `00-coordination.md` (the origin contract is binding; V28 is yours)
2. `backend/CLAUDE.md`
3. Job creation:
   - `web/ImageController.java` and `web/VideoController.java`, including
     how `api_key_id` is read from the principal
   - `service/ImageGenerationService.executeAsync`,
     `service/VideoGenerationService.submit`
   - `domain/GenerationJob.java`, `web/dto/ImageDtos.java` (`JobView`),
     `web/dto/VideoDtos.java`
4. `security/ApiKeyAuthFilter.java` (the API-key principal),
   `config/SecurityConfig.java`
5. `web/AdminConsoleController.java` and `service/admin/AdminConsoleService.java`
   (`usageSummary` is the pattern for a dated admin report), `security/AdminRoles`
6. `domain/CreditSource.java`, `repository/CreditLedgerRepository.java`
7. Round 9 brief 01 and the code it produced: how the app captures a
   referral code, keeps it in `localStorage`, and claims it once after
   sign-in, including after a social sign-in
8. App: `app/src/lib/auth.tsx`, `app/src/lib/api.ts`, the Studio's submit
   (round 7 brief 02 and round 8 brief 03),
   `app/src/pages/admin/OverviewPage.tsx`, `app/e2e/mock-api.ts`

## Scope — backend

### Data (V28)

- On `generation_jobs`:
  - `channel`: text, nullable. Existing rows stay null.
  - `gallery_recipe`: text, nullable.
  - An index that serves "this user's first job of each channel".
- On `users`, `first_touch` (jsonb, nullable) and `first_touch_at`.

### Recording a job's origin

- **Channel.** The server sets it: `API` when the principal carries
  `api_key_id`, and `STUDIO` otherwise. A request cannot set it.
- **Gallery recipe.** `gallery_recipe` comes from the request, as in the
  contract. It is checked for format only; the backend does not know which
  recipes exist.
- **How it reaches job creation.** Introduce one record that carries a
  job's origin into job creation (for example `JobOrigin(channel,
  galleryRecipe)`). Brief 03 will add its recipe fields to the same record,
  so keep it a single parameter.
- `JobView` gains `channel` and `galleryRecipe`.
- The completion webhook body does not change.
- **Purge leaves both fields.** They are not personal data and the report
  needs them.

### First touch: `POST /v1/attribution/first-touch`

- Session only. An API key cannot call it, which `SecurityConfig` already
  ensures by default; add a test that pins it.
- Validate as in the contract. Write once, inside the window
  `godance.attribution.first-touch-window-hours` (default 168), counted from
  account creation.
- Rate-limit per account with the existing limiter.
- No audit event: this is not a security action.

### Report: `GET /v1/admin/funnel`

- `@AdminRoles({SUPPORT, FINANCE, ADMIN})`. `from` and `to` are dates, with
  the same defaults and checks as `usageSummary`.
- **Computed in SQL,** not by loading users into memory. Each "first" is the
  earliest qualifying row per user.
- **Counts only,** exactly as in the contract. Leave out any bucket with
  fewer than 5 users, so a single person cannot be picked out of a small
  bucket.
- **`gallery_recipes`:** the 50 slugs with the most distinct users in the
  range.
- `median_days_to_api_job` is null when no one in the cohort has an API job.

### Document it (last commit)

The jobs section of `marketing/src/pages/docs/index.astro`: the
`gallery_recipe` request field, and `channel` and `galleryRecipe` on a job.

## Scope — app

- **Capture.** On the first load of any app page, read these from the URL
  and store them in `localStorage` for 30 days:
  - the `utm_*` parameters
  - `example` on a Studio route, as `gallery_recipe`
  - the path
  - the referrer's host, when it is not the app's own

  Write only when nothing is stored yet, so the first touch wins.
- **Claim.** After sign-in, call the first-touch endpoint once and clear the
  stored value whatever the answer.
  - Use the same after-sign-in hook as round 9's referral claim. If that
    hook is specific to referrals, generalize it rather than adding a second
    one.
  - It must work after a password sign-up, a social sign-in and a sign-in
    in another tab, as the referral claim does.
- **The Studio.** When the form was loaded from `?example=<slug>`, send
  `gallery_recipe` with that slug. Keep sending it while the prompt is still
  the recipe's. Stop once the user edits the prompt, or switches model to
  one the recipe has no run on.
- **Admin Overview page.** A funnel section with:
  - a date range
  - the cohort table
  - the source table
  - the top recipes

  Show percentages beside the counts.

## Hard rules

- Edits only in `backend/src/**`, migration V28, `application.yml`,
  `app/src/**`, `app/e2e/**`, and the docs page in the last commit.
- Additions only on the wire. Nothing here changes what a job costs.
- `channel` is never taken from the client.
- The report never returns a user id, email or name.
- No third-party analytics, tracker or script. This is first-party data in
  the existing database.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baselines. Read both generation paths and state, with file and line,
where a job row is created and where the principal's `api_key_id` is known.
Write the failing test first: a job created with an API key reads back
`channel: "API"`, and one created with a session reads back `"STUDIO"`. Then
post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green.
- **Backend tests cover:**
  - `channel` for a session and for an API key, on images and on video
  - `gallery_recipe` recorded, and a malformed one answering 400 with no
    ledger row
  - first touch written once
    - a second call changing nothing
    - a call after the window changing nothing
    - an API key refused
  - the funnel:
    - a cohort with one user at each stage
    - a paid top-up counted while promotional credit is not
    - a bucket under 5 users left out
    - the median with no API jobs
- **App tests cover:**
  - capture and claim after a password sign-up, a social sign-in and a
    sign-in in another tab
  - first touch not overwritten by a later visit
  - the Studio sending `gallery_recipe`, and no longer sending it after an
    edit
  - the admin funnel rendering from mocks
- A curl transcript of the new routes in the final summary.
- All work committed on `feature/origin-graduation`; `main` untouched.

## Out of scope (do not start)

- Events in the browser beyond the first touch.
- Any third-party analytics.
- Per-user funnels in the admin console.
- Changing the gallery pages (brief 02 adds `gallery_recipe` to their API
  examples).
- Batch counts (round 12 adds them).
