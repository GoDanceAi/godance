# Brief 3 — Video Model Catalog & New Providers (API tier)

**Branch:** `feature/video-catalog` · **Merges 3 of 4 (after image).** · **Read `00-COORDINATION.md` first.**

## Mission

Video is Godance's lead differentiator (the name nods to Seedance; competitors B.AI and WorldClaw are text-only). Build out `/v1/videos/generations` into the strongest catalog in the category — **via provider APIs only**. Self-hosting open models on owned GPUs is **deferred** (see `Godance-Video-SelfHost-vs-API-Analysis.md`); do not add any GPU/self-host code in this workstream.

## Scope

- **Add your catalog file only:** `gateway/catalog/VideoCatalog.java` (`@Component implements CatalogContributor`). Do **not** edit `ModelCatalog.java`.
- **Refresh existing video adapters:** `gateway/providers/SoraVideoProvider.java` (Sora 2 / Sora 2 Pro) and `VolcengineArkVideoProvider.java` (Seedance → current `seedance-2.0`/`1.5`, verify availability).
- **Build new video `ProviderAdapter`s** (mirror the two existing video adapters; all video is async → `requiresAsync=true`, route through the generation-job path; honor the optional `webhook_url`):
  - **Kling** (`KLING` enum exists, no adapter) — Kuaishou, China-hosted.
  - **Google Veo** (Veo 3.1 via Vertex; shares the `google` config block) — Western.
  - **Runway** (`RUNWAY` enum exists, Gen-4) — Western.
  - **MiniMax Hailuo** (via the `MINIMAX` provider) — China-hosted.
  - Optional: **Wan via API** (Alibaba DashScope video) as the cheap open-model tier — this is the API stand-in for the parked self-host plan.

## Curated launch catalog (video tier markup 10–15%; set `markupOverride` per model)

Prices are provider list, USD per second (video bills per second/clip, not per token). Convert with `perSecondMicrocents = USD/sec × 1_000_000`. **Verify before shipping** (sources + UNVERIFIED flags in `Godance-Model-Catalog-Research-2026-05.md`).

- `seedance-2.0` (Volcengine Ark) — token/clip-billed; ~$0.26 per 720p 5s clip w/ audio — price per-second equivalent
- `sora-2` (OpenAI) — ~$0.10/s ; `sora-2-pro` — ~$0.30–0.50/s
- `veo-3.1` (Google) — ~$0.40/s (incl. audio); `veo-3.1-fast` — ~$0.10–0.12/s
- `kling-3.0` (Kling) — ~$0.084–0.112/s (verify official credit→USD)
- `runway-gen4` — ~$0.05–0.12/s
- `minimax-hailuo-2.3` (MiniMax) — unit-billed; price per typical clip
- optional `wan-2.x` (Alibaba) — ~$0.07/s (cheap tier)

Note explicitly which models require async/webhook (all of them). Keep the list curated.

## Conventions

- Async job path is mandatory: return `job_id` immediately, store output via `ObjectStorageClient`, debit on success / refund on failure, fire the per-request `webhook_url` on completion. Reuse the existing `generation_jobs` + job service + webhook plumbing — do not rebuild it.
- New `@ConfigurationProperties` only for genuinely new providers (Kling, Runway); reuse shared `volcengine`/`google`/`alibaba`/`minimax` blocks.
- **Allowlist:** add **`KLING`** to `AlicloudProviderAllowlist` (Kuaishou is China-hosted). MiniMax/Volcengine/Alibaba already present. Google Veo, Sora, Runway are Western — **never** add them to the allowlist; a Veo/Sora/Runway call from `region=ALICLOUD` must return 503.

## Tests

- WireMock cassette per new video provider covering submit→poll→complete (and the webhook fire).
- Job lifecycle test: submit 5s clip → `job_id` <500ms → poll → output URL; debit only on success; refund on failure.
- Regional-allowlist negative test: `kling-3.0` from `ALICLOUD` allowed; `veo-3.1` / `sora-2` from `ALICLOUD` → 503.

## Acceptance criteria

`mvn -q test` green. `GET /v1/models` lists all curated video models (VIDEO modality, per-second pricing, `requires_async=true`). `POST /v1/videos/generations` for any new model returns a job that completes with a stored output URL, correct debit, and webhook delivery. No edits to `ModelCatalog.java` or outside your declared file set. No GPU/self-host code.

## Out of scope

Text/image/audio, billing, frontend, **self-hosting / GPU / Wan-on-GPU** (parked), UI/pricing-page (later pass).
