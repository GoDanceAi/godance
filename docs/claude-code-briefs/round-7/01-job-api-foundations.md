# Brief 01 — Job API foundations

**Branch:** `feature/job-api-foundations` (worktree `../gd-job-api`) off `main` @ `bce9491`
**Reserved migrations:** V23 (job outputs), V24 (catalog metadata)
**Baseline:** full suite green on parent; record the exact count first

## Mission (one line)

Make a finished generation fully readable through the API, make each model
describe what it accepts, and make the price knowable before a job is submitted.

## Why now

Brief 02 builds the app's Studio and History pages on these. API customers
have the same gaps today:

- `GET /v1/jobs` never returns the prompt or parameters, although
  `generation_jobs.prompt_json` stores them (`web/dto/ImageDtos.java`, `JobView`).
- Only the first output is recorded on the job. A job with `n: 4` stores four
  files, and the other three have no saved reference
  (`ImageGenerationService.uploadAll`, `VideoGenerationService.uploadAll`).
- `output_url` is a presigned link fixed when the job finishes. It expires
  within `presign-ttl` (at most 7 days), nothing re-signs it, and the file is
  kept for 90 days (`DataRetentionService.JOB_RETENTION`).
- `GET /v1/jobs` returns every job the caller has ever made, unpaged.
- `/v1/models` says nothing about valid sizes, durations or output counts.
- A client cannot learn the cost of a request without submitting it.

A billing fix landed ahead of this round: a job is now charged for the
outputs the provider delivered, and a video request that omits `seconds` is
priced on the length its adapter generates. Item 5 finishes that work by
moving both rules into the model's published capabilities.

## Read first

1. This brief and `00-coordination.md` (the shared contract is binding)
2. `backend/CLAUDE.md`
3. Job path: `web/JobController.java`, `web/dto/ImageDtos.java`,
   `domain/GenerationJob.java`, `repository/GenerationJobRepository.java`,
   `service/GenerationJobSettlement.java`
4. Workers: `service/ImageGenerationService.java`, `service/VideoGenerationService.java`
5. Storage and retention: `storage/ObjectStorageClient.java`,
   `service/DataRetentionService.java`, `service/AccountClosureService.java`
6. Catalog: `gateway/ModelCatalog.java`, `domain/ModelCatalogEntry.java`,
   `web/ModelsController.java`, migration `V11__model_catalog.sql`
7. Money: `gateway/CostCalculator.java`, `service/CreditLedgerService.java`
8. Every image and video adapter in `gateway/providers/`

## Scope

### 1. Record every output (V23)

- New table `generation_job_outputs`: job id, index, storage key, content
  type, size in bytes, revised prompt (nullable), created time. Unique on
  (job, index).
- The rows are written in the same transaction that ends the job `DONE`.
  Extend `GenerationJobSettlement.complete` to take the outputs; keep its
  "ends exactly once" guarantee.
- Purge and account closure delete the rows with the media.
- Jobs finished before V23 have no rows. On first read of such a job, list
  its storage prefix and insert the rows. This needs a `list(prefix)` method
  on `ObjectStorageClient`. These jobs age out within 90 days.

### 2. Return usable job data

- `JobView` gains `prompt`, `params`, `outputs`, `purged` and
  `mediaExpiresAt`, exactly as in the coordination contract.
- Links are signed when the job is read. `outputUrl` becomes the first
  output, freshly signed.
- The completion webhook body does not change.
- A job stays owner-only: another user's id still answers 404.

### 3. Page and filter the list

- `GET /v1/jobs` accepts `limit`, `before`, `modality` and `status`.
- `before` is a job id. Order by `(created_at, id)`, as the purge query does.
  A `before` that is not the caller's job answers 400.
- The response stays a bare array. Without `limit`, behaviour is unchanged.

### 4. Describe each model (V24)

