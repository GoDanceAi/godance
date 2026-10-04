# Brief 1 — Text Model Catalog Refresh & New Providers

**Branch:** `feature/text-catalog` · **Merges first (1 of 4).** · **Read `00-COORDINATION.md` first.**

## Mission

Refresh Godance's text catalog to the current frontier (May 2026) and add the missing text providers, so `/v1/chat/completions` can serve a curated cheap-tier and smart-tier across OpenAI, Anthropic, Google, and the major China-hosted labs. Streaming + fallback must work for every model.

## Scope

You own the catalog contributor refactor (see Coordination §Shared-files item 1). You also:
- **Refresh existing text adapters' models** (OpenAI, Anthropic, GoogleVertex are already implemented) to current model strings + pricing.
- **Build new `ProviderAdapter` text implementations** for providers that are named in `domain/Provider.java` but have no adapter yet: DeepSeek, Moonshot (Kimi), Alibaba DashScope (Qwen), Zhipu (GLM), MiniMax, plus Volcengine Ark **Doubao** (text; the Volcengine config/key already exists for video). xAI Grok if `XAI` adapter is missing.
- Optionally add Baidu **ERNIE** (requires adding `BAIDU` to the `Provider` enum + the Alicloud allowlist).

Mirror `gateway/providers/OpenAIProvider.java` and `AnthropicProvider.java` as templates. Most of these labs ship OpenAI-compatible `/chat/completions` endpoints, which keeps adapters thin.

## Curated launch catalog (set `markupOverride` only where you deviate from the 5% text tier)

Prices are provider list, USD per 1M tokens (input / output). Convert with `inputMicrocentsPer1k = USDper1M × 1000`. **Confirm each price against the provider dashboard before shipping** — several are aggregator-sourced (see `Godance-Model-Catalog-Research-2026-05.md` for sources and UNVERIFIED flags).

**Smart tier (frontier):**
- `gpt-5.5` (OpenAI) — 5.00 / 30.00
- `gpt-5.4-pro` or `gpt-5.4` (OpenAI) — 2.50 / 15.00
- `claude-opus-4-7` (Anthropic) — 5.00 / 25.00
- `claude-sonnet-4-6` (Anthropic) — 3.00 / 15.00
- `gemini-3.1-pro` (Google) — 2.00 / 12.00 (tiered >200K)
- `deepseek-v4-pro` (DeepSeek) — ~1.74 / 3.48 (promo lower until 2026-05-31)
- `doubao-1.6-pro` (Volcengine Ark) — verify on Ark console
- `grok-4.x` (xAI) — verify on x.ai pricing

**Cheap/fast tier (target < $0.50/1M input):**
- `gpt-5.4-nano` (OpenAI) — 0.20 / 1.25
- `gemini-3.1-flash-lite` (Google) — 0.25 / 1.50
- `claude-haiku-4-5` (Anthropic) — 1.00 / 5.00
- `deepseek-v4-flash` (DeepSeek) — 0.14 / 0.28
- `kimi-k2.6` (Moonshot) — ~0.95 / 4.00
- `qwen-3.6` and `qwen-3.6-coder` (Alibaba DashScope) — verify
- `glm-4.7-flash` (Zhipu) — verify
- `minimax-m2.7` (MiniMax) — verify

Keep the list curated (the product philosophy is ~60 hand-picked models total, not "500+"). Drop any model you cannot price or reach.

## Conventions (see Coordination for full list)

- Reactive (`Mono`/`Flux`), streaming via `chatStream` (SSE). Implement `chatStream` for every text model; fall back to non-stream only if the upstream truly lacks it.
- New `@ConfigurationProperties` per provider under `godance.providers.<name>.*` with `${ENV_VAR:dev-default}` placeholders. Reuse the existing `volcengine`, `alibaba`, `google` blocks where shared.
- Add fallback chains in `application.yml` for your new models (e.g. `gpt-5.5: [gpt-5.4, claude-sonnet-4-6]`).
- China-hosted providers (DeepSeek, Moonshot, Alibaba, Zhipu, MiniMax, Volcengine, Baidu) must be in `AlicloudProviderAllowlist`; the first five already are — add `BAIDU` only if you integrate ERNIE. **Never** add Western providers to the allowlist.

## Tests

- WireMock cassette per new provider (record a real request/response shape; do not mock the adapter interface).
- A streaming test asserting SSE chunks for at least one new provider.
- A fallback test: primary model 5xx → configured fallback used, noted in response metadata.
- A regional-allowlist negative test: a DeepSeek call from `region=ALICLOUD` is allowed; a `gpt-5.5` call from `ALICLOUD` returns 503.

## Acceptance criteria

`mvn -q test` green. `GET /v1/models` returns all curated text models with TEXT modality + correct input/output pricing. A chat completion to any new model returns an OpenAI-compatible response (streaming + non-streaming). Catalog contributor refactor in place (`CatalogContributor` interface + `TextCatalog`), with image/video able to add their own contributors without touching `ModelCatalog.java`.

## Out of scope

Image/video/audio models, billing, frontend, self-hosting. UI/pricing-page updates are a later consolidation pass.
