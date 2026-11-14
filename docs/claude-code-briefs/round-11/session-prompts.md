# Round 11 — Opening prompts (paste as the first message of each session)

Start only after round 10 is merged to `main`.

| When | Sessions |
|---|---|
| At the start, at the same time | A and B |
| Once this round's coordination file is on `main` | F, at any time |
| After A merges | C and D, at the same time |
| After A, C and D merge | E. It may start earlier against mocks. |

## Session A — Input files and policy

```
You're a Claude Code session working on Godance round-11 brief 01 — Input
files and policy: adult accounts that accepted the input terms can upload
images for generation; files are private, stripped of metadata, kept only
while used, and refused when the policy says so. Everything is off by
default.

Setup: working dir is a dedicated git worktree on branch
feature/input-files, off main with round 10 merged. Confirm the backend
test count with a full run, and that lint, build and e2e are green in
app/, before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-11/01-input-files-and-policy.md (the brief)
2. docs/claude-code-briefs/round-11/00-coordination.md (the file contract,
   input forms and JobView.inputs are binding; V31 is yours)
3. backend/CLAUDE.md
4. storage/*, service/DataRetentionService.java,
   service/AccountClosureService.java, repository/GenerationJobRepository.java
5. domain/User.java and AgeStatus, security/RateLimiter.java,
   service/WebhookDestinations.java

Hard rules:
- Commit to feature/input-files as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- Fail closed: with godance.inputs.enabled false, every existing route is
  unchanged and nothing is accepted.
- Metadata is stripped before storage. No image is ever logged or audited.
- Account closure deletes every file. Retention keeps a file exactly as
  long as something uses it.

Opening move: run the baselines. Read the purge and closure paths. Write the
failing tests: an upload with inputs disabled answers inputs_disabled, and
a JPEG with GPS EXIF is stored without it. Then summarize your build plan in
3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), app lint, build and e2e
green, every acceptance case covered by a test, a curl transcript of each
route, all commits on your branch, main untouched. Hand back.
```

## Session B — Images in chat

```
You're a Claude Code session working on Godance round-11 brief 02 — Images
in chat: accept OpenAI's image parts in /v1/chat/completions for models that
can see, bill them correctly, and implement brief 01's person check with
the configured vision model.

Setup: working dir is a dedicated git worktree on branch feature/chat-images,
off main with round 10 merged. Confirm the backend test count with a full
run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-11/02-images-in-chat.md (the brief)
2. docs/claude-code-briefs/round-11/00-coordination.md (chat parts, the
   file URL form and capabilities are binding; V32 is yours)
3. backend/CLAUDE.md
4. web/dto/ChatCompletionDtos.java, gateway/ChatDtos.java,
   service/ChatCompletionService.java, every chat adapter
5. gateway/CostCalculator.java, service/WebhookDestinations.java
6. Each provider's documentation for image input and image token counting

Hard rules:
- Commit to feature/chat-images as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- String content is unchanged on the wire; every existing cassette matches.
- User URLs only through the public-only client, with limits.
- The pre-debit never under-holds for images.
- The person check fails closed on anything but an unambiguous answer.

Opening move: run the baseline. Post, per chat model, documented image
support and token counting with links. Write the failing test for one
data-URL image reaching a cassette in the provider's format. Then summarize
your build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), every acceptance case covered
by a test, a curl transcript with an image part, all commits on your branch,
main untouched. Hand back.
```

## Session C — Image edits

```
You're a Claude Code session working on Godance round-11 brief 03 — Image
edits: POST /v1/images/edits in OpenAI's multipart shape and as JSON with
stored inputs, on every image model whose provider takes input images, with
input pricing and round 10's per-key response rule.

Setup: working dir is a dedicated git worktree on branch feature/image-edits,
off main with brief 01 merged. Confirm the backend test count with a full
run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-11/03-image-edits.md (the brief)
2. docs/claude-code-briefs/round-11/00-coordination.md (edits contract,
   input forms, capabilities; V33 is yours)
3. backend/CLAUDE.md
4. web/ImageController.java and round 10 brief 04's per-key response code
5. service/ImageGenerationService.java, gateway/ImageDtos.java, the image
   adapters and each provider's documentation for input images and masks
6. Brief 01's input service; gateway/CostCalculator.java

Hard rules:
- Commit to feature/image-edits as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- Multipart inputs pass brief 01's policy exactly like /v1/files.
- /v1/images/generations is unchanged.
- The estimate equals the pre-debit with inputs, for every seeded model.

Opening move: run the baseline. Post a table per image model (inputs, max,
mask, inline or URL, input pricing) with links. Write the failing test for a
multipart edit creating a job with one image input. Then summarize your
build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), every acceptance case covered
by a test, an OpenAI SDK images.edit run against the local stack, all
commits on your branch, main untouched. Hand back.
```

