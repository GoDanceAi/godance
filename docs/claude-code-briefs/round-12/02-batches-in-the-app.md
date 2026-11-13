# Brief 02 — Batches in the app

**Branch:** `feature/batches-app` (worktree `../gd-batches-ui`) off `main`; rebase onto brief 01 before merging
**Reserved migration:** none. This brief has no backend changes.
**Baseline:** `npm run lint`, `npm run build` and `npm run test:e2e` green in `app/` on parent

## Mission (one line)

Let a user run one of their recipes over a pasted table or a CSV, with
images per row where the recipe has input slots. They see the total before
starting, follow progress, deal with a pause, and download the results.

## Why now

A batch in the API is still a script for most of the people Godance is built
for. In the app it is a table they already have: a spreadsheet of products,
or a folder of photos.

## Read first

1. This brief and `00-coordination.md`. The batch contract is binding;
   develop against mocks until brief 01 merges.
2. Round 10's recipe pages and round 11's input slots in the app
3. `app/src/pages/BillingPage.tsx` (top-up), the Studio's estimate display
   and out-of-credit handling (the shared `sendError` module)
4. `app/src/App.tsx`, `app/src/components/Layout.tsx`, `app/src/lib/api.ts`,
   `app/e2e/mock-api.ts`
5. `app/src/index.css` for shared classes, and the admin Overview page
   (round 10's funnel)

## Scope

### 1. New batch: `/recipes/:id/batch`

Reached from "Run as batch" on the recipe page.

- **Rows.** Paste a table, tab- or comma-separated, or choose a CSV file.
  - The first row is the header. Columns whose names match the recipe's
    variables map to them automatically, and others can be mapped by hand
    or ignored.
  - Variables with no column use their defaults, and the page says so.
- **Parsing.**
  - Write a small parser that follows RFC 4180: quoted fields, embedded
    commas, quotes and newlines.
  - Detect UTF-8 with or without a byte-order mark.
  - Cover it with unit-style e2e checks, or flag a dependency before adding
    one.
- **Input slots.** For a recipe with input slots, rows get images in one of
  two ways:
  - Drop many files at once and match them to rows by a column holding file
    names. This is the default when such a column exists.
  - Otherwise match them by order.

  Upload through `/v1/files` with progress, and show which rows are missing
  an image.
- **Preview.** Show the first three rows' rendered prompts and their inputs.
- **Before starting:**
  - Choose the model, defaulting to the recipe's, and the concurrency, from
    1 to the server's cap.
  - Show the estimate: rows, total, balance. When `enough` is false, link
    to Billing.
- **Start** creates the batch and opens its page.
- **Errors.** A 400 naming a row highlights that row with the reason.

### 2. Batch page: `/batches/:id`

- **Status and progress.** Status, done, failed and total counts, spent
  against the estimate, and a progress bar. Poll every 3 seconds while
  running, then stop.
- **Rows table.** Each row shows its index, its variables (truncated), a
  status pill, the output thumbnail, cost and error. It pages with `after`.
  A row opens its job page.
- **Paused:**
  - for credit: a link to Billing, then Resume
  - for a cap: say which cap, link to API keys or Settings, then Resume
- **Cancel,** with a confirmation that says running rows will finish.
- **Downloads:** CSV and zip. When the zip answers `results_too_large`, say
  so and offer the CSV.
- **Grid view** of all outputs, as an alternative to the table.

### 3. Batches list: `/batches`

Newest first, with status, recipe, progress and spent. Add it to the
navigation under Recipes, or as its own entry if it fits on mobile.

### 4. Elsewhere

- **Recipe page:** "Run as batch", and a list of its batches.
- **Job page:** for a job with `batch`, a link back to the batch and row.
- **History:** a filter for batch jobs, or a badge on them.
- **Admin Overview:** a `first_batch` column in the funnel tables.

## Hard rules

- Edits only in `app/src/**` and `app/e2e/**`.
- No new runtime dependency without flagging it first.
- **Nothing starts without the explicit Start,** after the estimate has been
  shown.
- Large tables stay responsive: 500 rows paste, preview and upload without
  freezing the page.
- Use the existing theme tokens and classes, in light and dark. Tables are
  keyboard- and screen-reader-navigable, and progress is announced.

## Opening move

Run the baseline. Read the recipe page and the estimate display. Write the
failing e2e test first: pasting a 3-row table into a recipe with two
variables maps both columns and previews three prompts. Then post a 3–5
bullet plan with the component split before writing code.

## Acceptance criteria

- `npm run lint`, `npm run build` and `npm run test:e2e` green on desktop and
  mobile.
- **Specs cover:**
  - paste and CSV, with quoted fields, a byte-order mark and newlines in a
    field
  - automatic and manual column mapping, and defaults
  - images by file-name column and by order, with missing images flagged
  - estimate with `enough` false
  - start
  - progress polling stopping at the end
  - paused for credit and for a cap, then resume
  - cancel
  - CSV and zip download, including the too-large case
  - row error highlighting
  - 500 rows staying responsive (a timing bound in the test)
- Screenshots of the new-batch page, a running batch and a paused batch, in
  light and dark.
- All work committed on `feature/batches-app`; `main` untouched.

## Out of scope (do not start)

- Editing a batch after it starts.
- Google Sheets integration (brief 03's n8n template covers sheets).
- Scheduling.
- Batches from the gallery without saving the recipe first.
