# Round 10 — release notes

Merged to `main` on 2026-11-14, in this order: #28 (01), #31 (03), #30 (02),
#29 (04).

Everything shipped on its safe default. The decisions in
[Decisions still open](#decisions-still-open) were left for the operator and
nothing in this round waits on them.

## What shipped

### 01 Origin and graduation (#28)

- **Where a job came from.** Every job records `channel` (`STUDIO` for a
  signed-in session, `API` for an API key; the server decides) and
  `galleryRecipe`. Generation requests take an optional `gallery_recipe`
  slug; any other value answers 400 with `invalid_gallery_recipe`.
- **Where a sign-up came from.** `POST /v1/attribution/first-touch` (session
  only) keeps the campaign tags, gallery recipe, landing page and referring
  site the app saw before sign-in. It is written once.
- **The funnel.** `GET /v1/admin/funnel` reports, by week of sign-up and by
  source, how many accounts made a Studio job, a paid top-up and a job with
  an API key. The admin Overview page shows it. It gives counts only.

### 02 Recipe templates (#30)

- A gallery recipe keeps its variables: `prompt.template` (English and
  Chinese) and `prompt.variables` in `/gallery/<slug>.json`, and
  `has_variables` in the index. Each language's template, filled with its
  defaults, is that language's prompt, and the schema checks it.
- The import pipeline keeps a source prompt's blanks as variables instead of
  filling them in, and `retemplate` adds a template to a recipe published
  before this round when it can do so without changing the published prompt.
- The recipe page shows the template and its variables.

### 03 My recipes and attempts (#31)

- **Recipes.** `/v1/recipes`: create, list, get, rename, delete, list
  versions, and save a new version, which is written only when something
  changed. A recipe is private to its owner.
- **Running one.** Generation requests take
  `recipe: { version_id, variables }` in place of `prompt`. The server fills
  the template in, and the result is the job's prompt for pricing, the
  provider and `JobView.prompt`. The job carries `recipe`, and
  `GET /v1/jobs` takes `recipe=<id>`.
- **Studio.** A form that holds a template shows a field per variable and
  fills the prompt in as they change. "Save as recipe" and "Save as new
  version" keep the setup; selected text becomes a variable. `?recipe=<id>`
  loads a recipe, between `?job=` and `?example=`.
- **Pages.** `/recipes`, `/recipes/:id` with versions, attempts and the
  total spent, and `/compare?jobs=…` for two to four generations side by
  side, reached from a recipe's attempts or from "Select" in History.

### 04 Image responses in OpenAI's shape (#29)

- Each API key has an `image_response` setting: `async` (a job to poll, as
  before) or `sync` (the request waits for the job and answers with the
  images, as OpenAI's API does, so unchanged OpenAI SDK image code works).
- `PATCH /v1/keys/{id}` changes it, from a session only. The API keys page
  shows and changes it.
- A request that outlasts the wait answers 202 with the job, and the job
  goes on.

## Database

Three migrations, all additive, applied in number order:

| Migration | Adds |
|---|---|
| V28 | `generation_jobs.channel`, `generation_jobs.gallery_recipe`, `users.first_touch`, `users.first_touch_at` |
| V29 | `recipes`, `recipe_versions`, `generation_jobs.recipe_version_id` |
| V30 | the image-response setting on `api_keys` |

## Settings added, and what they are set to

| Setting | Value | What it does |
|---|---|---|
| `godance.images.new-key-response` | `async` | what a new key gets when its creation names nothing |
| `godance.images.sync-wait` | `90s` | the longest a `sync` request waits before answering 202 |
| `godance.images.max-inline-bytes` | `25MB` | the most image data one response carries as `b64_json` |
| `godance.attribution.first-touch-window-hours` | `168` | how long after sign-up a first touch is accepted |
| `godance.recipes.max-per-account` | `500` | recipes one account can keep |
| `godance.recipes.max-versions` | `200` | versions one recipe can have |

## Decisions still open

Each is at the default the coordination notes suggested as safe. None blocks
anything in this round.

1. **The default for new API keys.** New keys are `async`, so nothing
   changed for anyone. Standard OpenAI SDK image code works only on a `sync`
   key. Switch `godance.images.new-key-response` to `sync` once the image
   procedure in `docs/openai-dropin-verification.md` has been run against
   production. Existing keys stay as they are either way.
2. **How long a `sync` request waits.** 90 seconds. It has to stay below
   whatever limit the proxy in front of the API puts on the start of a
   response; the coordination notes give the numbers.
3. **The first-touch window.** 168 hours after sign-up.
4. **The privacy policy.** It does not yet say that the app records where a
   sign-up came from: campaign tags, the gallery recipe, the landing page and
   the referring site. This is the one open item that is a text to write
   rather than a number to confirm.
5. **Limits for recipes.** 500 per account and 200 versions per recipe.
6. **Migration order.** Flyway's out-of-order setting is off, so migrations
   have to reach `main` in number order: a build that brings V28 after V30
   was applied refuses to start. That fixed this round's merge order
   (01, 03, 04), which the coordination notes had left free for 04. A later
   round with reserved migration numbers needs the same care, or the setting
   turned on.
7. **The gallery content standard** is still draft v1. It gained a section
   on templates this round. The gallery stays switched off until recipes are
   published.

## Where the result differs from the briefs

- **01.** The app captures a first touch only for a visitor who arrives
  signed out, and closing an account clears it. The docs lines about
  `gallery_recipe` are shown only while the gallery is on.
- **02.** A recipe published with variables and no template stays valid.
  `retemplate` leaves a recipe alone when a default was translated, since it
  cannot be found again without translating.
- **03.**
  - Saving keeps what the form shows: each variable's value becomes its
    default in the saved version.
  - Once a template's text is edited, a run is a plain prompt until the form
    is saved as a new version, and the page says so.
  - Opening a job that ran a recipe brings the recipe back in the Studio,
    at the version and with the values it ran with.
  - With "Recipes" added, the navigation sits in the header row from 1280px
    up, and a long account address is shortened rather than wrapping a link.
- **04.** The GPT Image models answer with `b64_json` and with `url` as
  well. Changing a key writes the audit event `apikey.updated`.

## Checks

On `main` with all four merged:

| | Before the round | After |
|---|---|---|
| Backend (`mvn -B verify`) | 1143 | 1386 |
| App e2e (desktop and mobile) | 420 | 734 |
| Marketing (`npm test`) | 297 | 428 |

Each pull request carries its own evidence: curl transcripts of the new
routes, an OpenAI Python SDK run for 04, and what the screenshots showed.

## Not yet exercised

- 02's templates and 03's variable fields meet on the gallery's JSON. They
  are tested against fixtures in the shape 02 writes; no recipe is published
  yet, so they have not met on real content.
- A `sync` key with a real provider. The local checks used a stand-in for
  the image provider.
