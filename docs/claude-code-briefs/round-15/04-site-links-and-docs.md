# Brief 04 — Site links and docs

**Branch:** `feature/site-links` (worktree `../gd-site-links`) off `main`
**Reserved migration:** none. This brief has no backend or app changes.
**Baseline:** `npm ci && npm run lint && npm run build && npm test` green in `marketing/` on parent

## Mission (one line)

Link the site's built sections from where visitors look, let the operator
switch the gallery and community on from repository variables, and
document the APIs the docs leave out.

## Why now

- **The community pages** (`/community`, `/u/<handle>`, round 14) are linked
  from nowhere: not the header, the footer, the home page or the gallery.
- **The comparison index** (`/gallery/compare`, round 13) is reached only
  through model pages.
- **The deploy cannot switch anything on.** The marketing build in
  `.github/workflows/deploy-frontends.yml` sets only `SITE_URL` and
  `PUBLIC_API_BASE`. Turning on the gallery or community therefore needs a
  code change, even though `gallery-config.ts` and `lib/community.ts`
  already read environment overrides.
- **The docs are incomplete:**
  - The docs page has no routing policies section (`/v1/routing/policies`,
    calling a policy as a model, `X-Godance-Route`, batch fallbacks) and
    nothing on the public community API.
  - `llms-full.txt` has nothing on bring your own key or the public
    community API.

## Read first

1. This brief and `00-coordination.md` ("links appear only when usable" is
   binding)
2. `marketing/src/components/Nav.astro` (Gallery appears only while the
   gallery is on), `components/Footer.astro`, `src/pages/index.astro`
3. `marketing/src/lib/gallery-config.ts` (`GALLERY_ENV`),
   `src/lib/gallery-recipes.ts`, `src/lib/gallery-compare.ts`,
   `src/lib/community.ts` (`COMMUNITY_ENV`, how the build reads public
   recipes and fails soft)
4. `src/pages/gallery/[...index].astro`, `src/pages/gallery/compare/**`,
   `src/pages/community/**`, `src/pages/u/**`
5. `.github/workflows/deploy-frontends.yml` (the marketing build step),
   `marketing/README.md`
6. `src/pages/docs/index.astro`, `public/llms.txt`, `public/llms-full.txt`,
   `scripts/llms/**` (the check that routes named in `llms-full.txt` exist in
   the OpenAPI file)
7. Backend, read only, for the docs:
   - `web/RoutingPolicyController.java`, `routing/RoutingPolicyDocuments.java`
   - `web/ProviderCredentialController.java`
   - `web/PublicCommunityController.java`
   - `backend/src/main/resources/static/openapi.yaml`

## Scope

### 1. Links

- **Header.** A "Community" entry, after Gallery, only when the community is
  on and the build found at least one public recipe.
- **Footer.** Links to Gallery, Comparisons, Community, Docs and Status, each
  only when that section exists in the build.
- **Gallery page.** A link to `/gallery/compare` when at least one pair is
  published, and to `/community` when it is on.
- **Home page.** A Community link beside the gallery strip when community is
  on. Add no new strip.

### 2. Switches from repository variables

- **Deploy env.** In the marketing build step of `deploy-frontends.yml`, set:
  - `GALLERY_MEDIA_BASE_URL`, `GALLERY_REMOVAL_CONTACT`,
    `GALLERY_OUTPUT_LICENCE`, `GALLERY_RUN_THIS`, `GALLERY_COMPARE_MIN` and
    `COMMUNITY_ENABLED`, from `${{ vars.<NAME> }}`
  - an unset variable must leave the committed default, so an empty string
    must mean "not set". Check how each override reads empty, and fix the
    reader in `marketing/src/lib` if it does not.
- **No other change to the workflow.** No secrets are needed: these are
  public values.
- **Docs.** Document the variables in `marketing/README.md`: what each turns
  on, and that the next deploy picks them up.

### 3. Docs

- **Docs page, "Routing policies" section:**
  - create, read, update and delete
  - calling a policy by its id as a chat model
  - `X-Godance-Route`
  - fallbacks for batches (`fallbacks: true`)
  - limits, and the switch that turns the routes off
  - errors

  Each with an example. Follow the page's existing section style.
- **Docs page, "Community" section:** the public read routes
  (`/v1/public/recipes`, `/v1/public/profiles/{handle}`, examples), running
  a published recipe (`recipe.published_id`), and that publishing is
  moderated.
- **`llms-full.txt`:** add bring your own key and the public community API in
  its existing format, with one curl and one Python example each. Add a line
  to `llms.txt` for each.
- **Checks.** The existing `llms-full.txt` vs OpenAPI check must still pass.
  If a route the docs need is missing from `openapi.yaml`, list it in the
  summary; do not edit the backend.

## Hard rules

- Edits only in the marketing and CI files reserved for 04 in
  `00-coordination.md`, plus `marketing/README.md` and readers in
  `marketing/src/lib/**` if item 2 needs them.
- **With every variable unset, the built site is byte-identical to the
  parent's**, apart from the new docs text. Prove it with a build diff.
- No secret in the workflow. Variables only.
- Nothing documented that is not live on `main`.
- No client framework and no new runtime dependency.

## Opening move

Run the baseline. Build with the gallery and community off, and with both on
using fixtures, and note which links appear. Write the failing test: a
build with community on and one public recipe shows a Community link in the
header and footer; with it off, neither appears. Then post a 3–5 bullet plan
before writing code.

## Acceptance criteria

- `npm ci && npm run lint && npm run build && npm test` green.
- **Tests cover:**
  - each link, present and absent
  - an empty repository variable leaving the default
  - the `llms` check
- A build diff showing no change with every variable unset, apart from the
  docs.
- Screenshots of the header and footer with everything on, in light and
  dark.
- All work committed on `feature/site-links`; `main` untouched.

## Out of scope (do not start)

- Turning any switch on (operator).
- Publishing recipes.
- A docs site redesign.
- Backend changes, including `openapi.yaml`.
