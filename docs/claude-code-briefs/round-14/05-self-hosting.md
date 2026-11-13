# Brief 05 — Self-hosting guide

**Branch:** `feature/self-hosting` (worktree `../gd-selfhost`) off `main` with round 13 merged
**Reserved migration:** none. This brief adds documentation and packaging only.
**Baseline:** the backend, app and marketing CI checks green on parent

## Mission (one line)

Make running your own copy of Godance documented and repeatable, from an
empty server to a working API, Studio and admin console, with packaging
kept in the repository and checked by CI. What it does not support is said
plainly.

## Why now

The repository is Apache 2.0 and anyone can run it today, but nothing
explains how. Some teams need their data in their own infrastructure; some
experts simply prefer it. A guide serves them at little engineering cost.
Supporting them is a separate commitment, which is the operator's to make
or not.

## Read first

1. This brief and `00-coordination.md`
2. `README.md`, `backend/README.md`, `backend/CLAUDE.md` (hard constraints),
   `backend/HANDOFF.md`
3. `backend/deploy/`:
   - `docker-compose.prod.yml`, `Caddyfile`, `Dockerfile`, `backup.sh`
   - `README.md` and `encrypt-at-rest.md`

   This is the operator's own production setup. Do not change it; learn
   from it.
4. `backend/docker-compose.yml` (development), `backend/src/main/resources/application.yml`
   and `application-prod.yml`
5. `app/` and `marketing/` builds and their environment variables
   (`app/.env.example`)
6. `.github/workflows/*.yml`

## Scope

### 1. `docs/self-hosting.md`

Sections, in order:
1. What you get, and what you do not:
   - no updates or support unless the operator offers them
   - the name "Godance" is subject to the trademark policy, a gap the
     operator fills
2. Requirements: CPU, memory, disk, a domain, object storage, email.
3. Quick start with the packaged compose file.
4. **Configuration reference:** every `godance.*` and Spring setting a
   self-hoster may need, with what it does and its safe default. It is
   generated (item 3), not hand-maintained.
5. **Provider keys:** where each goes, and that unset providers simply have
   no models.
6. **The regional allowlist:** what `REGION` does, that it cannot be turned
   off, and why.
7. **Billing:**
   - Stripe and NOWPayments are optional.
   - Without them, an `ADMIN` funds accounts through the admin console's
     credit adjustment (`POST /v1/admin/users/{id}/credit-adjust`), which
     keeps the single ledger.
   - Explain the platform fee settings.
8. **Security checklist:**
   - secrets via environment
   - `KeyVault` and encryption at rest
   - session cookie settings
   - CORS
   - two-factor for staff
   - backups
9. **Upgrades:** pulling a release, Flyway migrations running on start,
   reading the changelog, and backing up first.
10. **Optional parts:** the marketing site and gallery, MCP, batches,
    inputs (off by default) and the community.
11. Troubleshooting.

### 2. Packaging: `deploy/self-host/`

- **`docker-compose.yml`:** the API (built from `backend/Dockerfile`),
  Postgres, Redis, MinIO, and Caddy serving the built app and the API on
  one domain, with automatic TLS.
- **`.env.example`:** every variable, with visibly fake placeholders.
- **`Caddyfile`:** proxy timeouts consistent with the production one
  (image waits and long requests).
- **`README.md`:** a pointer to `docs/self-hosting.md`.
- **Separate from production.** Do not reuse or modify `backend/deploy/`.
  Copy what is needed and say where it came from.

### 3. Checks in CI

- **A script under `backend/` or a test** that lists every
  `@ConfigurationProperties` field and every `${ENV:…}` placeholder in
  `application*.yml`. It generates the configuration reference section, and
  fails when the docs are out of date.
- **A CI job** that validates the compose file (`docker compose config`)
  and checks `.env.example` covers every variable the compose file uses.
  It runs on changes to `deploy/self-host/**` or the config.

## Hard rules

- Edits only in `docs/self-hosting.md`, `deploy/self-host/**`, the
  generator script or test, and one CI workflow or job.
- **No change to application code or to `backend/deploy/`.**
- **No real secret, private host name or operator note** in any committed
  file. Placeholders are visibly fake.
- **The regional allowlist is described as fixed,** because it is.
- Nothing documented that is not true of `main`.

## Opening move

Run the CI checks on parent. Bring up the development stack and list every
environment variable the production compose file uses, with where each is
read. Then post a 3–5 bullet plan before writing anything.

## Acceptance criteria

- On a clean machine or VM, following the quick start, from an empty
  directory to:
  - `/healthz` green
  - sign-up
  - an admin credit adjustment
  - one chat completion against a provider key or a WireMock stand-in
  - the Studio loading

  Record it in the final summary with timings and every step that was
  unclear.
- The configuration reference is generated and the drift check fails when a
  property is added without docs.
- The compose check runs in CI.
- All work committed on `feature/self-hosting`; `main` untouched.

## Out of scope (do not start)

- Kubernetes or Helm.
- A one-click installer.
- Multi-region.
- Running without Postgres, Redis or object storage.
- A billing-off mode in code.
- Support commitments (operator).
