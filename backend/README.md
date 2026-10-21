# Godance — Backend

Multimodal AI aggregator: text, image, and video behind one
OpenAI-compatible HTTP API. Stripe primary billing, NOWPayments (USDT)
for crypto top-ups. Single credit ledger with idempotent webhook
handling.

**Stack:** Java 21 · Spring Boot 3.4 (WebFlux) · PostgreSQL 16 · Redis 7
· Flyway · Maven · S3-compatible object storage (MinIO local, DO Spaces
/ Alicloud OSS prod) · Quartz (embedded) · Testcontainers · WireMock.

## Quickstart

```bash
# 1. Bring up dev dependencies (Postgres, Redis, MinIO). The first run
#    builds MinIO from source, which takes a few minutes; see
#    "MinIO (built from source)" below.
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

Needs Docker, and nothing else beyond Java 21 and Maven. No registry
login is required: every image is public or built locally.

```bash
docker compose up -d --wait   # Redis + MinIO on localhost (6380 / 9000)
mvn clean verify
```

The `@SpringBootTest` integration tests boot the full application
context, which connects to the compose stack's Redis and MinIO on the
ports above. Postgres is supplied per test class by Testcontainers, and
`ImageGenerationIntegrationTest` / `VideoGenerationIntegrationTest` start
their own throwaway MinIO from the same locally built image. Every
upstream provider is WireMock-stubbed, so no real API keys are needed.

CI runs the same thing. See [CI](#ci) below.

### MinIO (built from source)

MinIO withdrew its public images from Docker Hub and Quay in 2025:
`docker pull minio/minio` now fails with "pull access denied". Our GHCR
mirror can't be made public, so it is no use to contributors. Instead,
[`dev/minio/Dockerfile`](dev/minio/Dockerfile) builds MinIO
`RELEASE.2024-12-13T22-19-12Z` from source with `go install`. It needs only
the public `golang` and `alpine` images and the Go module proxy, and
`sum.golang.org` verifies the source hash. The build mirrors upstream's
release build (Go 1.23, `CGO_ENABLED=0`, version ldflags), so `minio
--version` reports the release.

All three consumers share one local-only tag:

```text
localhost/godance/minio:RELEASE.2024-12-13T22-19-12Z
```

| Consumer | How it gets the image |
|---|---|
| `docker compose up` | builds it if missing (`pull_policy: never`), otherwise reuses it |
| Testcontainers tests | reuse it; if it is missing (for example, a single test run from the IDE without compose), [`MinioTestImage`](src/test/java/ai/godance/web/MinioTestImage.java) builds it once |
| CI | the shared `.github/actions/backing-services` action builds it with a GitHub Actions layer cache |

The `localhost/` prefix makes Docker treat the tag as local-only, so it
never pulls a same-named image from a remote registry.

Useful commands:

```bash
docker compose build minio      # (re)build the image explicitly

# Different builder base: a registry mirror, or a golang image that trusts
# your network's TLS-intercepting proxy CA.
docker compose build --build-arg GO_IMAGE=<registry>/golang:1.23.12-alpine3.22 minio
```

Upgrading MinIO: follow the steps in the Dockerfile header (resolve the
new tag with `go list -m github.com/minio/minio@RELEASE.<tag>`, then bump
the tag everywhere it is listed).

If MinIO refuses to start against an existing `godance_minio_data`
volume, the volume may have been written by the newer MinIO in the old
GHCR mirror. The data is dev-only, so `docker compose down -v` starts
clean.

### CI

| Workflow | Runs on | Does |
|---|---|---|
| `.github/workflows/ci.yml` | every pull request, pushes to non-`main` branches | backend `mvn -B verify`, and `npm ci && npm run build` for `app/` and `marketing/`. Deploys nothing; uses only the read-only `GITHUB_TOKEN`, so fork PRs work too |
| `.github/workflows/deploy.yml` | push to `main` (backend paths) | same backend tests, then build, push and deploy the image |
| `.github/workflows/dependency-graph.yml` | push to `main` | submits the resolved Maven dependency tree to GitHub's dependency graph, so Dependabot alerts cover the backend (its static `pom.xml` parsing can't see BOM-managed or transitive versions; see `docs/dependency-triage-2026-09.md`) |

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
├── dev/minio/Dockerfile       # MinIO built from source (no public image exists)
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
