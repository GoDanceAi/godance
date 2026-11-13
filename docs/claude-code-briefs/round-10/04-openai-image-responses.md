# Brief 04 — Image responses in OpenAI's shape

**Branch:** `feature/openai-image-responses` (worktree `../gd-image-sync`) off `main` with rounds 7–9 merged
**Reserved migration:** V30
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Let an API key choose to receive image results in OpenAI's response shape,
so standard OpenAI SDK code works unchanged. It ships with every key on
today's behaviour.

## Why now

Every image model is seeded `requires_async = TRUE`
(`V11__model_catalog.sql`). `ImageController` therefore answers every image
request with a `202` and a job id. Standard OpenAI SDK code does not expect
that:

```python
result = client.images.generate(model="gpt-image-2", prompt="…")
image_url = result.data[0].url
```

Experienced developers read the docs and poll. The people Godance is built
for often have an AI assistant write the code. The assistant writes the
standard code from memory, and it fails on the first run. This is the first
wall between trying things in the Studio and using the API.

## Read first

1. This brief and `00-coordination.md` (the image-response contract is binding; V30 is yours)
2. `backend/CLAUDE.md`, especially hard constraint 5 (OpenAI SDKs work as
   drop-in clients)
3. `web/ImageController.java`. Note the two paths: `execute` (synchronous)
   and `executeAsync` (job). `web/dto/ImageDtos.java` holds both response
   shapes.
4. `service/ImageGenerationService.java`, `service/GenerationJobSettlement.java`,
   `service/GenerationJobSweepJob.java` (how a job is leased, finished,
   failed and recovered)
5. `domain/ApiKey.java`, `service/ApiKeyService.java`, `web/ApiKeyController.java`,
   `web/dto/ApiKeyDtos.java`
6. `docs/openai-dropin-verification.md`
7. `backend/deploy/Caddyfile` (proxy timeouts)
8. App: `app/src/pages/ApiKeysPage.tsx`, `app/e2e/keys.spec.ts`, `app/e2e/mock-api.ts`
9. OpenAI's current Images API reference. In particular, check which models
   return `b64_json` rather than `url`, and how `response_format` behaves
   for them.

## Scope — backend

### Data (V30)

`api_keys.image_response`: text, not null. Existing rows are `ASYNC`.

### Choosing per key

- `POST /v1/keys` accepts optional `image_response`. When it is absent,
  use `godance.images.new-key-response`, which defaults to `async`. With
  that default, a fresh deployment behaves exactly as today.
- `PATCH /v1/keys/{id}` with `{ "image_response": "…" }`. Owner only,
  session only, like the other key routes.
- `ApiKeySummary` gains `image_response`.
- **Audit event:** `api_key.updated`, with the field and its new value.

### Waiting for the job

When the contract's conditions hold, `ImageController` creates the job as
today: same pre-debit, lease, settlement, webhook and refund on failure.
Then it waits for the job to end, up to `godance.images.sync-wait`
(default 90 s; refuse a configuration above 300 s).

- **The wait must not hold a thread.** Poll the job row on a timer, at
  about one-second intervals, or complete from a signal that the settlement
  raises.
  - Polling is the safe baseline: the job may finish on another instance
    after a lease recovery.
  - If you use a signal, keep the polling as a fallback.
- **Done:** answer `200` with `ImageGenerationResponse` built from the job's
  outputs, as round 7 records them, with freshly signed URLs.
- **`response_format`:**
  - Accept `response_format` (`url` or `b64_json`) on the request, as
    OpenAI does.
  - For `b64_json`, read each stored output and encode it.
  - Where OpenAI's own API returns `b64_json` for a model by default,
    return it for that model's Godance id when `response_format` is
    absent. Do the same for the other models in the same family. Cite the
    OpenAI documentation you followed in a comment.
  - A job's outputs are only a few megabytes. Refuse to inline more than
    `godance.images.max-inline-bytes` (default 25 MB) and answer with URLs
    instead.
- **Failed:** problem+json `502`, `code: "generation_failed"`, carrying the
  job's error message. The refund is the settlement's, unchanged.
- **Timeout:** `202` with `AsyncJobAcknowledged` and `Retry-After: 5`. The
  job carries on, and polling and webhooks work as today.
- **The client disconnects:** stop waiting. The job carries on.
- **Counters:** count waits that ended done, failed or timed out, in the
  existing observability pattern.

### What does not change

- Session requests: the Studio sends `async: true`.
- Any request with `async: true` or `Prefer: respond-async`.
- Video. OpenAI's video API is a different shape; round 12 handles it.
- What a job costs.

### Document it (last commit)

- `marketing/src/pages/docs/index.astro`: per-key image responses, the
  wait, what a timeout returns, and `response_format`. Include the Python
  snippet above as code that works with a `sync` key.
- `marketing/public/llms.txt`: one paragraph. AI assistants read this file.
- `docs/openai-dropin-verification.md`: add an image section to the
  procedure.
  - The OpenAI Python SDK pointed at Godance with a `sync` key, using
    `images.generate` and reading `data[0].url`, and `b64_json` where it
    applies.
  - The same with an `async` key, showing a `202`.
  - Leave the "observed" column for the operator to fill after deploy.

## Scope — app

- **API keys page:** each key shows its image response setting, with a
  control to change it. The create form offers the choice, defaulting to
  the server's default as read from the created key.
- **Help text.** One sentence: "Synchronous: image requests wait for the
  result, as OpenAI's API does. Asynchronous: they return a job to poll."

## Hard rules

- Edits only in `backend/src/**`, migration V30, `application.yml`,
  `app/src/**`, `app/e2e/**`, and the docs files named above in the last
  commit.
- With `new-key-response: async` and no key changed, behaviour is identical
  to the parent commit. Pin that with a test.
- No blocking call on a Netty event-loop thread.
- Billing, settlement and refund code paths are reused, not copied.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes for providers.

## Opening move

Run the baselines. Read `executeAsync` through to settlement and state, with
file and line, where a job becomes `DONE` and where it becomes `FAILED`.
Write the failing test first: with a `sync` key, `POST
/v1/images/generations` on a cassette-backed model answers `200` with
`data[0].url`. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green.
- **Backend tests cover:**
  - an `async` key unchanged
  - a session unchanged
  - a `sync` key: done, failed with refund, and timeout returning `202` then
    finishing normally
  - `async: true` and `Prefer: respond-async` each overriding a `sync` key
  - `b64_json` on request, by default where OpenAI does so, and above the
    inline limit falling back to URLs
  - the client disconnecting mid-wait
  - `PATCH` owner-only and session-only
  - the configuration above 300 s refused
- A run of the OpenAI Python SDK against the local stack, with a WireMock
  provider, reading `data[0].url`, shown in the final summary.
- All work committed on `feature/openai-image-responses`; `main` untouched.

## Out of scope (do not start)

- Video in OpenAI's shape (round 12).
- `/v1/images/edits` (round 11).
- Streaming partial images.
- Changing the default for existing keys.
- Spending caps on keys (round 12).
