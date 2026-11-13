# Brief 03 — My recipes and attempts

**Branch:** `feature/my-recipes` (worktree `../gd-recipes`) off `main` with rounds 7–9 merged; rebase onto brief 01 before merging
**Reserved migration:** V29
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Let a user keep a setup that works as their own recipe, with versions and
variables, and compare their attempts side by side with what each one cost.

## Why now

Aspiring builders refine a prompt dozens of times before they trust it.
Today:
- each attempt is a separate History entry
- nothing groups attempts or shows what changed between them
- nothing adds up what the refining cost
- the setup that finally works lives only in the last job's parameters

A recipe holds it: template, variables, model and parameters. It is also
the thing round 12 will run over a sheet of values, so the variables need
to be real now.

## Read first

1. This brief and `00-coordination.md` (the recipe contract and the template
   rules are binding; V29 is yours)
2. `backend/CLAUDE.md`
3. Brief 01 of this round, which will land first: the origin record that
   job creation takes. Add your fields to it.
4. Job path:
   - `web/ImageController.java`, `web/VideoController.java`,
     `web/JobController.java`
   - `web/dto/ImageDtos.java`, `web/dto/VideoDtos.java`
   - `domain/GenerationJob.java`, `repository/GenerationJobRepository.java`
   - `service/DataRetentionService.java` (purge redacts the prompt),
     `service/AccountClosureService.java`
5. `config/SecurityConfig.java`, `service/AuditLogService.java`
6. App:
   - the Studio (round 7 brief 02, round 8 brief 03): how it pre-fills from
     `?job=`, `?example=` and loose parameters
   - History and the job page, with round 9's save control
   - `app/src/App.tsx`, `app/src/components/Layout.tsx`,
     `app/src/lib/api.ts`, `app/e2e/mock-api.ts`

## Scope — backend

### Data (V29)

- **`recipes`:** id, user, title, modality, `source_gallery_recipe`
  (nullable), created and updated times.
- **`recipe_versions`:** id, recipe, number (1, 2, 3… per recipe), template,
  variables (jsonb), model, params (jsonb), created time. Unique on
  (recipe, number). Versions never change once written.
- **On `generation_jobs`:** `recipe_version_id`, nullable, set to null when
  the recipe is deleted. Index it.
- **The variable values a job used** go into the job's stored request
  (`prompt_json`), so the existing purge redacts them with the prompt.

### Template rendering

- One small class that implements the template rules in the coordination
  file, line for line. The same rules run in the marketing site (brief 02),
  and here in batches (round 12).
- Test it against a table of cases that includes the contract's edge cases:
  - an empty value
  - a value containing `{{x}}`
  - a stray `}}`
  - an undeclared variable
  - a declared variable that is never used
- **Limits:** a template is at most 32,000 characters, with at most 20
  variables, and each value is at most 2,000 characters.

### Routes

As in the contract. Each takes a session or an API key; add them to the
API-key rule in `SecurityConfig`. Owner only: another user's recipe answers
404.

- **Create** (`POST /v1/recipes`): takes title, modality, template,
  variables, model, params and optional `source_gallery_recipe`, and writes
  version 1.
- **New version** (`POST /v1/recipes/{id}/versions`): writes a version only
  when the template, variables, model or params differ from the latest.
  Otherwise it answers 200 with the latest version.
- **`attempts` and `spent_cents`** come from the recipe's jobs: their count,
  and the sum of `actualCostCents` for jobs that finished.
- **Delete** removes the recipe and its versions. Its jobs stay, unlinked.
- **Closing an account** deletes the account's recipes.
- **Limits:** `godance.recipes.max-per-account` (default 500) and
  `godance.recipes.max-versions` (default 200). Past either, answer 409
  with `recipe_limit_reached`.
- **Audit events:** `recipe.created` and `recipe.deleted`.

### Running a recipe

- **Request.** Generation requests accept `recipe` as in the contract. The
  DTO's `prompt` becomes optional, and a validator requires exactly one of
  `prompt` and `recipe`.
- **Rendering.** The server loads the version, checks it is the caller's,
  checks the modality, and renders the template. The rendered text is the
  job's prompt from then on: pricing, provider call and `JobView.prompt` all
  use it.
- **Recording.** Store `recipe_version_id` and the values through brief 01's
  origin record.
- **Reading.** `JobView.recipe` as in the contract, and `GET /v1/jobs`
  accepts `recipe=<id>`.

### Document it (last commit)

