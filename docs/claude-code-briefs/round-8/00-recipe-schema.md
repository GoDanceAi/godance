# Brief 00 — Recipe schema (step zero)

**Branch:** `feature/gallery-schema` (worktree `../gd-gallery-schema`) off `main`
**Reserved migration:** none. This brief has no backend or app changes.
**Baseline:** `npm ci && npm run lint && npm run build` green in `marketing/` on parent

## Mission (one line)

Put the recipe format into code once, with everything the import and pages
sessions would otherwise both have to touch, so those two can run at the
same time.

## Why now

Briefs 01 and 02 share only three things: the definition of a recipe, the
`test` script, and the dependency list. This brief lands all three on
`main` first. It is small on purpose. After it, 01 and 02 edit no file in
common.

## Read first

1. This brief and `00-coordination.md` (the recipe format is binding)
2. `marketing/package.json`, `marketing/astro.config.mjs`, `marketing/.gitignore`
3. The content collection documentation for the installed Astro version

## Scope

### 1. One schema for a recipe

- A single module that expresses the coordination format, importable both
  by the Astro content collection and by a plain Node script. There is no
  second copy of the format anywhere.
- Beyond the shape, it enforces:
  - `slug`: lowercase letters, digits and hyphens, and equal to the file name
  - both `prompt.en` and `prompt.zh` present and non-empty
  - at least one run; every run has a `cost_cents` and at least one output
  - a video run states `seconds`
  - media paths are relative: no scheme, no leading slash, no `..`
  - `source.licence` is one of `CC0-1.0`, `CC-BY-4.0`, `MIT`, `Apache-2.0`
  - `source.original_url` and `source.author_url` are `https` URLs
  - dates are ISO dates

### 2. The content collection

- Define the `gallery` collection over `marketing/src/content/gallery/`,
  validated by the schema module.
- `GALLERY_CONTENT_DIR` overrides the folder, so the pages can be built
  against fixtures.
- An empty folder is valid, and the built site is unchanged by it.
- A malformed recipe must fail `npm run build`. If Astro only validates a
  collection that some page reads, say so and make the build check it
  another way.

### 3. A `validate` command

Checks every recipe in a folder against the schema without starting Astro,
and also that no two recipes share a slug. Exit non-zero on any failure,
naming the file and the rule.

### 4. Package and ignore file

- A `test` script in `marketing/package.json` that runs `node --test` over
  everything under `marketing/scripts/`.
- `sharp` as a dev dependency. The import pipeline needs it, and it is
  already installed through Astro.
- `marketing/.gitignore` ignores `.gallery-work/`.

### 5. Tests

`node --test`:
- the example in `00-coordination.md` is accepted
- each rule in item 1 rejects a recipe that breaks it
- a duplicate slug is caught by `validate`
- an empty folder passes
- `GALLERY_CONTENT_DIR` is honoured

## Hard rules

- Edits only in: `marketing/src/content.config.*`,
  `marketing/src/lib/gallery-schema.*`, `marketing/scripts/gallery-schema/**`,
  `marketing/src/content/gallery/` (an empty folder), `marketing/package.json`,
  `marketing/package-lock.json` and `marketing/.gitignore`.
- No pages, no import tooling, no recipes.
- The format is the one in `00-coordination.md`. If it cannot be expressed
  as written, stop and raise it; do not change it quietly.
- Commit to your own branch. Do not merge to `main`. Do not push or open a
  pull request unless the operator asks. `main` auto-deploys.

## Opening move

Run the baseline. Check how the installed Astro version defines and
validates a content collection, and whether an unread collection is
validated at build. Then post a 3-bullet plan before writing code.

## Acceptance criteria

- `npm run lint`, `npm run build` and `npm test` green in `marketing/`.
- The built site has the same routes as before.
- The final summary shows a malformed recipe failing the build, and the
  `validate` command's output for it.
- All work committed on `feature/gallery-schema`; `main` untouched.

## Out of scope (do not start)

Everything in briefs 01, 02 and 03.