- Add `display_name`, `description` and `capabilities` (jsonb) to `model_catalog`.
- Seed them for every `ACTIVE` image and video model. Take the values from
  each adapter and the provider's current public documentation. Where a value
  is not certain, leave the field out: absent means unknown.
- Expose all three under `godance` in `GET /v1/models`, and in the admin
  catalog listing as read-only.
- `ModelInfo` is constructed in many places. Carrying the metadata in a
  separate record looked up by model name avoids touching all of them.
- `gateway/catalog/*Catalog.java` are no longer read by `ModelCatalog`. Do
  not add metadata there.
- A model approved from a pricing proposal arrives with no capabilities.
  That must be a valid state everywhere.

### 5. Publish the count and duration rules

Already in place before this round (do not redo; read it first):

- `GenerationJobSettlement.complete` charges for what was delivered and
  returns the rest of the hold in the same transaction. It refuses a charge
  above the hold.
- Each video adapter declares `defaultDurationSeconds()`.
  `VideoGenerationService.submit` prices a request on it and sends the
  resolved value to the adapter.

What this round adds:

- **Reject an unsupported count up front.** `BflProvider` and all six video
  adapters return one output whatever `n` is. Today such a request is
  accepted, holds the price of `n`, and is charged for one. Reject `n`
  greater than the model's `max_n` with a 400 before any charge. A model
  without `max_n` allows 1. Seed `max_n: 1` for the Flux models and every
  video model.
- **Publish the default length.** Seed each video model's `default_seconds`
  from its adapter's `defaultDurationSeconds()`, and add a test that fails
  when the two disagree for any `ACTIVE` video model.

Other capability fields (`sizes`, `durations`) stay advisory this round. Do
not reject requests on them.

### 6. Estimate endpoint

- `POST /v1/estimates`, as in the contract. Session or API key; add the rule
  in `SecurityConfig`.
- The figure must come from the same code that computes the pre-debit,
  including the 1-cent minimum and the default clip length. Extract one
  shared method that both generation services and this endpoint call.
- Unknown model: 404. Text model: 400. Errors are problem+json.

### 7. Document it (last commit)

Update `marketing/src/pages/docs/index.astro` and `marketing/public/llms.txt`
for the new job fields, list parameters, model metadata and estimate
endpoint. Nothing that is not live goes in.

## Hard rules

- Edits only in `backend/src/**`, migrations V23 and V24, and the two
  marketing files in item 7.
- Additions only on the wire. No existing field changes name, type or meaning.
- Money stays in integer cents and microcents. Follow the ledger's
  idempotency pattern.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes for providers. Do not mock the adapter interface.
- Do not touch `gateway/AlicloudProviderAllowlist.java`, `routing/`,
  `backend/deploy/` or `app/`.

## Opening move

Run the baseline. Read the delivered-count and default-length code named in
item 5 and its tests. Then write the failing test for the up-front count
check (`n: 2` on a Flux model answers 400 and writes no ledger row). Post a
3–5 bullet build plan, with the capability values you intend to seed and
where each came from, before writing production code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests.
- A job with `n: 2` on a model that supports it reads back two outputs, and
  each link downloads.
- A job read after its original link's lifetime still returns a working
  link (test with a short `presign-ttl`).
- A purged job reads back with `purged: true`, no prompt and no outputs.
- `POST /v1/estimates` equals the ledger pre-debit for the same request, for
  every seeded image and video model (parameterised test).
- Every `ACTIVE` video model's `default_seconds` equals its adapter's
  `defaultDurationSeconds()`.
- `n` above `max_n` answers 400 and writes no ledger row.
- `marketing/` builds.
- A curl transcript of each new or changed endpoint in the final summary.

## Out of scope (do not start)

Reference images, aspect ratio, seed or negative prompt. Thumbnails and
poster frames. Job cancellation. Any public or shared content. Changing the
90-day retention. Admin editing of capabilities. Rejecting requests on
`sizes` or `durations`. Capping the unpaged list default.
