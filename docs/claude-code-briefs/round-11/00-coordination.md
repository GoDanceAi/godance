# Round 11 — Coordination

**Drafted:** 2026-11-13 · **Parent:** `main` with round 10 merged
**Workstreams:**
- 01 Input files and policy (backend, ships disabled)
- 02 Images in chat (backend)
- 03 Image edits (backend)
- 04 Video inputs (backend)
- 05 Studio and recipes with inputs (backend, app)
- 06 Gallery recipes with inputs (marketing)

Context: today the API accepts text only. In the lists the gallery imports
from, 21–32% of image prompts and 31% of Seedance prompts need an uploaded
image, so round 8's import sets them aside. Inputs matter twice for the
people Godance is built for:
- **Adapting.** "My product" or "my character" in place of someone else's.
- **Cheaper iteration.** The standard video workflow settles a frame as an
  image, at about 6 ¢, and then animates it, at about 16 ¢ a second.

See `docs/research/2026-11-prompt-gallery-competitors.md` §4 and §5.5.

**Most input prompts are about a real person's likeness.** That is the most
popular use and the most sensitive. This round builds the mechanism with
the strictest policy on, behind a switch that defaults to off. The operator
decides when and how far to open it.

**Prerequisites.** Round 10 on `main`: origin and recipes, and image
responses per key.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-inputs       -b feature/input-files main
git worktree add ../gd-chat-vision  -b feature/chat-images main
# after 01 is merged:
git worktree add ../gd-edits        -b feature/image-edits main
git worktree add ../gd-video-inputs -b feature/video-inputs main
git worktree add ../gd-studio-in    -b feature/studio-inputs main
# any time after this file is merged:
git worktree add ../gd-gallery-in   -b feature/gallery-inputs main
```

03, 04 and 05 may start before 01 merges if they code against the contract
below, but they rebase onto 01 before merging.

## Reserved resources

| Resource | 01 | 02 | 03 | 04 | 05 | 06 |
|---|---|---|---|---|---|---|
| Flyway | **V31** | **V32** | **V33** | **V34** | **V35** | none |
| Backend | new `service/Input*`, `web/FileController`, `storage/` (metadata stripping), retention and closure | `gateway/ChatDtos`, `web/dto/ChatCompletionDtos`, `ChatCompletionService`, chat adapters, the person-check implementation | new `web/ImageEditController`, `gateway/ImageDtos`, image adapters, `CostCalculator` (one input price) | `gateway/VideoDtos`, `web/dto/VideoDtos`, video adapters | `service/Recipe*`, `JobView.inputs` | none |
| App | Settings (terms acceptance) | none | none | none | Studio, History, job, recipe pages | none |
| Marketing | `docs/index.astro` (last commit) | same | same | same | none | gallery schema, import tool, pages, fixtures |
| Config | `godance.inputs.*` | none | `godance.images.edits.*` | none | none | none |

Check V31–V35 are still free before using them.

## Shared contract

Additions only.

### Input files (01)

```
POST   /v1/files        multipart: file=<image>, purpose=generation_input
       → 201 InputFile
GET    /v1/files?limit=&before=      → [InputFile], newest first
GET    /v1/files/{id}                → InputFile
GET    /v1/files/{id}/content        → 302 to a signed URL (owner only)
DELETE /v1/files/{id}                → 204
POST   /v1/inputs/terms  {"version": 1}   → 204   (session only)
```

```json
{ "id": "…", "object": "file", "purpose": "generation_input",
  "bytes": 812345, "content_type": "image/png", "width": 1024, "height": 1024,
  "contains_person": false, "created_at": 1731500000, "expires_at": 1731586400 }
```

- **Who can upload.** Session or API key. The caller must:
  - be an `ADULT` account
  - have accepted the current input terms
  - have inputs switched on (`godance.inputs.enabled`)
- **What is accepted.** PNG, JPEG or WebP, identified by content rather than
  by the declared type. At most 20 MB and at most 40 megapixels. Metadata
  (EXIF, XMP, GPS) is stripped before the file is stored.
- **The person check.** `contains_person` is the result. It is null when no
  check ran. With `godance.inputs.allow-people` false, an image that contains
  a person is refused.
  - 01 defines the check as an interface. When no implementation is
    present, it fails closed.
  - 02 implements it with the configured vision chat model, since that is
    where images in chat arrive.
- **Retention.** A file no job or recipe uses is deleted after
  `godance.inputs.unused-ttl-hours`. Once used, it lives as long as the
  longest-lived job or recipe that uses it. `expires_at` is null while it is
  in use.
- **Errors (problem+json `code`):**
  - `inputs_disabled` (403), `inputs_terms_required` (403),
    `inputs_not_allowed_for_account` (403)
  - `input_type_unsupported` (415), `input_too_large` (413)
  - `input_person_not_allowed` (422), `input_check_unavailable` (503)
  - `file_not_found` (404; another user's file answers the same)

**Pointing at an input.** These two forms are binding for 03, 04, 05 and
round 12:

```json
{ "file_id": "…" }
{ "job_output": { "job_id": "…", "index": 0 } }
```

A `job_output` must be an output of one of the caller's own unpurged jobs.
It is Godance-generated, so the person check does not apply to it.

### Model capabilities

`godance.capabilities` in `/v1/models` gains `inputs`. Absent means none.

```json
"inputs": { "images": { "max": 4, "mask": true } }                       // image models (03), chat models (02)
"inputs": { "first_frame": true, "last_frame": false,
            "reference_images": { "max": 4 } }                           // video models (04)
