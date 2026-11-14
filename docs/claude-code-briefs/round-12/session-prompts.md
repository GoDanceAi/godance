# Round 12 — Opening prompts (paste as the first message of each session)

Start only after round 11 is merged to `main`. A, C and D can run at the same
time. B can start alongside them against mocks, and rebases onto A before it
merges. C rebases onto B for the recipe page.

## Session A — Recipe batches

```
You're a Claude Code session working on Godance round-12 brief 01 — Recipe
batches: run one recipe version over up to hundreds of rows, each row an
ordinary job charged by the existing path, never submitted twice, paused
(not failed) when credit or a cap runs out.

Setup: working dir is a dedicated git worktree on branch
feature/recipe-batches, off main with round 11 merged. Confirm the backend
test count with a full run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-12/01-recipe-batches.md (the brief)
2. docs/claude-code-briefs/round-12/00-coordination.md (the batch contract
   is binding; V36 is yours)
3. backend/CLAUDE.md
4. Round 10 brief 03's recipe code and round 11 brief 05's input slots
5. Both generation services, the edit path, GenerationJobSettlement,
   GenerationJobSweepJob, the Quartz cron jobs, SpendingLimitService,
   WebhookDestinations, the round 10 funnel, retention and closure

Hard rules:
- Commit to feature/recipe-batches as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- A batch charges nothing itself; every cent goes through the normal job
  pre-debit and settlement.
- No row is ever submitted twice, including after a crash.
- Scheduling is single-instance, like the existing cron jobs.

Opening move: run the baseline. State, with file and line, how an abandoned
job is recovered. Write the failing test that a three-row batch with
max_concurrency 1 produces three jobs in order with the ledger summing to
spent_cents. Then summarize your build plan in 3–5 bullets before writing
code.

Done = mvn -B verify green (baseline + yours), every acceptance case covered
by a test, a curl transcript of a whole batch, all commits on your branch,
main untouched. Hand back.
```

## Session B — Batches in the app

```
You're a Claude Code session working on Godance round-12 brief 02 — Batches
in the app: run a recipe over a pasted table or CSV, with images per row
for input slots, see the total first, follow progress, handle a pause, and
download results.

Setup: working dir is a dedicated git worktree on branch feature/batches-app,
off main with round 11 merged. Develop against mocks; rebase onto brief 01
before merging. Confirm lint, build and e2e are green in app/ before
changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-12/02-batches-in-the-app.md (the brief)
2. docs/claude-code-briefs/round-12/00-coordination.md (the batch contract
   is binding)
3. The recipe pages and input slots in the app, BillingPage.tsx, the shared
   out-of-credit handling, App.tsx, Layout.tsx, api.ts, e2e/mock-api.ts

Hard rules:
- Commit to feature/batches-app as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- No new runtime dependency without flagging it first.
- Nothing starts without the explicit Start after the estimate is shown.
- 500 rows stay responsive.

Opening move: run the baseline. Write the failing e2e test: a pasted 3-row
table maps two variables and previews three prompts. Then summarize your
build plan in 3–5 bullets with the component split before writing code.

Done = app lint, build and e2e green on desktop and mobile, every acceptance
case covered, screenshots in the summary, all commits on your branch, main
untouched. Hand back.
```

## Session C — Hand-over

```
You're a Claude Code session working on Godance round-12 brief 03 —
Hand-over: capped API keys set by their owner, a "Run at scale" panel with
an AI-assistant prompt, working code in curl, Python and Java, and n8n
templates.

Setup: working dir is a dedicated git worktree on branch feature/hand-over,
off main with round 11 merged; rebase onto brief 02 before merging. Confirm
the backend count, app lint, build and e2e, and marketing lint, build and
test are green before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-12/03-hand-over.md (the brief)
2. docs/claude-code-briefs/round-12/00-coordination.md (the key additions
   are binding)
3. backend/CLAUDE.md
4. The API key domain, service, controller and DTOs; SpendingLimitService;
   ApiKeyAuthFilter
5. The Studio's View code panel, the recipe and batch pages, ApiKeysPage,
   the gallery recipe page's API block
6. n8n's docs for workflow JSON and the core nodes the templates use

Hard rules:
- Commit to feature/hand-over as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- No real key ever appears in code, prompts, templates, fixtures or
  screenshots.
- Caps are enforced by the existing SpendingLimitService path.
- Generated curl and Python must run as shown against the local stack.

Opening move: run the baselines. State whether ApiKeyAuthFilter enforces
expires_at, with file and line. Write the failing test that a key with
daily_cap_cents 100 is refused with 402 after spending 100 cents today.
Then summarize your build plan in 3–5 bullets before writing code.

Done = backend, app and marketing all green, every acceptance case covered,
the n8n check passing, generated code run against the local stack and the
assistant prompt in the summary, all commits on your branch, main
untouched. Hand back.
```

## Session D — OpenAI's video shape and docs for AI assistants

```
You're a Claude Code session working on Godance round-12 brief 04 — OpenAI's
video shape and docs for AI assistants: /v1/videos as a second view of
video jobs, a complete OpenAPI file guarded by a drift test, and
llms-full.txt for AI assistants.

Setup: working dir is a dedicated git worktree on branch
feature/openai-videos, off main with round 11 merged. Confirm the backend
test count and marketing lint, build and test before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-12/04-openai-videos-and-ai-docs.md
2. docs/claude-code-briefs/round-12/00-coordination.md (the video routes
   are binding; V37 only if needed)
3. backend/CLAUDE.md (hard constraint 5)
4. VideoController, VideoDtos, VideoGenerationService, JobController,
   round 11's video inputs, every controller in web/
5. marketing/public/llms.txt, the docs page, docs/openai-dropin-verification.md
6. OpenAI's current Videos API reference and SDK behaviour

Hard rules:
- Commit to feature/openai-videos as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- A video is a job: no second store. /v1/videos/generations and /v1/jobs
  are unchanged. Delete never refunds.
- Nothing documented that is not live.

Opening move: run the baselines. Post OpenAI's video object and request
from the current reference, with the link, and your mapping to Godance
jobs. Write the failing test for POST /v1/videos with seconds "8". Then
summarize your build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours, including the drift test),
marketing green with the consistency check, an OpenAI SDK create_and_poll
and download_content run in the summary, all commits on your branch, main
untouched. Hand back.
```
