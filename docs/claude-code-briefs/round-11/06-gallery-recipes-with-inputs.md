# Brief 06 — Gallery recipes with inputs

**Branch:** `feature/gallery-inputs` (worktree `../gd-gallery-in`) off `main` once this round's coordination file is merged
**Reserved migration:** none. This brief has no backend or app changes.
**Baseline:** `npm ci && npm run lint && npm run build && npm test` green in `marketing/` on parent

## Mission (one line)

Stop setting aside the gallery's input recipes. Give each one input slots
and a sample input generated on Godance, and show input and output side by
side. Recipes with people wait for the operator's go-ahead.

## Why now

Round 8's `screen` stage sets aside every entry that needs an input image,
because the API could not take one. That is 21–32% of image prompts and 31%
of Seedance prompts in the sources, and they are the recipes a visitor most
wants to try with their own product. Briefs 01–04 make the API take inputs.
Brief 05 makes the Studio offer the recipe's sample and let the user swap in
their own.

## Read first

1. This brief and `00-coordination.md` (the gallery JSON addition and the
   input forms are binding)
2. `marketing/src/lib/gallery-schema.js`, `marketing/scripts/gallery-schema/**`,
   including round 10's template rules
3. `marketing/scripts/gallery/**`: every stage, the budget guard, resume
   behaviour and the removal list
4. `docs/gallery-content-standard.md`
5. The gallery pages and components, `marketing/fixtures/gallery/**`
6. The API docs for `/v1/files` and `/v1/images/edits` (briefs 01 and 03),
   and video inputs (04)

## Scope

### 1. The format (additive)

- A recipe gains optional `inputs`: up to 4 slots. Each slot has:
  - `slot` (template naming rule)
  - `role` (`image` for image recipes; `first_frame`, `last_frame` or
    `reference_image` for video)
  - `hint` (`en`, optional `zh`)
  - `sample`, made up of:
    - `file`, `content_type`, `width` and `height` (relative media path, as
      outputs have)
    - `generated_from`: `model`, `prompt`, `params`, `cost_cents` and
      `generated_on`
- **Checks:**
  - a slot's role fits the modality
  - a sample is always present
  - `generated_from` is complete
  - a recipe with inputs has every run made with those samples
- **Every published recipe stays valid.**

### 2. The import takes input recipes

`screen` gains `--inputs none|objects|people`, defaulting to `none`, which
is today's behaviour.
- **`objects`:** entries whose input is a product or object pass to a new
  stage. Entries about a person's face or likeness stay set aside with that
  reason.
- **`people`:** person entries also pass. Refuse this option unless the
  content standard's version says person samples are allowed (item 4).

**New stage, `sample`.** For each passing entry:
1. **Propose slots.** Use a Godance text model for the slot name, role and
   hint in both languages, from the prompt. The operator edits them in the
   review sheet.
2. **Write a sample prompt.** It describes a plausible input: a plain
   product photo, or for `people` a fictional adult who resembles no real
   person.
3. **Generate the sample.** Run it on Godance and record it in
   `generated_from`.
   - **Never use the source list's example images**, as round 8 already
     requires.
   - Run it through the same budget guard and resume rules as `generate`.

**`generate`.** For an input recipe it uploads the sample through
`/v1/files` and runs:
- an image recipe through `/v1/images/edits` (JSON with the file id)
- a video recipe with the video input fields

It records `cost_cents` as today. The operator's account must be adult,
have accepted the input terms, and have inputs enabled. Check that first and
stop with a clear message if not.

**The review sheet** shows sample → output for each candidate, the slots,
and both costs.

### 3. Pages

- **Recipe page.** For input recipes, show the sample input or inputs
  beside the output, labelled with each slot's hint. Add one line: "Run this
  with your own" when "Run this" is on.
- **How it was made** lists the sample's own model and cost, and the run's.
- **The API call** shows the upload and the edit, or the video request with
  its input, as two quoted here-documents, in the docs page's style.
- **JSON.** `/gallery/<slug>.json` adds `inputs` exactly as in the
  contract, with absolute `sample_url`s. `/gallery/index.json` adds
  `has_inputs: true`.
- **Gallery filter.** Add "Uses your image".

### 4. Content standard

Add a section to `docs/gallery-content-standard.md`, with a new version
number:
- A sample input is generated on Godance.
- An object sample shows no person.
- **Person samples** depict a fictional adult who resembles no real or
  public person. They are not allowed until the operator raises the
  standard's version with this rule enabled.
- **The hint** never asks for a real person's name, a child, or anyone who
  has not consented.

### 5. Fixtures

- an image recipe with one `image` slot
- a video recipe with a `first_frame` slot
- an invalid one: a sample with no `generated_from`

## Hard rules

- Edits only in `marketing/src/lib/gallery-schema.js`,
  `marketing/scripts/**`, `marketing/src/pages/gallery/**`,
  `marketing/src/components/gallery/**`, `marketing/fixtures/gallery/**` and
  `docs/gallery-content-standard.md`.
- **Every paid stage defaults to a dry run** and requires
  `--max-spend-cents`. That includes `sample`.
- **No person recipe** is screened in, sampled or published unless the
  standard's version allows it.
- **Nothing is published unreviewed.**
- The API key comes from the environment and is never written anywhere.
- `npm ci && npm run lint && npm run build && npm test` stays green.

## Opening move

Run the baseline. Count, at the sources' pinned commits, the entries that
`screen` sets aside for inputs, split into object and person. Write the
failing schema tests: an input recipe without a sample, and a slot whose
role does not fit. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- **`npm test` covers:**
  - each new schema rule
  - every published recipe still valid
  - `--inputs objects` screening a person entry out with its reason
  - `--inputs people` refused under the current standard version
  - the `sample` stage dry run, and its budget guard
  - `generate` checking the account before spending
- The site builds with fixtures and real content.
- `/gallery/<slug>.json` for an input fixture matches the contract.
- In the final summary:
  - the object and person counts
  - a dry-run plan for the first ten object entries, with estimated cost
  - screenshots of an input recipe page in light and dark
- All work committed on `feature/gallery-inputs`; `main` untouched.

## Out of scope (do not start)

- Publishing any person recipe.
- Uploading media to public storage (operator).
- The Studio's use of samples (brief 05).
- New sources.
