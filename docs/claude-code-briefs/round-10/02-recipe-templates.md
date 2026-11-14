# Brief 02 — Recipe templates

**Branch:** `feature/recipe-templates` (worktree `../gd-templates`) off `main` with round 8 merged
**Reserved migration:** none. This brief has no backend or app changes.
**Baseline:** `npm ci && npm run lint && npm run build && npm test` green in `marketing/` on parent

## Mission (one line)

Keep a recipe's variables as a template, so a visitor can fill in their own
values, and attribute API runs that start from a recipe page.

## Why now

Between 20% and 47% of the prompts in the lists Godance imports from have
variables. Both global lists write them as
`{argument name="…" default="…"}`. Round 8's import resolves them to their
defaults and keeps only the result. The recipe is then fixed to someone
else's product, brand or wording.

Aspiring builders adapt before anything else, and later they will run the
same recipe over a sheet of values (round 12). A template is what makes
both possible.

## Read first

1. This brief and `00-coordination.md`. The template rules and the JSON
   additions are binding.
2. `marketing/src/lib/gallery-schema.js` and `marketing/scripts/gallery-schema/**`
   (the format, `checkRecipe`, `validate` and their tests)
3. `marketing/scripts/gallery/**` (round 8 brief 01's import tool),
   especially `translate`, and the GPT Image 2 list adapter that resolves
   placeholders
4. `docs/gallery-content-standard.md`
5. `marketing/src/pages/gallery/**`, `marketing/src/components/gallery/**`,
   and how the recipe page shows variables and the API call today
6. `marketing/fixtures/gallery/**`
7. `docs/research/2026-11-prompt-gallery-competitors.md` §4 and §5.3

## Scope

### 1. The format (additive)

In `gallery-schema.js`:
- `prompt.template`: optional `{ "en": "…", "zh": "…" }`. Both languages
  are required when it is present.
- `prompt.variables[]` gains an optional `zh_default`.
- New checks, each with its own rule tag and message:
  - When there is a template, there are variables, and the other way round.
  - Variable names follow the naming rule and are unique.
  - Each template uses every variable and only declared variables. Every
    `{{` begins a variable.
  - Rendering `template.en` with the defaults gives exactly `prompt.en`.
  - Rendering `template.zh` with `zh_default`, or `default` where that is
    absent, gives exactly `prompt.zh`.
- Put the renderer in one small module that the checks, the import tool and
  the pages all use. Write it so its rules can be read against the contract
  line by line, because the backend (brief 03) implements the same rules.
- **Every recipe already published must stay valid.** The template is
  optional.

### 2. The import keeps templates

- **Placeholders.** The GPT Image 2 adapter turns `{argument name="x"
  default="y"}` into `{{x}}` with default `y`, instead of resolving it. A
  name that breaks the naming rule is turned into one that follows it, and
  the change is listed in `source.changes`.
- **Translation.** `translate` keeps every `{{name}}` marker unchanged.
  - Text meant to appear inside the image is still left as the author wrote
    it.
  - Translate the defaults into `zh_default` where they are words, and leave
    them out where they are not, such as numbers, brand names or sizes.
  - **Check the markers survived.** When a translation loses or changes a
    marker, retry once. If it fails again, fall back to the resolved prompt
    without a template, and record why in the review notes.
- **`source.changes`.** "placeholders resolved" becomes "placeholders
  rewritten as {{name}}" when the template is kept.
- **Review sheet.** It shows the template with its variables marked, and
  the defaults in both languages.

### 3. Add templates to published recipes

A `retemplate <slug>` command (and `--all`):
- Re-reads the source entry at the recipe's pinned `list_commit`.
- Builds the template as in item 2.
- Writes it only if rendering with the defaults reproduces the published
  `prompt.en` and `prompt.zh` exactly.

That rule means outputs, costs and review dates stay true, and no paid call
is needed. It reports, for each recipe, what it did and why.

### 4. Pages

- **Recipe page.** In place of today's variables list:
  - the template, with each variable shown as a marked chip
  - a table of variables with their defaults
  - a short line saying the Studio can fill them in

  "Copy" on the prompt still copies the resolved prompt. Add "Copy
  template".
- **The API call** on a recipe page includes `"gallery_recipe": "<slug>"` in
  the request body. Brief 01 records it, which attributes runs that start
  from a pasted example.
- **Calls to action** ("Run this", "Get an API key") carry
  `utm_source=godance&utm_medium=gallery&utm_campaign=<slug>`.
- **JSON.** `/gallery/<slug>.json` adds `prompt.template` and
  `prompt.variables` exactly as in the contract. `/gallery/index.json` gains
  `has_variables: true` where there are variables.

### 5. Standard and fixtures

- `docs/gallery-content-standard.md`: a short section on templates.
  - The defaults must themselves meet the standard.
  - A variable must not invite a real person's name or likeness, so no
    variable named "celebrity" or "your face".
- Fixtures: a recipe with a two-variable template in both languages, and a
  JSON-shaped template that ends in `}}`.

## Hard rules

- Edits only in the marketing and docs paths reserved for 02 in
  `00-coordination.md`.
- Additions only to the format and to the JSON endpoints.
- No paid API call. `retemplate` makes none.
- No change to an approved recipe's outputs, costs or review record.
- `npm ci && npm run lint && npm run build && npm test` stays green.

## Opening move

Run the baseline. Pick three placeholder entries from the GPT Image 2 list
at the pinned commit. Write the failing schema tests first: a template whose
render does not match `prompt.en`, and a stray `{{`. Then post a 3–5 bullet
plan before writing code.

## Acceptance criteria

- `npm test` covers:
  - every new rule rejecting a recipe that breaks it
  - a JSON-shaped template ending in `}}` accepted
  - a value containing `{{x}}` inserted as it is
  - every published recipe still valid
  - the adapter converting placeholders
  - a lost marker falling back with a review note
  - `retemplate` writing only when the render matches
- The site builds with fixtures and with the real content.
- `/gallery/<slug>.json` for a fixture with variables matches the contract.
- In the final summary:
  - `retemplate --all` dry-run output for the published recipes
  - a screenshot of a recipe page with variables, in light and dark

## Out of scope (do not start)

- Filling in variables on the marketing site.
- The Studio's fields (brief 03).
- Templates for prompts that had no placeholders in the source.
- New sources.
- Categories (a later round).
