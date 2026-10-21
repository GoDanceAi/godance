# Godance — Backend

Multimodal AI aggregator: text, image, video, and audio behind one
OpenAI-compatible HTTP API. Stripe primary billing, NOWPayments (USDT)
for crypto top-ups. Single credit ledger with idempotent webhook
handling.

**Stack:** Java 21 · Spring Boot 3.4 (WebFlux) · PostgreSQL 16 · Redis 7
· Flyway · Maven · S3-compatible object storage (MinIO local, DO Spaces
/ Alicloud OSS prod) · Quartz (embedded) · Testcontainers · WireMock.

## Quickstart

```bash
# 1. Bring up dev dependencies (Postgres, Redis, MinIO).
docker compose up -d

# 2. Run the app. Java 21 must be on PATH or pointed at via JAVA_HOME.
mvn spring-boot:run

# 3. Health check.
curl -s http://localhost:8080/healthz | jq
# -> { "status": "ok", "region": "local", "version": "...", ... }
```

Dev ports (remapped to avoid common collisions with other local stacks
on 5432 / 6379 — see [Local environment notes](#local-environment-notes-macos)):

```text
Postgres   localhost:5433   (jdbc:postgresql://localhost:5433/godance)
Redis      localhost:6380
MinIO API  localhost:9000
MinIO UI   localhost:9001
```

The `/healthz` endpoint is what an external load balancer / DNS failover
should poll; `region` is sourced from the `REGION` env var (defaults to
`local`).

### Build & test

```bash
mvn clean verify
```

The integration tests stand up Postgres + MinIO via Testcontainers and
WireMock-stub each upstream provider — no external services required.

### Tearing down

```bash
docker compose down -v   # -v also wipes the postgres/minio volumes
```

## Local environment notes (macOS)

- **Docker CLI:** if `docker` isn't on `$PATH` despite Docker Desktop
  being installed, symlink or PATH-prepend
  `/Applications/Docker.app/Contents/Resources/bin`.
- **JDK selection:** if your default `mvn` resolves a JDK other than 21,
  set `JAVA_HOME` to a Java 21 install (any vendor — Microsoft,
  Temurin, Zulu, Oracle) before invoking `mvn`.
- **Port remaps:** Postgres is exposed on host `5433` and Redis on
  `6380` because the typical macOS dev environment already has a
  native `postgresql` and a standalone `redis` container occupying the
  default ports. If you've freed those, you can edit
  `docker-compose.yml` and `application.yml` to revert.

## Project layout

```
backend/
├── docker-compose.yml         # Postgres 16, Redis 7, MinIO
├── pom.xml
├── HANDOFF.md                 # Session-by-session scaffold spec + porting plan
├── CLAUDE.md                  # Tech stack, hard constraints, code conventions
└── src/
    ├── main/
    │   ├── java/ai/godance/
    │   │   ├── GodanceApplication.java
    │   │   ├── config/        # Spring configuration + property bindings
    │   │   ├── domain/        # JPA entities
    │   │   ├── repository/    # Spring Data JPA interfaces
    │   │   ├── service/       # Business logic
    │   │   ├── gateway/       # Provider adapters + regional routing
    │   │   ├── security/      # JWT, API key minting, webhook signing
    │   │   ├── storage/       # S3-compatible object-storage client
    │   │   └── web/           # WebFlux controllers + DTOs
    │   └── resources/
    │       ├── application.yml         # dev profile (env-var-overridable)
    │       ├── application-prod.yml    # prod skeleton (env-var-driven)
    │       └── db/migration/           # Flyway V1..VN
    └── test/java/ai/godance/
        ├── persistence/       # PersistenceIntegrationTest (full schema)
        ├── service/           # Unit tests with Spring context
        ├── gateway/           # Provider tests + Alicloud allowlist tests
        ├── security/          # JWT + ApiKey + WebhookSigner
        └── web/               # End-to-end integration tests
```

## Documentation

- [HANDOFF.md](./HANDOFF.md) — scaffold spec + entity port plan
- [CLAUDE.md](./CLAUDE.md) — hard constraints, tech stack, code conventions
- Sibling projects:
  - [`../app`](../app) — React SPA (Vite + TypeScript) for `app.godance.ai`
  - [`../marketing`](../marketing) — Astro static site for `godance.ai`
