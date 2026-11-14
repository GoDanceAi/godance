# Round 10 — Opening prompts (paste as the first message of each session)

Start only after rounds 7, 8 and 9 are merged to `main`. Sessions A, B and D
can run at the same time. Session C can start alongside them, but it rebases
onto A before it merges.

## Session A — Origin and graduation

```
You're a Claude Code session working on Godance round-10 brief 01 — Origin
and graduation: record where each job and each sign-up came from, and
report how many Studio users go on to generate with an API key.

Setup: working dir is a dedicated git worktree on branch
feature/origin-graduation, off main with rounds 7–9 merged. Confirm the
backend test count with a full run, and that lint, build and e2e are green
in app/, before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-10/01-origin-and-graduation.md (the brief)
2. docs/claude-code-briefs/round-10/00-coordination.md (the origin contract
   is binding; V28 is yours)
3. backend/CLAUDE.md
4. web/ImageController.java, web/VideoController.java, both generation
   services, domain/GenerationJob.java, web/dto/ImageDtos.java
5. web/AdminConsoleController.java and service/admin/AdminConsoleService.java
6. Round 9 brief 01 and its code: the referral capture and claim in the app

Hard rules:
- Commit to feature/origin-graduation as you go. Do NOT merge to main. Do
  NOT push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- channel is decided by the server, never by the client.
- Job creation takes ONE origin record; brief 03 will extend it.
- The funnel report returns counts only and drops buckets under 5 users.
- No third-party analytics or scripts.

Opening move: run the baselines. State, with file and line, where a job row
is created and where api_key_id is known. Write the failing test that a job
made with an API key reads back channel "API" and one made with a session
"STUDIO". Then summarize your build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), app lint, build and e2e
green, every acceptance case in the brief covered by a test, a curl
transcript of the new routes, all commits on your branch, main untouched.
Hand back.
```

## Session B — Recipe templates

```
You're a Claude Code session working on Godance round-10 brief 02 — Recipe
templates: keep a gallery recipe's variables as a {{name}} template in both
languages, add templates to published recipes where the render matches
exactly, and show them on recipe pages.

Setup: working dir is a dedicated git worktree on branch
feature/recipe-templates, off main with round 8 merged. Confirm
`npm ci && npm run lint && npm run build && npm test` is green in
marketing/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-10/02-recipe-templates.md (the brief)
2. docs/claude-code-briefs/round-10/00-coordination.md (the template rules
   and JSON additions are binding)
3. marketing/src/lib/gallery-schema.js and marketing/scripts/gallery-schema/**
4. marketing/scripts/gallery/** (the import tool), especially translate and
   the GPT Image 2 adapter
5. docs/gallery-content-standard.md, and the gallery pages and components

Hard rules:
- Commit to feature/recipe-templates as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- The schema change is additive: every published recipe stays valid.
- Rendering a template with its defaults must reproduce the published prompt
  exactly, in both languages, or no template is written.
- No paid API call. No change to any approved recipe's outputs, costs or
  review record.

Opening move: run the baseline. Write the failing schema tests: a template
whose render does not match prompt.en, and a stray "{{". Then summarize your
build plan in 3–5 bullets before writing code.

Done = marketing lint, build and test green, every acceptance case in the
brief covered, retemplate --all dry-run output and recipe page screenshots
in the summary, all commits on your branch, main untouched. Hand back.
```

## Session C — My recipes and attempts

```
You're a Claude Code session working on Godance round-10 brief 03 — My
recipes and attempts: users keep their own recipes with versions and
variables, run them from the Studio, and compare attempts side by side with
their costs.

Setup: working dir is a dedicated git worktree on branch
feature/my-recipes, off main with rounds 7–9 merged. Brief 01 lands first;
rebase onto it before you merge. Confirm the backend test count with a full
run, and that lint, build and e2e are green in app/, before changing
anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-10/03-my-recipes.md (the brief)
2. docs/claude-code-briefs/round-10/00-coordination.md (the recipe contract
   and template rules are binding; V29 is yours)
3. backend/CLAUDE.md
4. Brief 01's origin record (from its branch or main) — add your fields to it
5. The job path: controllers, DTOs, GenerationJob, GenerationJobRepository,
   DataRetentionService, AccountClosureService
6. The Studio, History and job pages, app/src/lib/api.ts, app/e2e/mock-api.ts

Hard rules:
- Commit to feature/my-recipes as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- A request without "recipe" behaves exactly as before.
- The rendered prompt is the job's prompt everywhere: pricing, provider,
  stored request, JobView.
- Recipes are private to their owner; another user's answers 404.
- The renderer follows the coordination rules line for line.

Opening move: run the baselines. Write the renderer's case table, then the
failing test that an image request with "recipe" renders the template and
the job reads back the rendered prompt and recipe.version. Then summarize
your build plan in 3–5 bullets, with the app's component split, before
writing code.

Done = mvn -B verify green (baseline + yours), app lint, build and e2e
green, every acceptance case in the brief covered by a test, curl
transcripts and screenshots in the summary, all commits on your branch,
main untouched. Hand back.
```

## Session D — Image responses in OpenAI's shape

```
You're a Claude Code session working on Godance round-10 brief 04 — Image
responses in OpenAI's shape: an API key can choose to have image requests
wait for the result and answer in OpenAI's shape, so standard OpenAI SDK
code works unchanged. Every key stays on today's behaviour by default.

Setup: working dir is a dedicated git worktree on branch
feature/openai-image-responses, off main with rounds 7–9 merged. Confirm the
backend test count with a full run, and that lint, build and e2e are green
in app/, before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-10/04-openai-image-responses.md (the brief)
2. docs/claude-code-briefs/round-10/00-coordination.md (the image-response
   contract is binding; V30 is yours)
3. backend/CLAUDE.md (hard constraint 5)
4. web/ImageController.java, web/dto/ImageDtos.java,
   service/ImageGenerationService.java, service/GenerationJobSettlement.java,
   service/GenerationJobSweepJob.java
5. The API key domain, service, controller and DTOs; app/src/pages/ApiKeysPage.tsx
6. docs/openai-dropin-verification.md and backend/deploy/Caddyfile
7. OpenAI's current Images API reference (b64_json and response_format)

Hard rules:
- Commit to feature/openai-image-responses as you go. Do NOT merge to main.
  Do NOT push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- With every key on async, behaviour is identical to the parent commit.
- The wait never blocks an event-loop thread, and survives the job finishing
  on another instance.
- Billing, settlement and refunds are reused, not copied.

Opening move: run the baselines. State, with file and line, where a job
becomes DONE and where it becomes FAILED. Write the failing test that a sync
key gets 200 with data[0].url. Then summarize your build plan in 3–5
bullets before writing code.

Done = mvn -B verify green (baseline + yours), app lint, build and e2e
green, every acceptance case in the brief covered by a test, an OpenAI
Python SDK run against the local stack in the summary, all commits on your
branch, main untouched. Hand back.
```
