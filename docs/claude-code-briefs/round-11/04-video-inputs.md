# Brief 04 — Video inputs

**Branch:** `feature/video-inputs` (worktree `../gd-video-inputs`) off `main` with brief 01 merged
**Reserved migration:** V34 (capability seed, and input prices where they differ)
**Baseline:** full backend suite green on parent; record the count first

## Mission (one line)

Let a video generation start from a first frame, end on a last frame, or
follow reference images, on every video model whose provider supports it.
Any input can be an upload or an output of one of the caller's image jobs.

## Why now

- **Cost.** Settling a frame as an image costs about 6 ¢. Each
  10-second clip costs about 1.60 USD. The standard GPT Image 2 → Seedance
  2.0 workflow, in Chinese and in English tutorials, fixes the look in
  stills and then animates the chosen one.
- **Demand.** 31% of the Seedance prompts in the list round 8 imports from
  refer to `@Image1`-style inputs.

## Read first

1. This brief and `00-coordination.md` (the video input fields, the input
   forms and the capability shape are binding; V34 is yours)
2. `backend/CLAUDE.md`
3. `web/VideoController.java`, `web/dto/VideoDtos.java`,
   `gateway/VideoDtos.java`, `gateway/VideoProviderAdapter.java`,
   `service/VideoGenerationService.java`
4. Every video adapter in `gateway/providers/`, and each provider's current
   documentation for:
   - first frame
   - last frame
   - reference images (how many, and how they are referred to in the
     prompt, such as Seedance's `@Image1`)
   - accepted formats and aspect-ratio rules
   - whether image-to-video is priced differently
5. Brief 01's input service, and brief 03's input pricing column if it has
   merged

## Scope

### 1. The request

- `VideoGenerationRequest` gains optional `first_frame`, `last_frame` and
  `reference_images`, in the contract's input forms.
- **Ownership and recording.** References are resolved and owned through
  brief 01. Inputs are recorded with roles `first_frame`, `last_frame` and
  `reference_image`, in the order given.
- **Unsupported.** A field the model does not support answers 400 with
  `input_not_supported` before any charge. So does more reference images
  than its `max`.
- **Policy.** With inputs disabled, any of the fields answers
  `inputs_disabled`.
- A `job_output` input needs no upload. "Animate this" in the Studio
  (brief 05) uses it.

### 2. Adapters

- The domain `VideoRequest` gains the three inputs, as bytes plus content
  type.
- Implement each adapter where the provider supports it. Give the provider
  short-lived signed URLs when it needs URLs.
- **Seedance-style references.** Where a provider refers to reference
  images by position in the prompt, the request's order defines the
  positions. Document this in the docs page; do not rewrite the prompt.
- **Aspect ratio.** Where a provider requires the first frame's aspect ratio
  to match the output size, check it before any charge and answer 400 with
  `input_aspect_mismatch`, naming both. Do not crop silently.
- Seed `capabilities.inputs` (V34) only where confirmed, citing the source.

### 3. Pricing

- Most providers price image-to-video the same as text-to-video. Where one
  does not, seed `per_input_image_microcents` (brief 03's column) for that
  model, with the source cited.
- If 03 has not merged, add the column here instead, with the same name and
  meaning, and say so in the summary. The two migrations must not both add
  it.
- The estimate and the pre-debit agree, with and without inputs.

### 4. Document it (last commit)

A "Start a video from an image" section in
`marketing/src/pages/docs/index.astro`:
- first and last frame
- reference images and how positions work
- animating one of your image outputs with `job_output`
- which models support what

## Hard rules

- Edits only in `backend/src/**`, migration V34, and the docs page in the
  last commit.
- A request without the new fields is unchanged on the wire to every
  provider. Existing cassettes must still match.
- Inputs are subject to brief 01's policy.
- The regional allowlist decides routing.
- Billing changes carry tests.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes.

## Opening move

Run the baseline. Post a table with, for each video model:
- first frame, last frame and reference image support, with the maximum
- aspect rules
- pricing differences

Give each a documentation link. Write the failing test that a Seedance
request with a `first_frame` given as a `job_output` reaches the cassette
with the frame. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests. Existing cassettes
  unchanged.
- **Tests cover:**
  - each field on a supporting model
  - each refused on a model without it
  - too many reference images
  - an aspect mismatch refused before charge
  - a `job_output` from another user's job answering 404
  - a purged job's output refused
  - inputs disabled
  - the estimate equal to the pre-debit where prices differ
  - a provider failure refunding in full
- A curl transcript of a first-frame request in the final summary.
- All work committed on `feature/video-inputs`; `main` untouched.

## Out of scope (do not start)

- Reference video or audio clips.
- Extending or looping a clip.
- Lip-sync.
- OpenAI's `/v1/videos` shape (round 12).
- The Studio (05).
