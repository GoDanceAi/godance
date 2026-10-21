# Godance

> Multimodal AI aggregator: text, image, and video behind one
> OpenAI-compatible API. Stripe card billing + NOWPayments (USDT)
> top-ups. Single credit ledger. Drop-in for any OpenAI SDK — point
> `base_url` at `https://api.godance.ai/v1` and it works.

[![License: Apache 2.0](https://img.shields.io/badge/License-Apache_2.0-blue.svg)](LICENSE)

## What's in this repo

```
godance/
├── backend/     Java 21 · Spring Boot 3.4 (WebFlux) · PostgreSQL · Redis · Flyway
│               OpenAI-compatible REST API, credit ledger, provider gateway with
│               regional allowlist enforcement, embedded Quartz scheduler.
│
├── app/        Vite · React 18 · TypeScript · Tailwind
│               Chat UI, dashboard, billing surface. Deploys as a static SPA
│               to Cloudflare Pages → app.godance.ai.
│
└── marketing/  Astro 5 · Tailwind
                SSG marketing site → godance.ai. SEO + GEO optimized.
```

Each subdirectory has its own README with a quickstart.

## Design philosophy

- **OpenAI-compatible HTTP contract.** Existing OpenAI SDKs work with no
  code changes — just swap `base_url`. Routes served today:
  `/v1/chat/completions`, `/v1/images/generations`,
  `/v1/videos/generations`, `/v1/models` (plus `/v1/jobs/{id}` to
  poll async generations). `/v1/audio/speech`,
  `/v1/audio/transcriptions` and `/v1/embeddings` are planned but not
  yet implemented.
- **Regional provider allowlist is code, not config.** The provider
  gateway enforces a hardcoded allowlist per region. Some upstream
  providers (Anthropic, OpenAI, Google Vertex, BFL Flux) cannot be
  served from Chinese-owned infrastructure under their terms of
  service — the Alicloud region's allowlist is empty for those
  providers. Fail-closed, not opt-in.
- **Single credit ledger, append-only.** All balance changes go through
  `CreditLedger`. The `User.credit_balance_cents` field is a
  denormalized cache; the source of truth is `SUM(delta_cents)`.
- **Integer cents everywhere.** Money is `long` cents at every layer.
  Pricing internally uses microcents (1 ¢ = 10,000 µ¢) for
  per-1k-token arithmetic that would otherwise round to zero on cheap
  models. Conversion to display strings only at the boundary.
- **Idempotent webhooks.** Stripe and NOWPayments webhooks dedupe on
  the upstream id, surfacing duplicate deliveries as success
  without double-crediting.
- **Reactive end-to-end.** WebFlux + Netty. SSE streaming for chat
  completions. Async generation jobs for image / video.

## Status

Live in production at `https://api.godance.ai`; `GET /healthz`
reports the running version. CI auto-deploys `main`: backend changes
to the API, `app/` and `marketing/` changes to Cloudflare Pages. The
full session-by-session build history is visible in the git log.

## License

[Apache License 2.0](LICENSE). Use it, fork it, embed it. Attribution
appreciated, not required.

## Contributing

Issues and pull requests welcome. For substantial changes, open a
discussion first so we can align on direction before code review.

Every pull request runs CI (`.github/workflows/ci.yml`): the backend test
suite (`mvn -B verify`) and production builds of `app/` and
`marketing/`. To run the backend suite locally you need only Docker, Java
21 and Maven; see [`backend/README.md`](backend/README.md#build--test).

The regional allowlist (`backend/.../AlicloudProviderAllowlist`) and the
billing / ledger code are the load-bearing safety surfaces — changes
there need a test in the same PR.
