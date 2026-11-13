# Round 8 — Coordination

**Date opened:** 2026-11-12 · **Parent:** `main` @ `bce9491` (update if `main` has moved)
**Workstreams:** 00 Recipe schema (step zero) · 01 Gallery import (tooling
and first batch of recipes) · 02 Gallery pages on the marketing site ·
03 "Run this" hand-off into the app

Context: the marketing site will carry a gallery of recipes. A recipe is a
prompt, the model and parameters it was run with, what it cost, and the
output, so anyone can reproduce it through the API. Recipes are imported from
open-source prompt lists with a clear licence, credited to their authors, and
every output is generated on Godance. This round builds the import pipeline,
defines the recipe format, and builds the pages that render it.

00, 01 and 02 touch only `marketing/` and `docs/`. They do not depend on
round 7 and can run alongside it. 03 touches only `app/` and needs round 7
brief 02 (Studio and History) on `main` first.

**What can run at the same time.** 00 is a short first step that lands the
recipe format in code. Once it is on `main`, 01 and 02 run together: they
edit no file in common. 03 can run alongside both, as soon as the Studio
is on `main`.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-gallery-schema -b feature/gallery-schema main
# after 00 is merged, both at once:
git worktree add ../gd-gallery-import -b feature/gallery-import main
git worktree add ../gd-gallery-pages  -b feature/gallery-pages main
# after round 7 brief 02 is merged:
git worktree add ../gd-gallery-hand-off -b feature/gallery-hand-off main
```

## Reserved resources

| Resource | 00 recipe-schema | 01 gallery-import | 02 gallery-pages |
|---|---|---|---|
| Flyway migrations | none | none | none |
| Backend, app | none | none | none |
| Marketing | `marketing/src/content.config.*`, `marketing/src/lib/gallery-schema.*`, `marketing/scripts/gallery-schema/**`, `marketing/package.json` and its lockfile, `marketing/.gitignore` | `marketing/scripts/gallery/**`, `marketing/src/content/gallery/**` | `marketing/src/pages/gallery/**`, `marketing/src/components/gallery/**`, `marketing/src/lib/gallery-config.ts` and other `gallery-*.ts` helpers except the schema, `marketing/fixtures/gallery/**`, `marketing/scripts/gallery-pages/**`, `marketing/public/_headers` |
| Existing files touched | none | none | `Nav.astro`, `pages/index.astro`, `pages/models.astro`: gallery links only |
| Docs | none | `docs/gallery-content-standard.md` | none |
| Env (operator's machine only) | none | `GODANCE_API_KEY`, `GODANCE_API_BASE` | none |

After 00, nobody edits `marketing/package.json`, its lockfile, the schema
module or the collection definition. 01 and 02 therefore share no file. If
either needs one of those changed, it stops and raises it.

03 edits only `app/src/**`, `app/e2e/**`, `app/playwright.config.ts` and
`app/.env.example`. It shares no file with 01 or 02 and reads the JSON
endpoints through mocks, so it can be built before 02 is merged.

## Recipe format (01 writes it; 02 and 03 will read it)

One JSON file per recipe at `marketing/src/content/gallery/<slug>.json`.
Keys are snake_case. A recipe holds one prompt and one or more runs of it. The example below is
invented to show the shape; it is not a real entry.

```json
{
  "slug": "paper-lantern-street-at-dusk",
  "title": { "en": "Paper lantern street at dusk", "zh": "黄昏的纸灯笼街" },
  "modality": "IMAGE",
  "tags": ["street", "photography"],
  "prompt": {
    "en": "…",
    "zh": "…",
    "zh_origin": "machine",
    "variables": [{ "name": "lantern color", "default": "warm red" }]
  },
  "runs": [
    {
      "model": "gpt-image-2",
      "prompt_lang": "en",
      "params": { "size": "1024x1024", "n": 1 },
      "cost_cents": 6,
      "generated_on": "2026-11-20",
      "outputs": [
        {
          "file": "paper-lantern-street-at-dusk/gpt-image-2-0.webp",
          "thumb": "paper-lantern-street-at-dusk/gpt-image-2-0-480.webp",
          "content_type": "image/webp",
          "width": 1024,
          "height": 1024
        }
      ]
    }
  ],
  "source": {
    "list": "Evolink-AI/awesome-gpt-image-2-API-Prompts",
    "list_commit": "<commit sha the entry was read at>",
    "licence": "CC0-1.0",
    "author": "example_author",
    "author_url": "https://x.com/example_author",
    "original_url": "https://x.com/example_author/status/0000000000000000000",
    "changes": ["placeholders resolved", "translated to Chinese"]
  },
  "review": { "approved_on": "2026-11-21", "standard_version": 1 },
  "published_on": "2026-11-21"
}
```

- `slug` is stable. Once a recipe is published it is never renamed.
- `title.zh` is optional. `prompt.en` and `prompt.zh` are both required.
- `prompt.zh_origin` is `list` when the source list supplied the Chinese
  text and `machine` when it was machine-translated.
- `prompt.variables` is optional: the placeholders a templated prompt had,
  with the defaults that were filled in to produce `prompt.en`.
- `params` uses the API's request field names. A video run always states
  `seconds`.
- `cost_cents` is the job's `actualCostCents` at the time it was generated.
- Media paths are relative to one media root. A video output has `file`,
  `content_type`, `duration_seconds`, `width`, `height` and an optional
  `poster`.
- `source.changes` lists what was changed from the original, as CC BY
  requires.
- The first run is the one the gallery shows by default. More runs of the
  same prompt on other models can be appended later.

**Media is not committed.** The pipeline writes it to a git-ignored work
folder in the layout above. Uploading it to public storage is the operator's
step.

**Fixtures.** 00 makes the collection's folder overridable with
`GALLERY_CONTENT_DIR`, so 02 can build the pages against invented recipes
without waiting for 01's content.

## The gallery switch (02 builds it)

`marketing/src/lib/gallery-config.ts` holds four public values, all empty
when it ships: the media base URL, the removal contact, the licence
statement for generated outputs, and whether "Run this" is on.

The gallery is on only when the media base URL and the removal contact are
set and at least one recipe exists. When it is off the build produces no
gallery output of any kind. That lets 02 merge before there is content.

## JSON endpoints (02 writes them; 03 will read them)

Static files, readable from any origin.

```json
GET /gallery/index.json
{ "recipes": [
    { "slug": "…", "title": { "en": "…", "zh": "…" }, "modality": "IMAGE",
      "model": "gpt-image-2", "tags": ["…"],
      "thumb": "https://<media base>/…-480.webp", "published_on": "2026-11-21" }
] }

GET /gallery/<slug>.json
{ "slug": "…", "title": { "en": "…", "zh": "…" }, "modality": "IMAGE",
  "prompt": { "en": "…", "zh": "…" },
  "run": { "model": "gpt-image-2", "prompt_lang": "en",
           "params": { "size": "1024x1024", "n": 1 } },
  "url": "https://godance.ai/gallery/<slug>" }
```

`model` and `run` describe the recipe's first run. `thumb` is an absolute
URL, and is the poster for a video or null when it has none.

## Hard rules

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- **Nothing is published unreviewed.** A session prepares candidates and the
  review sheet. Only the operator approves a recipe.
- **No paid API call without the operator's go-ahead and a spending cap.**
  Every pipeline stage defaults to a dry run.
- The API key comes from the environment. It is never written to a file, a
  log, a state file or a commit.
- A list with no licence, or a licence that does not allow reuse, is never
  a source.
- The lists' own example images and videos are never downloaded or copied.
- `npm ci && npm run lint && npm run build` stays green in `marketing/`,
  and `npm test` once 00 has added it.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

1. **00** first. It is small, and 01 and 02 both start from it.
2. **01 and 02**, in either order. 02 ships with the gallery off, so it can
   merge before any recipe exists. After 01 merges, the operator runs the
   first batch and approves recipes.
3. **03**, at any point after round 7 brief 02. Until "Run this" is turned
   on nothing links to it, so it is safe to merge early.
4. **Switching on** is the operator's step, below. Before it, build the site
   once with the real recipes and the pages together.

## Switching the gallery on (operator)

1. Recipes from the first batch are approved and published by `publish`.
2. The media in the manifest is uploaded to public storage.
3. `gallery-config.ts` gets the media base URL, the removal contact and the
   output licence statement, and the licence notice's gaps are filled.
4. `marketing/public/llms.txt` gets a short gallery section.
5. Merge. The deploy builds the pages. Check a recipe page, a model page,
   both JSON endpoints and the sitemap on the live site.

**Turning "Run this" on** is a second, later step. Do it once 03 is live
and the gallery is on: open `<app>/create/image?example=<slug>` for a real
recipe on production, signed out and signed in, then set the switch in
`gallery-config.ts` and merge.

Not covered by any brief yet: measuring how many first runs start from a
recipe.

## Operator decisions

Needed before the paid stages run:

- **Spending cap for the first batch.** Suggested: 6000 cents. Images are
  cheap (about 6 cents each on `gpt-image-2`); video dominates (about 16
  cents a second on `seedance-2.0` at the seed prices).
- **The content standard.** 01 drafts it; the operator approves it before
  any review starts.
- **First sources.** Suggested: the CC0 GPT Image 2 list for images and the
  CC BY Seedance list for video.

Needed before the gallery is switched on:

- **Where public media lives**, and its base URL.
- **The licence statement for generated outputs.**
- **The contact address for removal requests.**
