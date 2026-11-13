# Brief 01 — Gallery import

**Branch:** `feature/gallery-import` (worktree `../gd-gallery-import`) off `main` @ `bce9491`
**Reserved migration:** none. This brief has no backend or app changes.
**Baseline:** `npm ci && npm run lint && npm run build` green in `marketing/` on parent

## Mission (one line)

Build the pipeline that turns entries from licensed open-source prompt lists
into reviewed, bilingual, reproducible recipes, and prepare the first batch
for the operator's review.

## Why now

The gallery needs content before it needs pages, and the content needs only
the API that is live today. A recipe is more than a copied prompt: it is
credited to its author, checked against a content standard, carries English
and Chinese text, and shows an output that Godance generated at a recorded
cost. That takes a pipeline with a human review step, not a one-off script.

## Read first

1. This brief and `00-coordination.md` (the recipe format is binding)
2. `marketing/README.md`, `marketing/package.json`, `marketing/astro.config.mjs`
3. `marketing/src/lib/catalog.ts` — how the site already reads the API at build time
4. The API docs page, `marketing/src/pages/docs/index.astro`: image and
   video generation, jobs, error shapes
5. The content collection documentation for the installed Astro version
6. The two first sources, read-only, at a commit you pin:
   - `Evolink-AI/awesome-gpt-image-2-API-Prompts` (CC0-1.0)
   - `YouMind-OpenLab/awesome-seedance-2-prompts` (CC BY 4.0)

## Scope

### 1. A small command-line tool

Under `marketing/scripts/gallery/`. Node 22, ES modules, built-in `fetch`.
Its working state lives in `marketing/.gallery-work/`, which is git-ignored.
Every stage can be re-run and picks up where it stopped.

**`fetch`** — read entries from a source at a pinned commit.
- One adapter per list. Each yields: title, prompt, any language variants,
  author, original post URL, category, and whether the entry needs an input
  image, video or audio.
- Check the list's licence file at the pinned commit first. Refuse to read a
  list whose licence is missing or is not the one the adapter expects.
- Drop duplicates by original post across lists, and anything on the
  removal list.
- What the two first sources look like today:
  - **GPT Image 2 list:** `data/ingested_tweets.json` indexes author, post
    and category; the prompts are in `cases/*.md`. The `_zh-CN` files
    translate the headings only, not the prompts. Prompts may contain
    placeholders of the form `{argument name="…" default="…"}`.
  - **Seedance list:** `README.md` and `README_zh.md` carry each entry in
    English and Chinese, with author, source post and date.

**`screen`** — set aside what cannot be used.
- Entries that need an input image, video or audio: the API does not accept
  inputs yet.
- Entries that fail the content standard (item 2).
- Screening may use a Godance text model as a classifier. It records a
  reason for every entry it sets aside. It only narrows the list; it never
  approves anything.

**`translate`** — make the prompt complete in both languages.
- Use the list's own Chinese or English text when it exists, and a Godance
  text model otherwise. Record which in `prompt.zh_origin`.
- Text that is meant to appear inside the image stays as the author wrote it.
- Resolve placeholders to their defaults and record them in `prompt.variables`.

**`generate`** — run the prompt on Godance.
- Use the model the list was written for when Godance serves it
  (`gpt-image-2`, `seedance-2.0`); otherwise a mapping file the operator edits.
- Images with `n: 1`. Video always with an explicit `seconds`.
- Poll the job, then download at once: output links expire. Use the job's
  `outputs` when it has them and `outputUrl` otherwise.
- Record the job's `actualCostCents`.
- Convert images to WebP and make a 480-pixel thumbnail. Make a video poster
  when `ffmpeg` is installed; warn and leave it out when it is not.
- **Budget guard.** A real run requires `--max-spend-cents`. The tool adds
  up what it has spent and what the next job will hold, and stops before
  the cap would be passed.
- **No double payment.** Save a job's id as soon as the API accepts it. An
  interrupted run resumes that job; it never submits the same run twice.
- At most two jobs in flight. Back off on `429`.

**`review`** — build a review sheet for the operator.
- A local static page in the work folder. For each candidate: the output,
  the prompt in both languages, the source and author, the screening notes
  and the cost.
- The operator marks each one approved or rejected, with a reason for a
  rejection. The decisions are saved in the work folder.