A "Recipes" section in `marketing/src/pages/docs/index.astro`, and a short
entry in `marketing/public/llms.txt`.

## Scope — app

### Variables in the Studio

- **When the form holds a template,** show one field per variable, filled
  with its default, and a live preview of the rendered prompt. The form
  holds a template when it was loaded from:
  - a gallery recipe with variables (brief 02's JSON)
  - one of the user's recipes (`?recipe=<id>`)
- **Editing the prompt text directly** turns the template off, after a
  confirmation. The fields disappear and the prompt is plain text.
- **Submitting.** Submit with `recipe` when the form holds one of the user's
  recipes. Submit with the rendered `prompt` when it holds a gallery
  recipe, since that is not the user's yet, and keep brief 01's
  `gallery_recipe`.

### Keeping a recipe

- **"Save as recipe"** in the Studio saves the current form:
  - template and variables, or the plain prompt as a template with no
    variables
  - model and params
  - the gallery slug when it came from one
- **Saving over a loaded recipe** offers "Save as new version". It is a
  no-op when nothing changed.
- **Turning text into a variable.** The template editor lets the user
  select text and turn it into a variable, with the selection as its
  default.

### Recipe pages

- **`/recipes`:** the user's recipes, newest first, paged. Each shows title,
  modality, latest model, attempts and spent.
- **`/recipes/:id`:**
  - the title (editable)
  - the latest version's template and variables, and its model and params
  - a version list
  - "Open in Studio"
  - the attempts: the recipe's jobs, newest first, each with its version,
    variable values, cost and a save control (round 9), and a running total
  - Delete, with a confirmation that says jobs are kept

### Compare

- **`/compare?jobs=<id>,<id>…`:** two to four of the user's jobs side by
  side, each showing:
  - outputs
  - model
  - params
  - variable values
  - prompt, with the differences from the first job highlighted
  - cost and time
- **Reaching it.** From a recipe's attempts, and from History: a "Select"
  mode with up to four jobs and "Compare".
- **Purged jobs** show that their files and prompt were deleted, and still
  show their cost.

### Navigation and links

- Add "Recipes" to the navigation. It must still fit on mobile.
- `?recipe=<id>` loads a recipe into the Studio. When several pre-fill
  parameters are present, round 8 says `?job=` wins over `?example=`, which
  wins over loose parameters. `?recipe=` sits between `?job=` and
  `?example=`.

## Hard rules

- Edits only in `backend/src/**`, migration V29, `application.yml`,
  `app/src/**`, `app/e2e/**`, and the two marketing files in the last
  commit.
- Additions only on the wire. A request without `recipe` behaves exactly as
  before.
- **The rendered prompt is the prompt.** Pricing, moderation by the
  provider, the stored request and the job's view all see the same text.
- Nothing here changes what a job costs.
- **Ownership.** A recipe is private to its owner. No route reveals another
  user's recipe, even by its count.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baselines and read brief 01's origin record. Write the renderer's
case table first, then the failing test: an image request with `recipe`
renders the template, and the job reads back the rendered prompt and
`recipe.version`. Then post a 3–5 bullet plan, with the component split for
the app, before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green.
- **Backend tests cover:**
  - the renderer's case table
  - create, list with paging, get, rename and delete, each owner-only
  - a new version only when something changed
  - both limits
  - running a recipe on images and on video
  - `prompt_and_recipe`, `recipe_not_found` for another user's recipe,
    `recipe_modality_mismatch` and `recipe_variable_unknown`, each with no
    ledger row
  - `attempts` and `spent_cents`
  - purge redacting the values with the prompt
  - deleting a recipe leaving its jobs readable
  - account closure deleting recipes
- **App tests cover:**
  - variable fields from a gallery recipe and from a user recipe
  - the preview
  - turning the template off after an edit
  - save as recipe, and save as new version, including the no-op case
  - selection into a variable
  - the recipe page with attempts and the total
  - compare from History and from a recipe, including a purged job
  - the `?recipe=` precedence
  - navigation on mobile
- A curl transcript of each route and of a generation with `recipe` in the
  final summary.
- Screenshots of the Studio with variables, a recipe page and the compare
  page, in light and dark.
- All work committed on `feature/my-recipes`; `main` untouched.

## Out of scope (do not start)

- Running a recipe over many rows (round 12).
- Publishing or sharing a recipe (round 14).
- Inputs in a recipe (round 11).
- Folders or tags for recipes.
- Prompt suggestions from a text model.
