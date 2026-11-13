# Brief 04 — OpenAI's video shape and docs for AI assistants

**Branch:** `feature/openai-videos` (worktree `../gd-video-oai`) off `main` with round 11 merged
**Reserved migration:** V37, only if a column is needed; say so if unused
**Baseline:** full backend suite green on parent; `npm ci && npm run lint && npm run build && npm test` green in `marketing/`

## Mission (one line)

Serve OpenAI's video routes as a second view of Godance video jobs, publish
a complete OpenAPI file that cannot drift from the code, and give AI
assistants one file that tells them everything they need to write working
code.

## Why now

The code an AI assistant writes for video uses OpenAI's SDK:

```python
video = client.videos.create_and_poll(model="sora-2", prompt="…", seconds="8")
client.videos.download_content(video.id, variant="video").write_to_file("out.mp4")
```

Godance serves `/v1/videos/generations` and `/v1/jobs/{id}`, so that code
fails. `backend/CLAUDE.md` makes OpenAI SDKs working as drop-in clients a
hard constraint, and `docs/openai-dropin-verification.md` checks chat only.

Docs are the other half. `marketing/public/llms.txt` is a summary.
Assistants do better with one complete file of request and response
examples, and tools do better with an OpenAPI description.

## Read first

1. This brief and `00-coordination.md` (the video routes are binding)
2. `backend/CLAUDE.md` (hard constraint 5)
3. `web/VideoController.java`, `web/dto/VideoDtos.java`,
   `service/VideoGenerationService.java`, `web/JobController.java`,
   round 7's job outputs, and round 11's video inputs and input files
4. Every `@RestController` in `web/`, to build the OpenAPI file
5. `marketing/public/llms.txt`, `marketing/src/pages/docs/index.astro`,
   `docs/openai-dropin-verification.md`
6. **OpenAI's current Videos API reference:**
   - request fields and their types (`seconds` is a string there)
   - the video object
   - status values
   - `content` variants
   - list paging
   - errors
   - what the SDK's `create_and_poll` and `download_content` expect,
     including whether they follow redirects

## Scope — backend

### 1. `/v1/videos`

- **Create.** Accept JSON and multipart. Map `model`, `prompt`, `seconds`
  (string or number), `size` and `input_reference` onto the existing video
  path.
  - `input_reference` becomes a first-frame input through round 11's
    policy and input service.
  - Then create the job exactly as `/v1/videos/generations` does: same
    pre-debit, origin, `gallery_recipe` and `recipe` extensions.
- **The video object.** Answer in OpenAI's shape, mapped from the job:
  - **id:** the job's id
  - **status:** `QUEUED` → `queued`, `RUNNING` → `in_progress`, `DONE` →
    `completed`, `FAILED` or `CANCELLED` → `failed`
  - **other fields:** `progress` where known, else 0 or 100; `created_at`;
    `model`; `seconds`; `size`; `error` on failure
  - **extensions:** `cost_cents` and `job_url` (Godance extensions)
- **Retrieve and list.** Owner only, with paging as OpenAI pages.
- **`content`:**
  - `variant=video` streams the first output from storage, with its content
    type.
  - `thumbnail` streams a poster when one exists, otherwise 404.
  - Stream the bytes rather than redirect, unless the SDK reliably follows
    redirects; state which, with evidence.
- **Delete** purges the job's files early, exactly as the retention purge
  does for one job, and answers OpenAI's deleted shape. It does not refund
  anything.
- **Not supported:** remix and anything else OpenAI offers that Godance
  cannot do answer 404 or 400 with a clear `code`. They are not silently
  ignored.
- **Errors.** Keep problem+json. Check that the SDK raises a readable error
  from it, and add OpenAI's `error` object alongside if it does not.

### 2. The OpenAPI file

- **Contents.** `backend/src/main/resources/static/openapi.yaml` (OpenAPI
  3.1), served at `/v1/openapi.yaml` publicly. It describes every public
  route: request and response schemas, the Godance extensions, error codes
  and both auth methods.
- **Write it by hand,** unless a generator works cleanly with Spring Boot
  4.1 WebFlux. Check, and say which you chose and why.
- **The drift test.** A test reads every `@RequestMapping` and verb mapping
  in `web/` (admin routes excluded) and fails when a route is missing from
  the file, or the file has one the code lacks.

### 3. Drop-in verification

Extend `docs/openai-dropin-verification.md`:
- the OpenAI Python SDK's `videos.create_and_poll` and `download_content`
  against Godance
- the image steps round 10 added

Run them against the local stack with WireMock providers, and leave the
production "observed" column for the operator.

## Scope — marketing

- **`marketing/public/llms-full.txt`:** complete and example-led, in this
  order:
  1. authentication
  2. models and capabilities
  3. estimates
  4. chat (with images)
  5. images (sync and async keys)
  6. edits
  7. files and inputs
  8. video (both shapes)
  9. jobs
  10. recipes
  11. batches
  12. errors
  13. limits and retention
  14. the regional note

  Each section has one runnable curl example and one Python example with
  real field names. Link it from `llms.txt`.
- **`llms.txt`:**
  - add the lines briefs 01 and 03 handed over
  - point to `llms-full.txt` and `/v1/openapi.yaml`
  - replace "not an end-user product … targets engineering teams" with
    wording for people building with AI whether or not they are engineers
    yet. The research note's §5.13 has a draft. Leave the final words to the
    operator, marked in the summary.
- **Docs page:** a "Video (OpenAI shape)" section, and an "Ask your AI
  assistant" section linking `llms-full.txt`.
- **The consistency check.** A `npm test` check that every route named in
  `llms-full.txt` exists in the backend's `openapi.yaml`. Read the file from
  the repository path.

## Hard rules

- Edits only in `backend/src/**`, migration V37 if needed, `marketing/public/llms*.txt`,
  `marketing/src/pages/docs/index.astro`, `marketing/scripts/**` (the
  check), and `docs/openai-dropin-verification.md`.
- **`/v1/videos/generations` and `/v1/jobs` are unchanged.**
- **No second store for videos.** A video is a job.
- **Delete never refunds,** and never touches another user's job.
- Nothing documented that is not live.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes.

## Opening move

Run the baselines. Post the OpenAI video object and request as the current
reference defines them, with the link, and your mapping table to Godance
jobs. Write the failing test: `POST /v1/videos` with `seconds: "8"` creates
a job and answers a video object with `status: "queued"`. Then post a 3–5
bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests, including the drift test.
- **Tests cover:**
  - create from JSON and from multipart with `input_reference`, under
    policy
  - each status mapping
  - retrieve and list owner-only
  - content for video and thumbnail
  - delete purging files without a refund
  - unsupported routes answering clearly
- The OpenAI Python SDK's `create_and_poll` and `download_content` against
  the local stack, shown in the final summary.
- Marketing lint, build and test green, including the consistency check.
- All work committed on `feature/openai-videos`; `main` untouched.

## Out of scope (do not start)

- Remix.
- OpenAI's `/v1/batches`.
- Audio routes.
- A hosted docs portal.
- SDKs of Godance's own.