**`publish`** — write the approved recipes.
- One file per recipe in `marketing/src/content/gallery/`, in the
  coordination format, validated before it is written.
- Write a manifest of the media files to upload. Media is never committed.

**`remove <slug>`** — take a recipe down.
- Delete the recipe file, add its original post to a committed removal list
  so a later import cannot bring it back, and list its media for deletion.

### 2. The content standard

Draft `docs/gallery-content-standard.md` as version 1, for the operator to
approve before any review starts. The gallery is all-ages. Suggested
minimum rules:

1. No real people, named or described so as to be recognisable.
2. No third-party characters, franchises, brands, logos or trademarks, and
   no "in the style of" a named living artist.
3. Nothing sexualised or suggestive, no nudity, no focus on body parts.
4. No minors as the subject of a portrait, fashion, romance or similar prompt.
5. No graphic violence, gore, self-harm, drugs or hate symbols.
6. Nothing that imitates official documents, identity papers, currency,
   receipts, news reports, or a real organisation's advertising or interface.
7. No political, medical or financial claims in rendered text.
8. When unsure, reject.

The reviewer applies the standard to the Godance output as well as to the
prompt: a harmless prompt can still produce an output that fails.

### 3. Format and validation

- Define the `gallery` content collection with a schema equal to the
  coordination format, so a malformed recipe fails the site build.
- Let `GALLERY_CONTENT_DIR` override the collection's folder, so brief 02
  can build the pages against fixtures.
- Add a `validate` command that checks every committed recipe without
  starting Astro, and a `test` script in `marketing/package.json`.

### 4. Licence notice

`marketing/src/content/gallery/NOTICE.md`: the source lists and their
licences, a statement that each prompt is credited to its author, and how
changes are recorded. Leave clearly marked gaps for the removal contact and
the licence of generated outputs. Those are operator decisions; do not
invent them.

### 5. Tests

`node --test`, with the API mocked. Cover:
- each adapter, against small fixtures written for the test in the same
  shape as the list (no third-party text in fixtures)
- placeholder resolution, de-duplication and the removal list
- the budget guard never passing its cap
- a resumed run not resubmitting an accepted job
- format validation rejecting: a missing language, a run with no cost, a
  video run with no `seconds`, a duplicate slug
- the API key never appearing in the work folder or in output

### 6. The first batch

Runs only after the operator's go-ahead and with a cap.
- Target about 80 images and 20 videos approved.
- Expect to set aside a large share on the way: many entries need an input
  image, name real people or franchises, or fall outside the standard.
- Prefer video prompts written for short clips. At the seed prices a
  10-second `seedance-2.0` clip costs about 25 times one `gpt-image-2` image.

## Hard rules

- Edits only in the paths reserved in `00-coordination.md`.
- Dry run is the default for every stage. No paid call without the
  operator's go-ahead and `--max-spend-cents`.
- A session never approves a recipe and never edits the review decisions.
- The API key is read from the environment and written nowhere.
- Never download or copy a list's own example images or videos.
- `sharp` may be added as a dev dependency; it is already installed through
  Astro. Flag any other new dependency before adding it.
- Do not change `.github/workflows/`. CI does not run the new tests; say so
  in the final summary and leave adding a step to the operator.

## Opening move

Run the baseline. Read both source lists at a commit you pin, without
writing anything, and report for each: how many entries, how many need an
input, and anything about its shape that this brief gets wrong. Then post a
3–5 bullet build plan before writing code.

## Acceptance criteria

- `npm run lint`, `npm run build` and `npm test` green in `marketing/`.
- A dry run over both sources prints the count at each stage and the
  projected spend, and makes no paid call.
- Every test in item 5 passes.
- The content standard and the licence notice are drafted.
- After the go-ahead: a review sheet the operator can work through, and a
  `publish` that writes only approved recipes.
- The final summary gives, per source: the pinned commit, entries read,
  entries set aside by reason, runs generated, recipes approved, and the
  total spent against the cap.
- All work committed on `feature/gallery-import`; `main` untouched.

## Out of scope (do not start)

Gallery pages, search or filters. The hand-off into the app. Runs of one
prompt across several models. Uploading media to public storage. Entries
that need an input image. Community submissions. Changes to CI.