## Session D — Video inputs

```
You're a Claude Code session working on Godance round-11 brief 04 — Video
inputs: first frame, last frame and reference images on video generations,
from uploads or the caller's own image outputs, on every video model whose
provider supports them.

Setup: working dir is a dedicated git worktree on branch
feature/video-inputs, off main with brief 01 merged. Confirm the backend
test count with a full run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-11/04-video-inputs.md (the brief)
2. docs/claude-code-briefs/round-11/00-coordination.md (video input fields,
   input forms, capabilities; V34 is yours)
3. backend/CLAUDE.md
4. web/VideoController.java, both VideoDtos, VideoProviderAdapter,
   service/VideoGenerationService.java, every video adapter and each
   provider's documentation
5. Brief 01's input service; brief 03's input price column if merged

Hard rules:
- Commit to feature/video-inputs as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- A request without the new fields is unchanged on the wire.
- Aspect mismatches are refused before charge, never cropped silently.
- Only one migration may add per_input_image_microcents.

Opening move: run the baseline. Post a table per video model (first, last,
reference images and max, aspect rules, pricing differences) with links.
Write the failing test for a Seedance first_frame given as a job_output.
Then summarize your build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), every acceptance case covered
by a test, a curl transcript, all commits on your branch, main untouched.
Hand back.
```

## Session E — Studio and recipes with inputs

```
You're a Claude Code session working on Godance round-11 brief 05 — Studio
and recipes with inputs: inputs in the Studio, Animate this, Describe as
prompt, input slots in recipes, and gallery samples. Everything is hidden
while inputs are switched off.

Setup: working dir is a dedicated git worktree on branch
feature/studio-inputs, off main with briefs 01, 03 and 04 merged (start
earlier against mocks if asked). Confirm the backend test count with a full
run, and that lint, build and e2e are green in app/, before changing
anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-11/05-studio-and-recipes-with-inputs.md
2. docs/claude-code-briefs/round-11/00-coordination.md (V35 is yours)
3. backend/CLAUDE.md
4. Round 10 brief 03's recipe code; briefs 01, 03 and 04's routes and errors
5. The Studio, History, job, recipe and compare pages; app/src/lib/auth.tsx,
   app/src/lib/api.ts, app/e2e/mock-api.ts

Hard rules:
- Commit to feature/studio-inputs as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- With inputs disabled the app looks exactly as on the parent commit.
- Nothing generates without an explicit submit. The app never sends a URL
  as an input.

Opening move: run the baselines. Write the failing backend test: running a
recipe with an image slot filled creates an edit job with that input. Then
summarize your build plan in 3–5 bullets with the component split before
writing code.

Done = mvn -B verify green (baseline + yours), app lint, build and e2e green
on desktop and mobile, every acceptance case covered, screenshots in the
summary, all commits on your branch, main untouched. Hand back.
```

## Session F — Gallery recipes with inputs

```
You're a Claude Code session working on Godance round-11 brief 06 — Gallery
recipes with inputs: stop setting aside input recipes, give each slots and a
sample input generated on Godance, and show input and output side by side.
Person recipes wait for the operator.

Setup: working dir is a dedicated git worktree on branch
feature/gallery-inputs, off main with this round's coordination file merged.
Confirm `npm ci && npm run lint && npm run build && npm test` is green in
marketing/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-11/06-gallery-recipes-with-inputs.md
2. docs/claude-code-briefs/round-11/00-coordination.md (the gallery JSON
   addition is binding)
3. marketing/src/lib/gallery-schema.js and marketing/scripts/**
4. docs/gallery-content-standard.md, the gallery pages and fixtures

Hard rules:
- Commit to feature/gallery-inputs as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Every paid stage is a dry run by default and needs --max-spend-cents.
- No list image is ever used. Samples are generated on Godance.
- No person recipe is screened in or published under the current standard.
- Nothing is published unreviewed.

Opening move: run the baseline. Count the entries screen sets aside for
inputs at the pinned commits, split into object and person. Write the
failing schema tests. Then summarize your build plan in 3–5 bullets before
writing code.

Done = marketing lint, build and test green, every acceptance case covered,
counts, a dry-run plan and screenshots in the summary, all commits on your
branch, main untouched. Hand back.
```
