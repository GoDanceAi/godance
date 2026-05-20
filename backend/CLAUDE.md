# Godance — Project Memory

Godance (godance.ai) is a multimodal AI aggregator: text + image + video +
audio behind one OpenAI-compatible API and a simple chat UI. Stripe primary
billing, Coinbase Commerce for crypto top-ups. Single credit ledger.

**Read this first:** [`HANDOFF.md`](./HANDOFF.md) (in this folder).
Everything below is shorthand; that document is canonical for the initial
scaffold and entity-porting plan.

## Tech Stack

- Java 21 (LTS — for virtual threads + pattern matching)
- Spring Boot 3.4+ with **WebFlux** (reactive — required for SSE streaming)
- PostgreSQL via Spring Data JPA + Hibernate
- Flyway migrations
- Redis (rate limits, prompt cache hints, session)
- Spring Security + JWT
- Stripe Java SDK + Coinbase Commerce webhook integration
- Quartz scheduler (embedded — no separate worker fleet)
- S3-compatible object storage via a single `ObjectStorageClient` abstraction
  (MinIO locally, DO Spaces in prod, Alicloud OSS in failover)
- Build tool: **Maven** (Spring Boot's primary path)

## Hard Constraints (Non-Negotiable)

1. **Regional provider allowlist — code-level enforcement.** The
   `ProviderGateway` enforces a hardcoded allowlist per region. The Alicloud
   region MUST NEVER route Anthropic, OpenAI, Google Vertex, BFL Flux,
   ElevenLabs, Cartesia, or Runway — not in failover, not for testing, not
   ever. Fail closed on policy violation. This respects Anthropic / OpenAI /
   Google Terms of Service that restrict serving these models from
   Chinese-owned infrastructure. Tests must cover the negative case (OpenAI
   call from Alicloud region → 503).
2. **No plaintext secrets at rest.** API keys, user passwords, Coinbase
   webhook secrets, Stripe keys, provider keys — all encrypted (AES-256). A
   dedicated `secrets` table with a `KeyVault` service, or Spring's
   Jasypt-style property encryption.
3. **Single-tenant.** The system has no notion of organizations or tenants.
   Do not introduce `tenant_id` columns on domain entities.
4. **No agent orchestration in v1.** No HITL gates, no tool-calling, no
   agent runtime. Model in, content out. Architect the
   Provider/Gateway/Router so an orchestrator could be added later, but do
   not ship it.
5. **OpenAI-compatible REST contract.** `/v1/chat/completions`,
   `/v1/images/generations`, `/v1/videos/generations`, `/v1/audio/speech`,
   `/v1/audio/transcriptions`, `/v1/embeddings`, `/v1/models`. OpenAI SDKs
   must work as drop-in clients by swapping `base_url`.
6. **Crypto top-up is payment flexibility, not a brand identity.** Do not
   put "blockchain" or "crypto-native" in any user-facing copy, marketing,
   or default landing page. Crypto is one of three payment methods, not the
   product.

## Code Conventions

- Java records for DTOs; classes for entities
- Lombok allowed (`@Slf4j`, `@RequiredArgsConstructor`); avoid `@Data` on
  entities — explicit getters/setters
- Controllers return `Mono<T>` or `Flux<T>` — reactive end-to-end
- No `System.out.println` — use `log.info/warn/error`
- Test naming: `methodUnderTest_givenCondition_expectsResult`
- Unit tests in JUnit 5 + Mockito; integration tests against real Postgres +
  Redis via Testcontainers
- Provider integration tests use WireMock with recorded cassettes; do NOT
  mock the `Provider` interface itself in those tests — that's the bug
  surface we want to catch
- All HTTP errors return RFC 7807 problem details
  (`application/problem+json`)
- Idempotency on all webhook handlers via a unique key from the upstream
  provider (Stripe `idempotency-key` header, Coinbase Commerce `id`)
- Money is always in **integer cents** (`BigInteger` or `long`) — never
  `double`, never `BigDecimal` for arithmetic. Convert to display strings
  only at the boundary.

## When Stuck

If a requirement is ambiguous, priority order:

1. Match OpenRouter's API contract (`openrouter.ai/docs`).
2. Open an issue or discussion on the repository for design decisions
   that could shape the product (regional routing, encryption choices,
   pricing tiers).
