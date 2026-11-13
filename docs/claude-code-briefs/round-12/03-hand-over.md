# Brief 03 — Hand-over: capped keys, code, the AI-assistant prompt, n8n

**Branch:** `feature/hand-over` (worktree `../gd-handover`) off `main` with round 11 merged; rebase onto brief 02 before merging
**Reserved migration:** none. The cap columns already exist on `api_keys`.
**Baseline:** full backend suite green on parent; `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; `npm ci && npm run lint && npm run build && npm test` green in `marketing/`

## Mission (one line)

From any working recipe or Studio result, give the user three ready ways to
run it outside the app:
- a prompt for their AI assistant
- working code in three languages
- an n8n template

Also give them an API key that cannot spend more than they choose.

## Why now

The people Godance is built for will eventually call the API, usually
through code an AI assistant writes for them, or through a no-code tool.
Three things stand in the way:

- **Their assistant does not know Godance.** Handing it a precise prompt
  that holds the recipe and the docs gets the first script right.
- **The Studio's code panel shows only curl** (round 7). Python with the
  OpenAI SDK is what assistants write by default.
- **Keys cannot be capped by their owner.** `SpendingLimitService` enforces
  per-key daily and monthly caps, but `CreateApiKeyRequest` has only `name`
  and `scopes`, and there is no update route for caps. A runaway script is
  the fear that stops people trying.

n8n's public template library already holds Seedance, Kling and Veo
workflows through kie.ai and fal. In some of them each Google Sheets row is
a job. Godance's async jobs fit the same pattern, and it has none.

## Read first

1. This brief and `00-coordination.md` (the API key additions are binding)
2. `backend/CLAUDE.md`
3. `domain/ApiKey.java`, `service/ApiKeyService.java`,
   `web/ApiKeyController.java`, `web/dto/ApiKeyDtos.java`, and round 10
   brief 04's `PATCH /v1/keys/{id}`
4. `service/SpendingLimitService.java`: how a key's day and month spend are
   computed
5. App:
   - the Studio's "View code" panel (round 7)
   - the recipe and batch pages (rounds 10 and 12)
   - `app/src/pages/ApiKeysPage.tsx`, `app/e2e/keys.spec.ts`
6. Marketing: the gallery recipe page's API call block (round 8, extended in
   rounds 10 and 11), `marketing/src/pages/docs/index.astro`
7. n8n's documentation for workflow JSON import, the HTTP Request, Google
   Sheets, Wait, If and Loop nodes, and its template submission guidelines

## Scope — backend

- **Create and update.** `POST /v1/keys` and `PATCH /v1/keys/{id}` accept
  `daily_cap_cents`, `monthly_cap_cents` and `expires_at`, as in the
  contract.
  - A cap must be at least 1 cent or null.
  - `expires_at` must be in the future.
  - Owner only and session only.
- **Summary.** `ApiKeySummary` gains the three fields, plus
  `spent_today_cents` and `spent_month_cents`, computed exactly as
  `SpendingLimitService` computes them. Extract the shared query rather
  than copying it.
- **Audit event:** `api_key.caps_changed`, with old and new values.
- **Expiry.** An expired key is refused as a revoked key is. Check whether
  `ApiKeyAuthFilter` already enforces `expires_at`, add it if not, and test
  both.

## Scope — app

### 1. "Run at scale" panel

The panel sits on a recipe page, a finished Studio result, and a batch page.
It has four tabs.

- **Ask your AI assistant.** A ready prompt with a Copy button. It holds:
  - the goal, for example "run this recipe for every row of my CSV"
  - the recipe as JSON: template, variables, input slots, model, params
  - the API base URL
  - links to `https://godance.ai/llms-full.txt` (brief 04) and the docs
  - the rules the script must follow:
    - read the key from the `GODANCE_API_KEY` environment variable and never
      write it into the code
    - use the OpenAI Python SDK with `base_url`
    - for images, use a synchronous key or poll `/v1/jobs/{id}`
    - estimate cost first with `/v1/estimates`
    - stop on a 402
    - write results to a CSV
    
  The wording is in one constant, versioned, for the operator to edit. It
  carries no account data except the recipe.
