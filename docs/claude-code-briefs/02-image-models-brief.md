# Brief 2 — Image Model Catalog & New Providers

**Branch:** `feature/image-catalog` · **Merges 2 of 4 (after text).** · **Read `00-COORDINATION.md` first.**

## Mission

Make Godance's image generation a first-class, broad catalog (the brief's whole thesis is "multimodal-first, not text-only"). Refresh the existing image providers and add the missing ones so `/v1/images/generations` covers the current leaders across OpenAI, ByteDance, Alibaba, Google, and Black Forest Labs.

## Scope

- **Add your catalog file only:** `gateway/catalog/ImageCatalog.java` (`@Component implements CatalogContributor`). Do **not** edit `ModelCatalog.java` (text instance establishes the contributor interface, which merges before you).
- **Refresh existing image adapters:** `gateway/providers/OpenAIImageProvider.java` (DALL·E 3 → `gpt-image-2`) and `BflProvider.java` (Flux 1.1 / Flux 2 — verify current ids).
- **Build new image `ProviderAdapter`s** (mirror `OpenAIImageProvider` / `BflProvider` as templates; image gen is async — set `requiresAsync=true` and route through the existing generation-job path):
  - **Volcengine Ark — Seedream** (the Volcengine config/key already exists for video; add the image call path).
  - **Alibaba DashScope — Qwen-Image** (shares the `alibaba`/dashscope config block with the text instance — reuse it).
  - **Google — Imagen 4 / Nano Banana** (Gemini image; shares the `google` config block).
  - **Stability — SDXL** (`STABILITY` enum already exists; no adapter yet).

## Curated launch catalog (image tier markup 10–15%; set `markupOverride` per model)

Prices are provider list, USD per image. Convert with `perImageMicrocents = USD × 1_000_000`. Some providers bill per output token, not per image — for those, price the typical 1024² generation and note it. **Verify before shipping** (sources + UNVERIFIED flags in `Godance-Model-Catalog-Research-2026-05.md`).

- `gpt-image-2` (OpenAI) — token-billed; ~$0.01–0.20/img by quality. Price the standard tier.
- `seedream-5.0` (Volcengine Ark) — ~$0.035/img
- `qwen-image-2.0` / `qwen-image-2.0-pro` (Alibaba) — ~$0.035 / ~$0.075
- `flux-2-pro` and `flux-1.1-pro` (BFL) — per-megapixel (~$0.03 first MP); price a 1MP image
- `nano-banana` (`gemini-3-pro-image` / Nano Banana Pro) (Google) — ~$0.134 (1K/2K)
- `imagen-4` (Google) — ~$0.02 fast / $0.04 std / $0.06 ultra
- `sdxl` (Stability) — verify current API price

Curate aggressively; drop anything you cannot price or call. (Midjourney has no official API — exclude.)

## Conventions

- Async job path: image generations return a `job_id` immediately; output to S3-compatible storage via the existing `ObjectStorageClient`; cost debited on success, refunded on failure (this plumbing already exists — reuse `generation_jobs` + the job service; do not rebuild it).
- New `@ConfigurationProperties` only for genuinely new providers; reuse shared `volcengine`/`alibaba`/`google` blocks.
- Image Chinese providers (Volcengine Seedream, Alibaba Qwen-Image) are already in `AlicloudProviderAllowlist` via `VOLCENGINE_ARK` / `ALIBABA_DASHSCOPE`. Google (Imagen/Nano Banana), OpenAI, and BFL are Western — **must not** be added to the allowlist.

## Tests

- WireMock cassette per new image provider (record real response shape; for async providers, cover the submit→poll→complete cycle).
- A job lifecycle test: submit → `job_id` in <500ms → poll → output URL; balance debited only on success.
- Pricing test: `GET /v1/models` returns each image model with `per_image` pricing populated and `requires_async=true`.

## Acceptance criteria

`mvn -q test` green. `GET /v1/models` lists all curated image models (IMAGE modality, per-image pricing). `POST /v1/images/generations` for any new model returns a job that completes with a stored, signed output URL and a correct debit. No edits to `ModelCatalog.java` or outside your declared file set.

## Out of scope

Text/video/audio, billing, frontend, self-hosting. UI/pricing-page is a later pass.
