# Brief 02 — Open recipe list

**Branch:** `feature/open-recipe-list` (worktree `../gd-open-list`) off `main` with round 12 merged
**Reserved migration:** none. This brief has no backend or app changes.
**Baseline:** `npm ci && npm run lint && npm run build && npm test` green in `marketing/` on parent

## Mission (one line)

Generate an open, bilingual list of Godance's published recipes from the
gallery's content, and publish it to its own GitHub repository from CI. Each
entry shows the exact model, parameters and cost it was generated with, and
how to run it.

## Why now

In this niche, people find prompts through GitHub lists:

| List | Stars | Notes |
|---|---|---|
| EvoLink (GPT Image 2) | 17.4k | 11 languages |
| YouMind (Nano Banana Pro) | 13.8k | 16 languages, updated automatically |
| OpenNana's source list | 3.9k | |

Every entry in them links back to its operator's site. Godance's gallery has
something none of them can claim: every example was generated through the
API with exactly these parameters, for exactly this price. A list makes that
visible where the audience already looks.

## Read first

1. This brief and `00-coordination.md`
2. `marketing/src/lib/gallery-schema.js` and its reader (round 8 brief 00);
   round 10's templates; round 11's inputs
3. `marketing/src/lib/gallery-config.ts`: the media base, the output licence
   statement, the removal contact, and the switch
4. The recipe page (round 8 brief 02 and later): how credit, licences and
   changes are shown. The list must credit in the same way.
5. The committed removal list (round 8 brief 01's `remove`)
6. `.github/workflows/ci.yml` and `deploy-frontends.yml`, for the repository's
   workflow conventions
7. The three lists above, for how a good README entry reads. Read them
   only; copy nothing from them.

## Scope

### 1. The generator: `marketing/scripts/open-list/`

`npm run open-list -- --out <dir>` reads the gallery collection, through the
same reader as `validate`, and writes the following.

- **`README.md` (English) and `README_zh.md` (Chinese).** Each has:
  - an introduction saying what is different: reproducible, priced, multiple
    models
  - a contents list by modality and by tag or category
  - one entry per recipe
  - statistics: count, models, last updated
- **An entry contains:**
  - title
  - output thumbnail, as an absolute media URL, never a copy of the file
  - the prompt in a code block, or the template with its variables
  - input slots and their sample, where present
  - each run's model, params and cost
  - "Run this" and "View recipe" links with
    `utm_source=github&utm_medium=open-list&utm_campaign=<slug>`
  - credit: author with link, original post, source list and its licence,
    and what was changed, as the recipe page shows it
- **`data/recipes.json`:** the same entries as data, for people who build on
  the list.
- **`LICENSE.md`:**
  - each prompt keeps its source licence, which the entry states
  - outputs follow the gallery's output licence statement
  - Godance's own text follows the licence the operator chooses, left as a
    marked gap
- **`CONTRIBUTING.md`:** how to report a problem or ask for removal, using
  the gallery's removal contact. The list is generated, so pull requests
  that edit entries are not merged.

**Rules for the generator:**
- It refuses to run when the gallery switch is off.
- It is deterministic: the same content gives byte-identical output, apart
  from the "last updated" line, which comes from the newest `published_on`.
- A recipe on the removal list is never written. A removed recipe disappears
  on the next run.

### 2. Publishing from CI: `.github/workflows/open-list.yml`

- **When it runs:** on push to `main` when `marketing/src/content/gallery/**`
  or the generator changes, and on manual dispatch.
- **What it does:** generates the list and pushes it to the repository named
  by the repository variable `OPEN_LIST_REPO`, using the deploy key in the
  secret `OPEN_LIST_DEPLOY_KEY`. It does nothing, successfully, when either
  is unset. That is the state it ships in.
- **Commits.** It commits only when the output changed, with a message
  naming the recipes added and removed. It never force-pushes.
- **Conventions.** Pin actions as the other workflows do, and give the job
  the least permissions.

### 3. Tests

`node --test` under `marketing/scripts/open-list/`:
- a fixture set produces the expected README sections, in both languages
- the output is deterministic
- the removal list is respected
- a CC BY entry carries author, link, licence and changes
- the run is refused with the switch off
- no absolute local path, environment value or key appears in the output

## Hard rules

- Edits only in `marketing/scripts/open-list/**`, `marketing/package.json`
  (one script entry, no new dependency), and `.github/workflows/open-list.yml`.
- **Only approved, published recipes,** exactly as the gallery shows them.
- **No media is copied into the list.** Thumbnails are links to the media
  host.
- **Attribution is never weaker than on the recipe page.**
- The workflow cannot run on pull requests from forks, and holds no secret
  in a file.
- `npm ci && npm run lint && npm run build && npm test` stays green.

## Opening move

Run the baseline. Read how the recipe page builds credit lines, and reuse
that code rather than writing a second version. Write the failing test that
a fixture recipe with a CC BY source produces an entry with author, link,
licence and changes. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `npm test` green, with the cases above.
- In the final summary:
  - the generated README for the fixtures, in English and in Chinese
  - the workflow's dry-run log with the variables unset, showing a skip
- All work committed on `feature/open-recipe-list`; `main` untouched.

## Out of scope (do not start)

- More README languages than English and Chinese.
- Accepting entries by pull request.
- Creating the repository (operator).
- Recipes users publish (round 14).
