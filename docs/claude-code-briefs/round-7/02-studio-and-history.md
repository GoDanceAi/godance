# Brief 02 — Studio and History (app)

**Branch:** `feature/studio-history` (worktree `../gd-studio`) off `main` @ `bce9491`
**Reserved migration:** none. This brief has no backend changes.
**Baseline:** `npm run lint`, `npm run build` and `npm run test:e2e` green in `app/` on parent

## Mission (one line)

Let a signed-in user generate images and videos in the app, see the exact
price before running, and find every past generation again.

## Why now

The catalog has image and video models, and the app can reach none of them:
the only generation page is text chat (`app/src/pages/ChatPage.tsx`), and the
dashboard still says "Try any of 7 text models". A new account has to write
code before it can see what the API produces, and early testers who were
given only an API key did not know what to do next.

The Studio is a playground for the public API, not a separate product. Every
action is a call a customer could make with an API key, and the page shows
that call.

## Read first

1. This brief and `00-coordination.md`. The shared contract is what you code
   against; brief 01 builds it in parallel, so develop against mocks.
2. `app/src/App.tsx`, `app/src/components/Layout.tsx`, `app/src/components/ProtectedRoute.tsx`
3. `app/src/pages/ChatPage.tsx` — model loading from `/v1/models`, the
   `sendError` out-of-credit handling, balance refresh after a call
4. `app/src/lib/api.ts`, `app/src/lib/auth.tsx` (`formatCents`, `refresh`)
5. `app/src/pages/BillingPage.tsx` and `app/src/index.css` for page style and
   the shared classes (`btn`, `card`, `input`, `pill`)
6. `app/e2e/mock-api.ts` and `app/e2e/chat.spec.ts` for the test pattern

## Scope

### Routes and navigation

- `/create/image`, `/create/video`, `/history`, `/history/:jobId`. `/create`
  redirects to `/create/image`.
- Navigation becomes Dashboard · Chat · Create · History · API keys ·
  Billing · Settings. It must still fit on the mobile layout.

### Studio (one component, two modalities)

- **Form:** prompt, model, and the controls the chosen model supports.
  - Model picker lists the modality's models from `/v1/models`, grouped by
    provider, each with its price per image or per second including the fee.
    Use `display_name` when present, the id otherwise. Default to the
    cheapest model.
  - Size and duration come from `capabilities`. Count is offered only when
    `max_n` is above 1.
  - With no `capabilities`, show the prompt and model only, and send no
    size, count or duration.
- **Price:** call `POST /v1/estimates` when the model or a control changes
  (debounced) and show the result on the Generate button, formatted with
  `formatCents`. While it loads or fails, the button shows no price and
  still works.
- **Submit:** `POST /v1/images/generations` with `async: true`, or
  `POST /v1/videos/generations`. Then poll `GET /v1/jobs/{id}`: every 2
  seconds for the first 30, then every 5. Stop on `DONE`, `FAILED` or
  `CANCELLED`, and on unmount.
- **While it runs:** show the status. For video, say it usually takes 1–5
  minutes and that the result will also be in History, so leaving is safe.
- **Result:** every output (image grid or video player), with Download and
  Copy prompt. If `outputs` is absent, fall back to `outputUrl`.
- **Failure:** show `errorMessage` and say the charge was returned. A failed
  job is refunded in full by the backend.
- **Out of credit:** reuse the chat's 402 handling. Move `sendError` out of
  `ChatPage.tsx` into a shared module instead of copying it.
- **Balance:** refresh after submitting and again when the job ends.
- **View code:** a panel with the curl for this exact request and the curl
  that polls the job. Use `$GODANCE_API_KEY` as the placeholder and link to
  `/keys`. Never render a real key.

### Linkable state

- `?model=&prompt=&size=&n=&seconds=` pre-fills the form.
- `?job=<id>` pre-fills from one of the caller's own jobs.
- Reloading the page while a job runs resumes polling it.

### History

- `/history`: newest first, `limit=24`, "Load more" using `before`. Filter by
  modality. Jobs still running keep polling.
- Each card: first output (lazy-loaded; video shows a frame via
  `preload="metadata"`), the prompt truncated, model, cost, age and a status pill.
- `/history/:jobId`: all outputs, full prompt, parameters, model, cost and
  time, with Copy prompt, Run again, Open in Studio and Download.
- Show the date the files will be deleted (`mediaExpiresAt`). When it is
  null, show no date.
- A purged job shows that its files were deleted, with the cost and date kept.
- Empty state links to the Studio.

### Dashboard

- Replace the "7 text models" card with Create image and Create video.
- Add a strip of the four most recent generations, hidden when there are none.

### Types

Add `JobView`, `JobOutput`, `ModelCapabilities` and `Estimate` to
`app/src/lib/api.ts`, mirroring the contract. `capabilities`, `display_name`,
`description` and `outputs` are all optional.

## Hard rules

- Edits only in `app/src/**` and `app/e2e/**`.
- No new runtime dependencies without flagging it first.
- Use the existing theme tokens and classes. Both light and dark must work.
- Controls are labelled, and job status changes are announced (`aria-live`).
- No calls to endpoints outside the contract. If the contract is missing
  something you need, stop and raise it; do not invent a field.

## Opening move

Run the baseline. Read `ChatPage.tsx` and `mock-api.ts` end to end. Then post
a 3–5 bullet build plan with the component split and the list of mocked
endpoints, before writing code.

## Acceptance criteria

- `npm run lint`, `npm run build` and `npm run test:e2e` green, on desktop
  and mobile projects.
- New specs `app/e2e/create.spec.ts` and `app/e2e/history.spec.ts` cover:
  - image run: price shown, submit, `QUEUED` → `RUNNING` → `DONE`, outputs rendered
  - two outputs render for `n: 2`; the count control is absent when `max_n` is 1
  - a model with no `capabilities` sends only `model` and `prompt`
  - video run with a duration control
  - failed job shows the refund note and refreshes the balance
  - out-of-credit 402 links to Billing
  - `?job=` pre-fill and reload-resumes-polling
  - history paging, modality filter, purged job, empty state
  - fallback to `outputUrl` when `outputs` is absent
- Screenshots of the Studio (both modalities), History and the job page in
  light and dark in the final summary.
- All work committed on `feature/studio-history`; `main` untouched.

## Out of scope (do not start)

An examples gallery. Reference images. Favourites. Model presets. Saving
chat conversations. Carrying a destination through signup. Prompt
rewriting. Translations. Any backend change.
