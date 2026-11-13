# Brief 02 — Gallery pages

**Branch:** `feature/gallery-pages` (worktree `../gd-gallery-pages`) off `main` once brief 00 is merged
**Reserved migration:** none. This brief has no backend or app changes.
**Baseline:** `npm ci && npm run lint && npm run build && npm test` green in `marketing/` on parent
**Runs alongside:** brief 01. The two share no file, and you do not need its output.

## Mission (one line)

Give every recipe its own page on the marketing site, and a gallery page to
browse them, built statically from the recipe files and switched off until
the operator turns it on.

## Why now

The import pipeline produces recipes that nothing renders. Each recipe page is a
working example of the API: the prompt, the model and parameters, what it
cost, the output, and the request that reproduces it. Each one is also a
page a search engine or an AI agent can find, which the site has few of today.

## Read first

1. This brief and `00-coordination.md` (recipe format, JSON endpoints and
   the gallery switch are binding)
2. `00-recipe-schema.md` and the schema module it produced. Skim
   `01-gallery-import.md` to know where recipes come from; it is being
   built at the same time, so none of its output exists yet
3. `marketing/src/layouts/Layout.astro` — title, description, canonical,
   `jsonLd`, `ogImage` and `noindex` are already props
4. `marketing/src/components/Nav.astro`, `marketing/src/pages/index.astro`,
   `marketing/src/pages/models.astro`, `marketing/src/lib/catalog.ts`
5. `marketing/src/styles/global.css` for the shared classes (`btn`, `card`,
   `pill`, `code-block`, `code-chip`) and the light and dark theme
6. `marketing/src/pages/docs/index.astro` for how API snippets are shown
7. `marketing/public/_headers` and `marketing/astro.config.mjs` (sitemap)

## Scope

### 1. The gallery switch

- A committed module, `marketing/src/lib/gallery-config.ts`, holds the
  operator's inputs: the media base URL, the removal contact, the licence
  statement for generated outputs, and whether "Run this" is on. They are
  public values, not secrets. It ships with all of them empty.
- The gallery is **on** only when the media base URL and the removal contact
  are set and at least one recipe exists.
- When it is off, the build generates no gallery route, no navigation entry,
  no home-page strip, no links from `/models`, and nothing in the sitemap.
  The built site has no gallery output at all.
- For local work and tests, environment variables override the config, and
  `GALLERY_CONTENT_DIR` points the collection at fixtures.

### 2. Gallery page: `/gallery`

- A grid of cards, newest first: thumbnail, title, model, and a mark on
  videos. CSS columns, so mixed shapes pack without script.
- Images are lazy-loaded and carry `width`, `height` and `alt`.
- Filters for modality, model and tag, and a text search over titles and
  prompts in both languages. Filter state lives in the URL, so a filtered
  view can be linked and survives reload.
- Without JavaScript every recipe is listed and the model filter still
  works as links. Search may be hidden.
- Search data is fetched when the visitor first uses search. It is not
  inlined in the page.
- Render all cards on one page for now. Say in the final summary at what
  size that stops being reasonable.

### 3. Recipe page: `/gallery/<slug>`

- **Output.** The image, or the video with controls, `preload="none"` and
  its poster. With no poster, `preload="metadata"`. Nothing autoplays.
- **Title,** with the Chinese title beneath when there is one.
- **Prompt,** in English and in Chinese, each in its own block with a Copy
  button. Mark the Chinese block `lang="zh-Hans"`. When `zh_origin` is
  `machine`, say it is a machine translation.
- **Variables,** when the recipe has them: the name and the default that
  was used.
- **How it was made.** Model, parameters, the cost recorded when it was
  generated, and the date. Link to `/pricing` for current prices; do not
  recompute the cost.
- **The API call.** The request that reproduces the run and the request
  that polls the job, in the docs page's style. Use `$GODANCE_API_KEY` as
  the placeholder. Pass the body through a quoted here-document so any
  prompt, with quotes and newlines, can be pasted and run as shown.
- **Call to action.** "Run this" links to
  `<app>/create/image?example=<slug>` or `/create/video?example=<slug>`
  when the switch says so. Otherwise show "Get an API key", linking to
  signup. It ships off.
