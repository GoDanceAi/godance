# Godance — Parallel Claude Code Coordination Contract

**Read this before any of the four workstream briefs.** Four Claude Code instances will work simultaneously on Godance. This document is the shared contract that keeps them from colliding. Each instance must obey it.

## The four workstreams

| # | Branch | Brief | Touches |
|---|---|---|---|
| 1 | `feature/text-catalog` | `01-text-models-brief.md` | text providers + ModelCatalog |
| 2 | `feature/image-catalog` | `02-image-models-brief.md` | image providers + ModelCatalog |
| 3 | `feature/video-catalog` | `03-video-models-brief.md` | video providers + ModelCatalog |
| 4 | `feature/crypto-nowpayments` | `04-crypto-payments-brief.md` | billing/payments |

## Repository & ground rules

- **Canonical repo:** `godance-oss/backend/` (Spring Boot, Java 21, WebFlux). This is what the production droplet deploys from. Work here, not in `godance-archive/`.
- **Each instance works on its own branch** off the current `main`, listed above. Do not commit to `main`. The operator merges in the order **1 → 2 → 3 → 4** (text, image, video, crypto).
- **OSS hygiene (hard rule):** this is a public Apache-2.0 repo. Do **not** add internal references, real secrets, private URLs, or operator notes to committed files. Never edit `CLAUDE.md`, `HANDOFF.md`, or `PROGRESS.md` (working-copy-only / sanitized). Config goes through env-var placeholders only.
- **Read first, every instance:** `gateway/ModelCatalog.java`, `gateway/ProviderAdapter.java`, `gateway/ProviderGateway.java`, `gateway/AlicloudProviderAllowlist.java`, `domain/Provider.java`, and your modality's existing adapter(s) in `gateway/providers/`.

## Architecture you must follow (from the live code)

- **Models live in code,** not the DB: `gateway/ModelCatalog.java` registers `ModelInfo` records. `GET /v1/models` (`web/ModelsController.java`) reads them.
- **`ModelInfo` fields:** `modelName`, `provider` (enum), `modality` (TEXT/IMAGE/VIDEO/AUDIO_STT/AUDIO_TTS/EMBEDDING), `inputMicrocentsPer1k`, `outputMicrocentsPer1k`, `defaultMaxOutputTokens`, `markupOverride` (Double, null = tier default), `perImageMicrocents`, `perSecondMicrocents`, `requiresAsync` (boolean — true routes via the async job path).
- **Money is microcents** (1 microcent = 0.0001¢ = $0.000001). Conversion helpers:
  - Text: `inputMicrocentsPer1k = (USD per 1M tokens) × 1000`. *(e.g. $5/1M → 5000)*
  - Image: `perImageMicrocents = (USD per image) × 1_000_000`. *(e.g. $0.04 → 40000)*
  - Video: `perSecondMicrocents = (USD per second) × 1_000_000`. *(e.g. $0.40/s → 400000)*
- **Providers are Spring beans:** implement the adapter interface for your modality, annotate `@Component`, and `ProviderGateway` auto-discovers it. No factory, no manual registration. Mirror your modality's existing adapter as the template.
- **Provider config:** `@ConfigurationProperties` class bound to `godance.providers.<name>.*` in `application.yml`, with `${ENV_VAR:dev-default}` placeholders. Mirror `OpenAIProperties` / `VolcengineProperties`.
- **Regional allowlist is a HARD constraint:** `AlicloudProviderAllowlist` (an `EnumSet`) lists the only providers that may be dispatched from the Alicloud region. Western providers (OpenAI, Anthropic, Google Vertex, BFL, Sora, Runway) must **never** be added to it. Add only genuinely China-hosted providers. Fail-closed is the intended behavior.
- **Tests:** integration tests against Testcontainers Postgres+Redis; provider tests use **WireMock record/replay cassettes** — do **not** mock the adapter interface itself. Test naming: `methodUnderTest_givenCondition_expectsResult`.
- **Reactive end-to-end:** controllers/services return `Mono<T>`/`Flux<T>`. Errors are RFC 7807 `application/problem+json`.

## Shared files — the collision map

These files are touched by more than one instance. Follow the protocol exactly.

1. **`gateway/ModelCatalog.java` (instances 1, 2, 3).**
   - **Instance 1 (text) performs a one-time refactor** to a contributor pattern: define `gateway/CatalogContributor.java` (`interface CatalogContributor { List<ModelInfo> models(); }`), have `ModelCatalog` autowire `List<CatalogContributor>` and build its map from them, and add `gateway/catalog/TextCatalog.java implements CatalogContributor`.
   - **Instances 2 and 3 add only their own file** — `gateway/catalog/ImageCatalog.java` and `gateway/catalog/VideoCatalog.java`, each `@Component implements CatalogContributor`. They do **not** edit `ModelCatalog.java`.
   - Because merge order is 1→2→3, the interface exists before image/video merge. If you (image/video) start before text has merged, code against the interface signature above and rebase after text lands.

2. **`domain/Provider.java` (mostly instance 1).** The enum already contains DEEPSEEK, MOONSHOT, ALIBABA_DASHSCOPE, ZHIPU, MINIMAX, VOLCENGINE_ARK, KLING, RUNWAY, STABILITY, BFL, etc. Only add an enum value if your provider is genuinely missing (e.g. text may add `BAIDU` for ERNIE; xAI `XAI` already exists). Append at the end; one-line additions merge cleanly.

3. **`gateway/AlicloudProviderAllowlist.java` (instances 1, 3).** Append only new *China-hosted* providers. Current set already includes DEEPSEEK, MOONSHOT, ALIBABA_DASHSCOPE, ZHIPU, MINIMAX, VOLCENGINE_ARK. **Video must add `KLING`** (Kuaishou, China-hosted). Text adds `BAIDU` only if it integrates ERNIE. Do not add Western providers.

4. **`application.yml` provider blocks (instances 1, 2, 3).** Add your `godance.providers.<name>.*` blocks. **Shared provider keys:** `volcengine` (already present — Seedream/Seedance/Doubao share it; reuse, don't duplicate), `alibaba`/dashscope (Qwen text *and* Qwen-Image share it), `google` (Gemini text *and* Imagen/Veo share it). If a block already exists, extend it; don't create a second one. Additions are namespaced, so merges are trivial.

5. **`config/GatewayProperties.java` fallback chains in `application.yml` (instances 1, 2, 3).** Add fallback entries only for your own models. Additive map keys; trivial merge.

## Flyway migrations

- **Latest committed migration is `V8`.**
- **Text / Image / Video: add NO migrations.** Models live in code; you should not need schema changes. If you believe you do, stop and flag the operator.
- **Crypto (instance 4): owns `V9`** (and `V10` if a second is truly needed). Use only those numbers.

## What every instance must NOT do

- Do not edit another workstream's files or another modality's adapters/catalog.
- Do not change the regional-allowlist *semantics* (only append China-hosted providers).
- Do not rewrite `CreditLedgerService`, `ProviderGateway` dispatch logic, or `ModelsController` shape.
- Do not add frontend changes (SPA / marketing). UI is a deferred consolidation pass.
- Do not bump shared dependency versions in `pom.xml` without flagging.
- Do not commit secrets; use env-var placeholders.

## Definition of done (all instances)

`mvn -q test` green on your branch · new providers reachable through `/v1/chat/completions` (or images/videos) · `GET /v1/models` lists your new models with correct modality + pricing · WireMock cassette tests for each new provider · regional-allowlist negative test where applicable · no edits outside your declared file set.
