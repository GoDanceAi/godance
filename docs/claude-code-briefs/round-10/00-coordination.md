# Round 10 — Coordination

**Drafted:** 2026-11-13 · **Parent:** `main` with rounds 7, 8 and 9 merged
**Workstreams:** 01 Origin and graduation (backend, app) · 02 Recipe
templates (marketing) · 03 My recipes and attempts (backend, app) ·
04 Image responses in OpenAI's shape (backend, app)

Context: Godance is built for aspiring builders. They find a prompt, adapt
it, try it dozens of times, keep what works, and then run it at scale
through the API. The reasoning is in
`docs/research/2026-11-prompt-gallery-competitors.md`.

This round covers the adapt and iterate steps, and measures whether people
move on to the API:
- **01** records where each job and each sign-up came from, and reports how
  many Studio users go on to generate with an API key.
- **02** keeps a recipe's variables, so a gallery recipe can be filled in.
- **03** lets a user keep their own recipes, with versions, and compare
  attempts with their costs.
- **04** makes standard OpenAI SDK image code work unchanged, which is the
  first wall that code written by an AI assistant hits.

**Prerequisites.** All of rounds 7, 8 and 9 on `main`:
- round 7 brief 01: job outputs and estimates
- round 7 brief 02: the Studio and History
- round 8: the gallery, its JSON endpoints and the "Run this" hand-off
- round 9: referral capture, and saved generations

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-origin     -b feature/origin-graduation main
git worktree add ../gd-templates  -b feature/recipe-templates main
git worktree add ../gd-recipes    -b feature/my-recipes main
git worktree add ../gd-image-sync -b feature/openai-image-responses main
```

## Reserved resources

| Resource | 01 origin | 02 templates | 03 my recipes | 04 image responses |
|---|---|---|---|---|
| Flyway migrations | **V28** | none | **V29** | **V30** |
| Backend | new `service/Attribution*`, `service/admin/Funnel*`, job creation in both generation services, `JobView`, `AdminConsoleController` (one route) | none | new `service/Recipe*`, `web/RecipeController`, `domain/Recipe*`, job creation (after 01), `JobView`, `JobController` (one filter) | `web/ImageController`, `ApiKeyController`, `ApiKeyService`, `ApiKeyDtos`, `domain/ApiKey` |
| App | first-touch capture, admin Overview page, the Studio's submit (one field) | none | Studio template fields, new `/recipes` pages, `/compare` page, History select, navigation | API keys page |
| Marketing | `docs/index.astro` (last commit only) | `src/lib/gallery-schema.js`, `scripts/gallery-schema/**`, `scripts/gallery/**`, `src/pages/gallery/**`, `src/components/gallery/**`, `fixtures/gallery/**` | `docs/index.astro`, `public/llms.txt` (last commit only) | `docs/index.astro`, `public/llms.txt` (last commit only) |
| Docs | none | `docs/gallery-content-standard.md` (one section) | none | `docs/openai-dropin-verification.md` |
| Config | `godance.attribution.*` | none | `godance.recipes.*` | `godance.images.*` |

Check that V28–V30 are still free before using them.

**The schema module is open again for 02 only.** Round 8 froze
`marketing/src/lib/gallery-schema.js` after its brief 00. This round, 02 may
change it, additively. Nobody else edits it.

## Shared contract

Additions only. Nothing that exists today changes shape. Request fields are
snake_case, as the API's other extensions are (`webhook_url`). New fields
on `JobView` are camelCase, as `JobView`'s are (`mediaExpiresAt`).

### Origin (01)

- `POST /v1/images/generations` and `POST /v1/videos/generations` accept an
  optional `gallery_recipe`: a recipe slug (lowercase letters, digits and
  hyphens, at most 80 characters). Any other value answers 400 with
  `code: "invalid_gallery_recipe"`.
- `JobView` gains:
  - `channel`: `"STUDIO"` for a signed-in session, `"API"` for an API key,
    and null for jobs made before V28. The server decides; a request cannot
    set it.
  - `galleryRecipe`: the slug, or null.

```json
POST /v1/attribution/first-touch        (session only)
{ "utm_source": "x", "utm_medium": "social", "utm_campaign": "launch",
  "gallery_recipe": "paper-lantern-street-at-dusk",
  "landing_path": "/create/image", "referrer_host": "x.com" }
→ 204
```

- **Fields.** All fields are optional, but at least one must be present.
  Each is at most 100 printable characters. `gallery_recipe` follows the
  slug rule.
- **Write-once.** The first accepted call is kept. A later call, or one from
  an account older than the window, still answers 204 and changes nothing.

```json
GET /v1/admin/funnel?from=2026-11-01&to=2026-11-30     (SUPPORT, FINANCE, ADMIN)
{ "cohorts": [
    { "week": "2026-W47", "signed_up": 120, "first_studio_job": 64,
      "first_paid_top_up": 18, "first_api_job": 7, "median_days_to_api_job": 3.5 } ],
  "by_source": [
    { "source": "gallery", "signed_up": 40, "first_studio_job": 31,
      "first_paid_top_up": 9, "first_api_job": 4 } ],
  "gallery_recipes": [
    { "slug": "paper-lantern-street-at-dusk", "users": 31, "jobs": 88, "users_with_api_job": 4 } ] }
```

- **Cohorts** are by UTC ISO week of sign-up.
- **Source buckets:**
  - `gallery`: the first touch has a `gallery_recipe`
  - `referral`: a round 9 referral exists
  - `utm:<utm_source>`: the ten largest; the rest are `utm:other`
  - `none`
- **"Paid top-up"** means a positive `STRIPE`, `NOWPAYMENTS` or
  `BINANCE_PAY` ledger entry. Promotional and referral credit does not count.
- **"API job"** means a job whose `channel` is `API`.
- The report gives counts only, never who.

### Recipe templates (02 writes them; 03 and round 12 render them)

- **Syntax.** A variable is written `{{name}}`. A name is 1–40 ASCII
  letters, digits, spaces, hyphens or underscores, starting and ending with
  a letter or digit.
- **Rendering.**
  - Each `{{name}}` is replaced by the value given, or by the variable's
    default when no value is given for it. An empty string is a value.
  - It is one pass: a value that contains `{{…}}` is inserted as it is.
  - Every `{{` in a template must begin a declared variable. A stray `}}` is
    allowed, so JSON-shaped prompts work.
  - Every declared variable appears in the template at least once.
- **Gallery.** `GET /gallery/<slug>.json` gains, inside `prompt`, an
  optional `template` and `variables`:

```json
"prompt": { "en": "…", "zh": "…",
  "template": { "en": "A {{lantern color}} paper lantern…", "zh": "一盏{{lantern color}}的纸灯笼…" },
  "variables": [ { "name": "lantern color", "default": "warm red", "zh_default": "暖红色" } ] }
```

Rendering `template.en` with the defaults gives exactly `prompt.en`.
Rendering `template.zh` gives exactly `prompt.zh`, using `zh_default` where
present and `default` otherwise.

### My recipes (03)

These routes take a signed-in session or an API key, like the job routes.

```
POST   /v1/recipes                        → 201 Recipe
GET    /v1/recipes?limit=&before=         → [Recipe], newest first (bare array, as /v1/jobs)
GET    /v1/recipes/{id}                   → Recipe
PATCH  /v1/recipes/{id}  {"title": "…"}   → Recipe
DELETE /v1/recipes/{id}                   → 204
GET    /v1/recipes/{id}/versions          → [RecipeVersion], newest first
POST   /v1/recipes/{id}/versions          → 201 RecipeVersion, or 200 with the latest when nothing changed
```

```json
{ "id": "…", "title": "Mug on a kitchen shelf", "modality": "IMAGE",
  "source_gallery_recipe": "paper-lantern-street-at-dusk",
  "created_at": "…", "updated_at": "…",
  "latest_version": {
    "id": "…", "number": 3, "template": "A {{color}} mug on a shelf…",
    "variables": [ { "name": "color", "default": "red" } ],
    "model": "gpt-image-2", "params": { "size": "1024x1024", "n": 1 },
    "created_at": "…" },
  "attempts": 12, "spent_cents": 74 }
```

**Running a recipe.** Generation requests accept, in place of `prompt`:

```json
"recipe": { "version_id": "…", "variables": { "color": "blue" } }
```

- Exactly one of `prompt` and `recipe` is given; both answers 400 with
  `prompt_and_recipe`.
- The server renders the template, and the result is the job's prompt.
- `model` and the other request fields are sent as usual. The version's
  model and params are what the Studio fills in, not a constraint.
- **Errors:**
  - `recipe_not_found` (404; another user's recipe answers the same)
  - `recipe_modality_mismatch` (400)
  - `recipe_variable_unknown` (400)

**What jobs show.**
- `JobView` gains `recipe`: `{ "id": "…", "version": 3, "variables": {…} }`,
  or null. After purge, `variables` is null.
- `GET /v1/jobs` accepts `recipe=<id>`.

### Image responses in OpenAI's shape (04)

- **The setting on each key.** API keys gain `image_response`: `"async"` or
  `"sync"`.
  - `POST /v1/keys` accepts it. When it is omitted, the key gets the value
    of `godance.images.new-key-response`, which defaults to `async`.
  - `PATCH /v1/keys/{id}` accepts `{ "image_response": "sync" }` and
    returns the key's summary. It is session only, like the other key
    routes.
  - `ApiKeySummary` gains `image_response`.
- **When a request waits.** All of these must hold: the key is `sync`, the
  model requires async, the body does not set `async: true`, and the request
  has no `Prefer: respond-async` header.
- **What it answers.** It waits up to `godance.images.sync-wait`.
  - **Done:** `200` with today's synchronous body: OpenAI's `created` and
    `data`, plus the existing `job_id` and `cost_cents` extensions.
  - **Failed:** problem+json `502` with `code: "generation_failed"`. The
    charge is returned, as today.
  - **Still running at the limit:** `202` with today's job body and
    `Retry-After: 5`.
- **Sessions are unaffected.** The Studio sends `async: true`.

## Shared-file conflict watch

- **Job creation:** `service/ImageGenerationService.java`,
  `service/VideoGenerationService.java`, `domain/GenerationJob.java`,
  `web/dto/ImageDtos.java`, `web/dto/VideoDtos.java` (01, 03, and 04 on the
  image response).
  - 01 introduces one record carrying a job's origin into job creation.
  - 03 adds its fields to that record and does not add a second parameter.
  - 03 rebases onto 01.
- `web/JobController.java`, `repository/GenerationJobRepository.java` — 01
  and 03 each add one filter or field.
- `config/SecurityConfig.java` — 03 adds the recipe routes to the API-key
  rule. 01's routes are session or admin only and need no new rule.
- `service/AuditLogService.java` — 03 and 04 add event constants.
- `backend/src/main/resources/application.yml` — 01, 03 and 04 each add one
  block.
- `app/src/lib/api.ts`, `app/e2e/mock-api.ts` — 01, 03 and 04.
- **The Studio's submit:** 01 adds `gallery_recipe`; 03 adds `recipe`. 03
  rebases onto 01.
- `marketing/src/pages/docs/index.astro` — 01, 03 and 04, last commit
  only. `marketing/public/llms.txt` — 03 and 04, last commit only.

## Hard rules (all sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- Run the full suite on the parent commit and record the count first.
- Frontends: `npm run lint`, `npm run build` and `npm run test:e2e` stay
  green in `app/`. `npm ci && npm run lint && npm run build && npm test`
  stays green in `marketing/`.
- Billing and ledger changes carry a test in the same change. Nothing in
  this round changes what a job costs.
- Money in integer cents.
- The regional provider allowlist is not touched this round.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

1. **01** first. It is small, and 03 builds on its job-origin record.
2. **02** at any time. It is marketing only, and 03 reads its JSON through
   mocks.
3. **03** after 01, rebased.
4. **04** at any time. It ships with every key on `async`, so it changes
   nothing until the operator acts.

## Operator decisions

- **The default for new keys** (`godance.images.new-key-response`).
  Suggested: `sync`, once 04 is live and the drop-in check in
  `docs/openai-dropin-verification.md` passes for images. Existing keys stay
  `async` until their owner switches them.
- **How long a request waits** (`godance.images.sync-wait`). Suggested: 90
  seconds.
  - Caddy allows 5 minutes.
  - If `api.godance.ai` is proxied through Cloudflare, a response must start
    within about 100 seconds, so stay under that or make the record DNS-only.
- **The first-touch window** (`godance.attribution.first-touch-window-hours`).
  Suggested: 168.
- **The privacy policy** must say that the app records where a sign-up came
  from: campaign tags, the gallery recipe, the landing page and the
  referring site.
- **Limits for recipes.** Suggested: 500 per account and 200 versions per
  recipe.
