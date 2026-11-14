# Brief 03 — Comparisons and model launches

**Branch:** `feature/comparisons` (worktree `../gd-comparisons`) off `main` with round 12 merged
**Reserved migration:** none. This brief has no backend changes.
**Baseline:** `npm ci && npm run lint && npm run build && npm test` green in `marketing/`; `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`

## Mission (one line)

Add runs on other models to published recipes, cheaply and under a cap.
Publish pages that compare models on the same prompts with real outputs and
costs. Write down the routine for the 48 hours after a model launch. Let
"Run this" choose a run the visitor can actually use.

## Why now

A recipe schema with several runs per prompt has existed since round 8, and
nothing has used it. Comparing models on the same prompt, with what each run
cost, is something only a multi-model API can produce cheaply. About 20
prompts on 5 image models costs around 6 USD.

These comparisons help an aspiring builder choose a model before they spend.
They are also what people search for when a model launches. EvoLink
publishes "coming soon" pages to catch those searches. Godance can publish
evidence instead.

## Read first

1. This brief and `00-coordination.md` (the `runs` addition to the JSON is
   binding)
2. `marketing/scripts/gallery/**`: `generate`, the budget guard, resume, the
   review sheet and `publish`
3. `marketing/src/lib/gallery-schema.js`: runs, inputs (round 11) and
   templates (round 10)
4. The gallery pages: recipe, model pages (`/gallery/model/<id>`),
   components and helpers
5. The app's recipe hand-off (round 8 brief 03, extended in rounds 10 and
   11): how it chooses the model, and what it does when the model is not in
   `/v1/models`
6. `docs/research/2026-11-prompt-gallery-competitors.md` §5.8 and §5.9

## Scope

### 1. A `rerun` stage in the import tool

`rerun --models <id,…> [--slugs …|--tag …|--all] --max-spend-cents N`
- **What it does.** Adds a run on each named model to each selected
  published recipe, using:
  - the recipe's prompt, or the template with its defaults
  - the recipe's inputs' samples, through round 11's paths
  - parameters mapped from the first run, and only those the target model's
    capabilities allow, with a mapping file for exceptions
- **Rules:**
  - A dry run is the default.
  - It uses the same budget guard, the same no-double-payment resume rule,
    and at most two jobs in flight.
  - It never replaces or reorders existing runs; it appends.
- **The review sheet** shows the new runs beside the first.
  - Only approved runs are written by `publish`. Approving a new run does
    not change the recipe's review record.
  - Record the approval date per run (a new optional `approved_on` on a
    run, added to the schema additively).
- **`--china-hosted`** adds a run on the cheapest China-hosted model of the
  recipe's modality, for each selected recipe that lacks one. The models
  are listed in a committed file, taken from the regional allowlist's
  providers.

### 2. Comparison pages: `/gallery/compare/<model-a>-vs-<model-b>`

- **When one is generated.** For a pair of models when at least
  `GALLERY_COMPARE_MIN` recipes (default 8) have an approved run on both.
  Otherwise there is no page and no link.
- **Contents:**
  - a short factual introduction, generated from the data, with no claims
    the data does not show
  - for each recipe, the two outputs side by side, with each cost and a
    link to the recipe
  - a summary table: recipes compared, total and average cost per model,
    and each model's price per image or per second today, linked to
    `/pricing`
  - a "Run both" link per recipe into the app's compare flow (round 10), or
    to sign-up
- **Discovery.** Model pages link to their comparisons, and an index lists
  all of them at `/gallery/compare`. Structured data, canonical URL and
  sitemap follow the recipe pages.
- **No winner.** The pages show evidence and leave judging quality to the
  reader.

### 3. JSON and the app's hand-off

- **JSON.** `/gallery/<slug>.json` adds `runs`, as in the contract. `run`
  stays.
- **App.** When a recipe has `runs`, the hand-off picks the first run whose
  model is listed in the caller's `/v1/models`, which reflects the region
  that serves them. Only when none is listed does it fall back to the
  Studio's default and say so, as round 8 does.
  - Change only the choice of run; keep everything else in the hand-off.
  - Add e2e cases: first run unavailable and second available; none
    available.

### 4. The launch routine: `docs/gallery-model-launch.md`

A short runbook for the operator, for the first 48 hours after a model joins
the catalog:
1. Confirm the model id, price and capabilities in `/v1/models`.
2. Choose 20 recipes covering its strengths.
3. `rerun` dry run, then a real run under a cap.
4. Review.
5. Publish.
6. Check that the comparison pages appear.
7. Add a line to `llms.txt`.
8. Post the comparison.

Include the commands, the cost formula and what to check on the live site.

## Hard rules

- Edits only in the marketing and app paths reserved for 03 in
  `00-coordination.md`, and the runbook.
- **Every paid stage is a dry run by default** and requires
  `--max-spend-cents`.
- **Nothing is published unreviewed.** A new run on a published recipe
  needs its own approval.
- Existing runs, outputs and review records are never changed.
- No client framework and no new runtime dependency on the site.
- `marketing/` and `app/` checks stay green.

## Opening move

Run the baselines. Read `generate` and the budget guard end to end. Write
the failing test that `rerun --models X --slugs a` in dry-run mode plans one
job per recipe and spends nothing. Then post a 3–5 bullet plan before
writing code.

## Acceptance criteria

- **`npm test` covers:**
  - rerun planning, the budget guard and resume
  - parameter mapping, including a parameter the target model cannot take
  - `--china-hosted` choosing from the committed list
  - the schema's per-run `approved_on`
  - a comparison generated at the threshold, and not below it
  - `runs` in the JSON
- **App e2e** covers the hand-off choosing an available run, and the
  fallback.
- The site builds with fixtures that have two-model recipes.
- In the final summary:
  - a dry-run plan for 20 recipes on 5 models, with the estimated total
  - screenshots of a comparison page in light and dark
- All work committed on `feature/comparisons`; `main` untouched.

## Out of scope (do not start)

- Scoring or ranking model quality.
- Comparison pages for text models.
- Changing the catalog (operator).
- Automatic reruns on a schedule.
