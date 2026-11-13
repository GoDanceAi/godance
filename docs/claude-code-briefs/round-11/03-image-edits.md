# Brief 03 — Image edits

**Branch:** `feature/image-edits` (worktree `../gd-edits`) off `main` with brief 01 merged
**Reserved migration:** V33 (capability and price seed)
**Baseline:** full backend suite green on parent; record the count first

## Mission (one line)

Serve `POST /v1/images/edits` in OpenAI's shape, plus a JSON form that
points at stored inputs, on every image model whose provider accepts input
images. Price the inputs, and keep round 10's per-key response rule.

## Why now

These are the prompts that start "Using the uploaded product as the exact
reference…" or "keep the same face…". They are a fifth to a third of what
the gallery's sources contain, and the main way an aspiring builder adapts a
recipe to their own product. OpenAI's SDK sends them as
`client.images.edit(image=…, prompt=…)`, which `/v1/images/edits` must
accept unchanged.

## Read first

1. This brief and `00-coordination.md` (the edits contract, the input forms
   and the capability shape are binding; V33 is yours)
2. `backend/CLAUDE.md`
3. `web/ImageController.java` and round 10 brief 04's code: the per-key
   `image_response` and the wait. Reuse it; do not copy it.
4. `service/ImageGenerationService.java`, `gateway/ImageDtos.java`,
   `gateway/ImageProviderAdapter.java`, `gateway/ImageProviderGateway.java`
5. Every image adapter in `gateway/providers/`, and each provider's current
   documentation for input images and masks
6. Brief 01's input service: resolving a reference, recording the use, and
   the upload checks
7. `gateway/CostCalculator.java`, `domain/ModelCatalogEntry.java`, the
   estimates endpoint (round 7)

## Scope

### 1. The route

- `POST /v1/images/edits` takes session or API key; add it to the API-key
  rule in `SecurityConfig`.
- **Multipart (OpenAI's form).** Fields `model`, `prompt`, `image` (one or
  more, also as `image[]`), and optional `mask`, `n`, `size`, `quality`,
  `response_format`. Each image or mask part goes through brief 01's upload
  checks and becomes an input file owned by the caller.
- **JSON.** As in the contract, with references resolved through brief 01.
- **Shared behaviour.** Both forms run the same job path as generations:
  pre-debit, lease, settlement, webhook, refund on failure. They answer by
  round 10's per-key rule.
- **Recording.** The job records its inputs, with roles `image` and `mask`,
  through brief 01.
- **Origin.** `gallery_recipe` and `recipe` (round 10) are accepted here as
  on generations.

### 2. Adapters

- `ImageRequest` gains a list of input images and an optional mask, as
  bytes plus content type.
- **For each adapter, confirm from the provider's documentation:**
  - whether it accepts input images, and how many
  - whether it accepts a mask
  - whether images go inline or by URL

  Implement those adapters. Seed `capabilities.inputs.images` (V33) only
  where confirmed, with the source cited in the migration comment.
- **Unsupported.** A model without the capability answers 400 with
  `input_not_supported` before any charge. So does a mask on a model
  without `mask`.
- **Upstream URLs.** When a provider needs a URL, give it a short-lived
  signed URL to the stored input. Never a public one.

### 3. Pricing inputs

Some providers charge for input images: as input tokens, or per reference
image. Godance prices images per output today.

- **Add `per_input_image_microcents` to the catalog** (V33), nullable,
  meaning 0. Seed it per model from the provider's documented price, at the
  largest documented size when it varies. Cite the source.
- **Apply it.** `CostCalculator` multiplies it by the number of input
  images, mask excluded unless the provider charges for it, and applies the
  model's markup. The estimates endpoint takes an optional `input_images`
  count and returns the same figure the pre-debit takes.
- **Admin.** The pricing admin shows the new field read-only. Proposals may
  set it.
- Add tests: the estimate equals the pre-debit with and without inputs, for
  every seeded model.

### 4. Document it (last commit)

An "Edit an image" section in `marketing/src/pages/docs/index.astro`:
- the multipart example with the OpenAI Python SDK
- the JSON form
- masks
- the input price
- which models accept inputs (link to `/models`)

## Hard rules

- Edits only in `backend/src/**`, migration V33, and the docs page in the
  last commit.
- `/v1/images/generations` is unchanged.
- **Inputs are subject to brief 01's policy** on both forms. Multipart is
  not a way around it.
- With inputs disabled, the route answers `inputs_disabled` and writes
  nothing.
- **The regional allowlist decides routing,** as for generations.
- Billing changes carry tests. Money in integer cents and microcents.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes.

## Opening move

Run the baseline. Post a table with, for each image model:
- input images supported and the maximum
- mask support
- inline or URL
- input pricing

Give each a documentation link. Write the failing test that a multipart
edit with one image to a cassette-backed model creates a job with one
`image` input. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests.
- **Tests cover:**
  - multipart with one and with several images, and with `image[]`
  - JSON with a `file_id` and with a `job_output`
  - a mask
  - unsupported input and mask refused before charge
  - policy refusals on multipart
  - inputs disabled
  - the input price in the estimate and the pre-debit
  - sync and async keys
  - a provider failure refunding in full
- The OpenAI Python SDK's `images.edit` against the local stack with a
  WireMock provider, shown in the final summary.
- All work committed on `feature/image-edits`; `main` untouched.

## Out of scope (do not start)

- Variations (`/v1/images/variations`).
- Inpainting tools in the app.
- Video inputs (04).
- The Studio (05).
- Face swap or identity tools of any kind.
