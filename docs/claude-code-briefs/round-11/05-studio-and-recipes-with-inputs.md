# Brief 05 — Studio and recipes with inputs

**Branch:** `feature/studio-inputs` (worktree `../gd-studio-in`) off `main` with briefs 01, 03 and 04 merged (develop against mocks before that)
**Reserved migration:** V35
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Let a user bring their own images into the Studio, animate an image result
in one step, describe an image as a prompt, and keep input slots in their
recipes. Everything is visible only while inputs are switched on.

## Why now

Briefs 01–04 make inputs possible through the API. The people Godance is
built for meet them in the Studio first. Two flows matter most:
- **"This, with my product":** a recipe whose input slot the user fills.
- **"Animate this":** the cheap path from a settled still to a video.

Recipes need input slots too, so round 12 can run a recipe over a sheet of
products.

## Read first

1. This brief and `00-coordination.md` (the input forms, `JobView.inputs`,
   the capability shape and the recipe input contract are binding; V35 is
   yours)
2. `backend/CLAUDE.md`
3. Round 10 brief 03's code: recipes, versions, rendering, and running a
   recipe
4. Briefs 01, 03 and 04 of this round: their routes and errors
5. App:
   - the Studio (rounds 7, 8 and 10): form, pre-fill order, template
     fields, submit, result
   - History and the job page, round 9's save control, round 10's recipe
     and compare pages
   - `/v1/auth/me` handling in `app/src/lib/auth.tsx`
   - `app/src/lib/api.ts`, `app/e2e/mock-api.ts`

## Scope — backend

### Recipe input slots (V35)

- `recipe_versions.inputs` (jsonb): the slots, as in the contract.
- **Slot rules:**
  - names follow the template naming rule and are unique per version
  - a slot's `role` must be one the recipe's modality uses (`image` for
    images; `first_frame`, `last_frame` or `reference_image` for video)
  - at most 4 slots
- **A new version** is written when slots change, as for any other field.
- **Running a recipe:**
  - `recipe.inputs` maps each slot to an input reference.
  - The server places each one by role into the request: as edit images
    (through brief 03's path) or as video inputs (brief 04's fields).
  - A slot with no value answers 400 with `recipe_input_missing`.
  - An image recipe with input slots runs as an edit, and one without runs
    as a generation. The server decides, from the slots.

## Scope — app

All of this is shown only when `/v1/auth/me` says inputs are enabled. When
the terms are not accepted, the first attempt to add an input opens the
terms (brief 01's Settings content) in a dialog, with Accept.

### Inputs in the Studio

- **Image slots.** When the chosen model has `inputs.images`, show image
  slots up to `max`:
  - drop or choose a file, upload it through `/v1/files`, and show a
    thumbnail
  - remove an input
  - a mask slot when `mask` is true
- **Video slots.** When the model has `first_frame`, `last_frame` or
  `reference_images`, show those slots. For each, choose a file or pick one
  of the user's image outputs (a small picker of recent image jobs).
- **Upload errors** use each error code's own message. In particular,
  `input_person_not_allowed` says that images of people are not accepted
  yet.
- **Submit.** With inputs, image requests go to `/v1/images/edits` as JSON
  with references, and video requests carry the fields. The price comes
  from the estimate with `input_images`.

### Animate this

- On a finished image result, in the Studio, History and the job page,
  "Animate this" opens the video Studio with that output as the first
  frame.
- **The model.** Use the cheapest video model with `first_frame`, unless
  the user has one chosen that supports it.
- A short line explains that settling the image first is cheaper than
  generating videos until one looks right.

### Describe as prompt

- On an input slot or an image result, "Describe as prompt" sends the image
  to the cheapest chat model with `inputs.images`, with a fixed instruction
  to write a prompt that would reproduce the image.
- It puts the answer into the prompt box, replacing it only after a
  confirmation.
- Show its estimated cost first. It is a normal chat call, charged as one.

### Recipes with slots

- **Save as recipe** saves the current inputs as slots. Each slot gets a
  name and a hint, editable. A slot never stores the user's file by
  default.
- **The recipe page** shows the slots. Opening it in the Studio shows the
  empty slots with their hints.
- **Attempts and compare** show each job's inputs as thumbnails.

### Gallery recipes with inputs

When a gallery recipe has input slots (brief 06), the Studio:
- shows the recipe's sample input in each slot, marked "Sample"
- lets the user replace it
- on submit, uploads any sample still in place through `/v1/files`, so it
  becomes the user's input

If the sample cannot be fetched, the slot is empty with the hint.

### History and the job page

They show inputs (`JobView.inputs`) beside the outputs. A deleted input
shows as such.

## Hard rules

- Edits only in `backend/src/**` (recipe slots), migration V35,
  `application.yml`, `app/src/**` and `app/e2e/**`.
- With inputs disabled, the app looks exactly as it did on the parent
  commit. Pin that with a test.
- **Loading never generates.** Neither does "Animate this" or picking a
  sample. Generation is always an explicit submit.
- **The app never sends a URL as an input.** It uploads or uses a
  `job_output`.
- Use the existing theme tokens and classes, in light and dark. Upload
  controls are keyboard-accessible and labelled.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and the e2e
  tests mock every endpoint.

## Opening move

Run the baselines. Read the Studio's submit path and round 10's recipe
code. Write the failing backend test first: running a recipe with an
`image` slot filled creates an edit job with that input. Then post a 3–5
bullet plan with the component split before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green on the
  desktop and mobile projects.
- **Backend tests cover:**
  - slot rules
  - a new version on slot change
  - each role placed correctly
  - `recipe_input_missing`
  - an image recipe with slots running as an edit, and one without as a
    generation
- **App tests cover:**
  - inputs hidden when disabled
  - the terms dialog
  - upload with each error code shown
  - image and mask slots by capability
  - video slots, including picking an output
  - Animate this from the Studio, History and the job page
  - Describe as prompt with its cost and the replace confirmation
  - save as recipe with slots, and opening it
  - a gallery recipe's sample uploaded on submit, and a missing sample
  - inputs shown in History, the job page and compare
- Screenshots of the Studio with image slots, with video slots, and after
  Animate this, in light and dark.
- All work committed on `feature/studio-inputs`; `main` untouched.

## Out of scope (do not start)

- A mask drawing tool. A mask is uploaded as a file.
- Cropping or editing inputs in the app.
- Reference video or audio.
- Inputs in the chat page.
- Batch runs (round 12).