- **Credit.** The author with a link to the original post, the source list,
  its licence, and what was changed. Then the licence statement for the
  output, and how to ask for removal.
- **More than one run.** Show each run with its model, cost and output.
- **Related recipes.** Up to six, by shared tags and model.

### 4. Model pages: `/gallery/model/<id>`

- The recipes that have a run on that model, with the same cards.
- Generated only for models that have at least one recipe.
- `/models` links each such model to its page.

### 5. Home page

A strip of the six newest recipes, linking to `/gallery`.

### 6. Search engines and agents

- Each recipe page has its own title, description (from the start of the
  English prompt), canonical URL, and the output as its Open Graph image.
  `Layout.astro` resolves `ogImage` against the site, so pass the media
  host's absolute URL.
- Structured data on each recipe page. Be exact about who made what: the
  **prompt's author** is the original author, with the original post as its
  source and the source licence; the **output's creator** and the **page's
  publisher** are Godance. Use `VideoObject` for video, with a thumbnail
  only when a poster exists.
- The sitemap integration picks the pages up. Check that it does.

### 7. JSON for the app

Static endpoints, as described in `00-coordination.md`:
- `/gallery/index.json` — a light list for "start from an example".
- `/gallery/<slug>.json` — one recipe, for the hand-off into the app.
- Add a rule to `marketing/public/_headers` for `/gallery/*.json`: readable
  from any origin, cached for a few minutes.

### 8. Fixtures

`marketing/fixtures/gallery/`: a handful of invented recipes, with tiny
placeholder media generated for the purpose. Cover an image, a video with a
poster, a video without one, a machine-translated prompt, a prompt with
variables and a recipe with two runs. No text or media from the source lists.

## Hard rules

- Edits only in the paths reserved for 02 in `00-coordination.md`.
- No client framework and no new runtime dependency. Small inline scripts
  only. No third-party script, tracker or embed.
- Do not change the recipe format. If a page needs something it lacks,
  stop and raise it.
- Do not edit `marketing/package.json`, its lockfile, the schema module or
  the collection definition. Put the page tests under
  `marketing/scripts/gallery-pages/`, where the existing `test` script
  already finds them.
- A recipe that fails validation fails the build. Never skip one quietly.
- Do not add a web font for Chinese. The system font stack handles it.
- Both themes must work, and the pages must work on a phone.
- Do not change `.github/workflows/`.
- Do not edit `marketing/public/llms.txt`. Its gallery section is written
  when the operator switches the gallery on.

## Opening move

Run the baseline. Write the fixtures first, then build with the gallery on
against them and with it off, and confirm the off build has no gallery
output. Post a 3–5 bullet plan with the page and component split before
writing the pages.

## Acceptance criteria

- `npm run lint`, `npm run build` and `npm test` green in `marketing/`.
- **Off build:** no `/gallery` files in `dist/`, no gallery link in the
  navigation, home page or `/models`, and no gallery URL in the sitemap.
- **On build, against the fixtures,** checked by a `node --test` script
  that reads `dist/`:
  - one page per recipe, each with a unique title and canonical URL
  - both prompt blocks present, the Chinese one marked `lang="zh-Hans"`,
    and the machine-translation note only where it applies
  - credit, source licence and removal line on every recipe page
  - structured data parses, and names the original author as the prompt's
    author
  - "Run this" absent when off and correct when on
  - the API snippet's body is valid JSON for a prompt containing quotes,
    a dollar sign, a backtick and newlines
  - images carry `width`, `height` and `alt`; video never autoplays
  - both JSON endpoints are valid and match the coordination shapes
  - model pages exist only for models with recipes
- Screenshots in the final summary: gallery, an image recipe and a video
  recipe, each on desktop and phone, in light and dark.
- All work committed on `feature/gallery-pages`; `main` untouched.

## Out of scope (do not start)

Tag pages. Separate compare pages. Pagination. Playing video on hover.
Translating the site's own text. Likes, comments or accounts. The app side
of "Run this" (workstream 03). Uploading media. Any change to the import
pipeline or the recipe format.