- **Code.** curl, Python (OpenAI SDK) and Java (Spring `WebClient`) for
  this exact request, generated from the same object as the curl.
  - Images: the sync and async variants, and polling.
  - Video: `/v1/videos` once brief 04 is live, and `/v1/videos/generations`
    otherwise.
  - Recipes: with `recipe` and its variables.
  - Edits: with inputs.
- **No-code (n8n).** Which template to use, a download link to it, and four
  numbered steps to import it and set the key.
- **Batch.** A link to round 12's new-batch page, for recipes.

### 2. A capped key in one click

In the panel and on the API keys page: "Create a key for this".
- Name it after the recipe.
- Set a daily cap, defaulting to 5.00 USD and editable, and an optional
  expiry.
- Show the key once, with the line "Set it as GODANCE_API_KEY". The
  generated code still uses the variable and never the key itself.

### 3. API keys page

Each key shows its caps, today's and this month's spend against them, its
expiry, and round 10's image response setting, all editable.

## Scope — marketing

- **The gallery recipe page** gains the same "Run at scale" block, built
  statically: the assistant prompt, the code, and n8n. There is no key
  creation, since the visitor is signed out. The code uses
  `$GODANCE_API_KEY` and links to sign-up with round 10's UTM tags.
- **Docs.** A "Run from n8n" section in
  `marketing/src/pages/docs/index.astro`: the templates, how to set the key
  as a credential, and what each node does.

## Scope — `integrations/n8n/`

A new top-level folder with:

- `godance-sheet-to-images.json`: Google Sheets rows → Godance image job →
  poll until done → write the output URL and cost back to the row.
- `godance-sheet-to-videos.json`: the same for video, with a `seconds`
  column and an optional `first_frame_file_id` column.
- **Node and credential rules:**
  - Only n8n core nodes. No community nodes, and no Code node where a core
    node will do.
  - The key goes in an n8n "Header Auth" credential named `Godance API`.
    Never write it into the workflow.
- **Polling, not webhooks.** A Wait and If loop, with backoff and a maximum
  number of tries. It needs no public URL, which suits the audience.
- **A row that fails** writes the error to the sheet and the workflow
  carries on.
- `README.md`: what each template does, the sheet columns, how to import,
  and the cost warning (estimate first).
- `marketing/scripts/n8n/n8n-templates.test.js`. It is the check, placed
  there so `marketing`'s `npm test`, and therefore CI, runs it. It reads
  `integrations/n8n/*.json` and checks that each one:
  - parses as an n8n export
  - uses only allowed node types
  - holds no credential value
  - points at `https://api.godance.ai/v1`

## Hard rules

- Edits only in `backend/src/**` (keys), `app/src/**`, `app/e2e/**`,
  `marketing/src/pages/gallery/**`, `marketing/src/components/gallery/**`,
  `marketing/scripts/n8n/**`, the docs page section, and
  `integrations/n8n/**`.
- **No real key ever appears** in generated code, the assistant prompt, a
  template, a test fixture or a screenshot.
- **Caps are enforced by the existing `SpendingLimitService` path.** This
  brief only lets owners set them.
- Generated code must run as shown. Test the curl and Python variants
  against the local stack with WireMock providers.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baselines. Check whether `ApiKeyAuthFilter` enforces `expires_at`
today, with file and line. Write the failing test: a key created with
`daily_cap_cents: 100` is refused with 402 once it has spent 100 cents
today. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green, app lint, build and e2e green, marketing lint,
  build and test green.
- **Backend tests cover:**
  - caps set on create and by `PATCH`
  - null clearing a cap
  - validation
  - spend figures matching `SpendingLimitService`
  - an expired key refused
  - owner-only and session-only access
- **App tests cover:**
  - the panel's four tabs on a recipe, a Studio result and a batch
  - code variants for image sync and async, video, a recipe and an edit
  - a capped key created and shown once
  - caps and spend on the keys page
- The n8n check passes in `npm test`, and each template imports into n8n.
  Run n8n in Docker locally once if you can; otherwise say it was not
  tried.
- In the final summary:
  - the curl and Python variants run against the local stack
  - the assistant prompt as shipped
- All work committed on `feature/hand-over`; `main` untouched.

## Out of scope (do not start)

- An n8n community node.
- Make or Zapier templates.
- Account-wide caps set by the user.
- A key per batch created automatically.
- Submitting templates to n8n's library (operator).
