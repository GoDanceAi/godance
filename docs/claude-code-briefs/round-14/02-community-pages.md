# Brief 02 — Community pages

**Branch:** `feature/community-pages` (worktree `../gd-community`) off `main` with round 13 merged; rebase onto brief 01 before merging
**Reserved migration:** none. This brief has no backend changes.
**Baseline:** `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; `npm ci && npm run lint && npm run build && npm test` green in `marketing/`

## Mission (one line)

Give published recipes a public home on the marketing site that search
engines and agents can read. In the app, give users their profile, a
Publish flow, a feed from the people they follow, and a moderation queue
for staff.

## Why now

Brief 01 makes published recipes exist. Without pages they cannot be found,
run or followed.

The public pages belong on the marketing site, as the gallery's do: static,
fast and indexable. They are rebuilt from the public API on each deploy and
on a daily schedule, as `marketing/src/lib/catalog.ts` already reads the
API at build time.

## Read first

1. This brief and `00-coordination.md` (the publishing contract is binding;
   develop against mocks until brief 01 merges)
2. Marketing: `marketing/src/lib/catalog.ts`, the gallery pages, components
   and `gallery-config.ts`, `Layout.astro`, and `.github/workflows/deploy-frontends.yml`
3. App:
   - the recipe pages (round 10)
   - the Studio's recipe hand-off (rounds 8, 10, 11 and 13)
   - Settings
   - the admin layout and pages
   - `app/src/lib/api.ts`, `app/e2e/mock-api.ts`
4. `docs/gallery-content-standard.md` (the community section from brief 01)

## Scope — marketing

- **`marketing/src/lib/community.ts`.** At build time, reads
  `/v1/public/recipes` (all pages) and `/v1/public/profiles/{handle}`.
  - It fails soft. When the API is unreachable, the build keeps going with
    no community pages and logs a warning. The rest of the site must never
    break because of it.
- **Switch.** The `community-enabled` flag sits in a config module, as the
  gallery's switch does. When it is off, no community output is built.
- **Pages:**
  - `/community`: newest recipes, filterable by tag.
  - `/community/<public_id>`: the recipe page.
    - examples and template with variables
    - model and params
    - licence, author with a link to the profile
    - `made_from` with a link
    - `runs` and `runners`
    - "Run this" into the app
    - "Report", which links to the app's report form
  - `/u/<handle>`: the author's profile and published recipes, with follower
    count.
- **Search and structured data:** canonical URLs, Open Graph, sitemap.
  Structured data names the author as the recipe's creator and Godance as
  the publisher.
- **Schedule.** Add a daily scheduled build to `deploy-frontends.yml` so new
  approvals appear within a day, as well as on each deploy.

## Scope — app

- **Profile** in Settings: handle, display name, link, with the rules shown.
  It is only for accounts that may publish; others see why not.
- **Publish** on a recipe page:
  - choose the version, one to four example jobs from its attempts, and the
    licence
  - show what will be public and what will not
  - submit
  - then a status line: pending, public with a link, rejected or removed
    with the reason
  - unpublish
- **Run a published recipe.** `/create/image?published=<id>` (and video)
  loads it, like a gallery recipe, and submits with
  `recipe.published_id`. Add it to the pre-fill order after `?recipe=`.
- **Following:** a Follow button on profile and recipe links that open in
  the app, and a `/following` feed page.
- **Report** form: `/report?kind=…&id=…`, signed-in only, with a reason.
- **Admin moderation** page under `/admin`:
  - the queue with each item's template, examples and author history
  - approve, reject with reason, remove
  - open reports

  Only for roles brief 01 allows.

## Hard rules

- Edits only in `marketing/src/pages/community/**`,
  `marketing/src/pages/u/**`, `marketing/src/lib/community.ts`,
  `marketing/src/components/community/**`, the deploy workflow's schedule,
  `app/src/**` and `app/e2e/**`.
- **The marketing build never fails because the API is down.**
- **Nothing private reaches a public page.** Use only the public routes.
- No client framework or new runtime dependency on the marketing site.
- Use existing theme tokens and classes, in light and dark.

## Opening move

Run the baselines. Read `catalog.ts`, and how the build already tolerates an
unreachable API. Write the failing test: a build with the public API mocked
to return two recipes produces two community pages and a profile page.
Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- Marketing lint, build and test green, including:
  - a build with the API unreachable
  - a build with the switch off producing no community output
- App lint, build and e2e green on desktop and mobile. Specs cover:
  - profile rules
  - publish with each status
  - unpublish
  - running a published recipe
  - follow and feed
  - reporting
  - the moderation page
- Screenshots of a community recipe page, a profile, the Publish dialog and
  the moderation queue, in light and dark.
- All work committed on `feature/community-pages`; `main` untouched.

## Out of scope (do not start)

- Likes and comments.
- Notifications.
- Search beyond tags.
- Embeds.
- Server-side rendering in the app.