```

### Images in chat (02)

- In `/v1/chat/completions`, a message's `content` may be a string, as
  today, or OpenAI's array of parts:
  - `{"type": "text", "text": "…"}`
  - `{"type": "image_url", "image_url": {"url": "…", "detail": "auto"}}`
- **`url` may be:**
  - a data URL
  - an `https` URL, fetched through the public-only client
  - `<api base>/v1/files/{id}/content` for one of the caller's own files,
    read from storage and never fetched over the network
- **Errors.** A model without `inputs.images` answers 400 with
  `model_does_not_accept_images`.

### Image edits (03)

`POST /v1/images/edits` accepts OpenAI's multipart form:
- `model`, `prompt`
- `image`, one or more (also as `image[]`)
- optional `mask`, `n`, `size`, `quality`, `response_format`

It also accepts a JSON body that names its inputs with the forms above:

```json
{ "model": "gpt-image-2", "prompt": "…",
  "images": [ { "file_id": "…" } ], "mask": { "file_id": "…" }, "n": 1, "size": "1024x1024" }
```

- **Multipart images** go through the same checks as `/v1/files` and are
  stored as input files.
- **The response** follows round 10's rule for the key: `202` with a job,
  or the synchronous body.

### Video inputs (04)

`POST /v1/videos/generations` accepts optional `first_frame`, `last_frame`
and `reference_images` (a list), each in the forms above. One the model does
not support answers 400 with `input_not_supported`.

### Jobs

`JobView` gains `inputs`:

```json
"inputs": [ { "role": "image", "file_id": "…", "url": "…signed…" },
            { "role": "first_frame", "job_output": { "job_id": "…", "index": 0 }, "url": "…" } ]
```

- `role` is one of `image`, `mask`, `first_frame`, `last_frame` or
  `reference_image`.
- `url` is null when the file is gone.
- After purge, `inputs` is empty.

### Recipe inputs (05)

- A recipe version gains `inputs`:
  `[{ "slot": "product photo", "role": "image", "hint": "One product on a plain background" }]`.
  The slot name follows the template's naming rule.
- Running a recipe accepts
  `"recipe": { …, "inputs": { "product photo": { "file_id": "…" } } }`.
- A slot without a value answers 400 with `recipe_input_missing`.

### Gallery recipes with inputs (06 writes them; 05 reads them)

`GET /gallery/<slug>.json` gains an optional top-level `inputs`:

```json
"inputs": [ { "slot": "product photo", "role": "image",
              "hint": "One product on a plain background",
              "sample_url": "https://<media base>/<slug>/input-0.webp" } ]
```

A sample input is generated on Godance. It is never taken from a source
list.

## Shared-file conflict watch

- **Retention and closure:** `service/DataRetentionService.java`,
  `service/AccountClosureService.java`. 01 only; others call 01's service.
- **Job inputs** are recorded through one 01 service method
  (`InputService.attach`, or similar). 03 and 04 call it and add no tables.
- **`JobView`:** 01 adds `inputs`. 05 adds nothing to it.
- **`config/SecurityConfig.java`:** 01 adds `/v1/files/**` to the API-key
  rule; 03 adds `/v1/images/edits`.
- **`service/AuditLogService.java`:** 01 adds the `input.*` events.
- **`app/src/lib/api.ts`, `app/e2e/mock-api.ts`:** 01 (the terms line in
  Settings) and 05.
- **`marketing/src/pages/docs/index.astro`:** 01–04, last commit only.

## Hard rules (all sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- Run the full suite on the parent commit and record the count first.
- `app/` lint, build and e2e stay green. `marketing/` lint, build and test
  stay green.
- **Fail closed.** With `godance.inputs.enabled` false, no input is
  accepted anywhere, and every new field answers `inputs_disabled`.
- **No URL a user supplies is fetched except through
  `WebhookDestinations`' public-only client,** with size and time limits.
- **An input is never public.** It is never shown in the gallery, and only
  its owner can read it.
- **The regional allowlist still decides where a request may go.** Inputs
  never route around it.
- Billing changes carry a test in the same change. Money in integer cents.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

1. **01 and 02**, in either order.
2. **03 and 04**, after 01, in either order.
3. **05**, after 01, 03 and 04. It codes against mocks until then.
4. **06** at any time. Its "Run this" with a sample input works once 05 is
   live.

## Operator decisions

- **The input terms:** the text and its version. Users must accept them
  before uploading. Draft them with whoever advises on privacy. They should
  say:
  - the user has the right to use the image
  - a person shown is the user, or has consented
  - no one under 18 is shown
  - uploads are deleted with the job
- **When to switch inputs on** (`godance.inputs.enabled`, default false).
- **Whether people may appear in uploads** (`godance.inputs.allow-people`,
  default false). Suggested stages:
  1. On, with people refused. This covers products and objects.
  2. Gallery recipes with AI-generated sample people (06's standard
     section).
  3. People allowed in uploads, for adult accounts that accepted the terms.
- **The model that runs the person check** (`godance.inputs.check-model`).
  - It must be a vision chat model allowed in the region it runs in.
  - With `allow-people` false and no check model, uploads answer
    `input_check_unavailable`.
- **How long an unused upload is kept.** Suggested: 24 hours.
- **The privacy policy** must say that uploaded images are stored, checked
  by a model, sent to the chosen provider, and deleted with the job or when
  unused.
- **Who may send images in chat.** 02 follows the same account rules as
  chat text. Decide whether chat images need the input terms too.
- **CORS for the gallery's media host.** It is needed for 06's "Run this"
  with a sample input.
