# Self-hosting Godance

Packaging for running your own copy of Godance with Docker Compose. Read
**[docs/self-hosting.md](../../docs/self-hosting.md)** first: it says what
you get and what you do not, what you need, and walks through the quick
start, configuration, security, backups and upgrades.

| File | What it is |
|---|---|
| `docker-compose.yml` | The core stack: Caddy (automatic TLS, the app and the API on one host name), the API, Postgres, Redis, and a check of `.env` that runs before the API |
| `docker-compose.minio.yml` | Bundled MinIO for object storage, behind Caddy on its own host name. Left out when the API uses external S3-compatible storage |
| `.env.example` | Every setting the stack needs, with placeholders. Copy it to `.env` |
| `Caddyfile`, `storage.caddy` | Caddy's configuration: the app and the API; the files host |
| `web.Dockerfile` | Builds the app (`app/`) into a Caddy image |
| `preflight.sh` | The `.env` check: refuses placeholders and unusable values |

The API image is built with `backend/deploy/Dockerfile`, the one production
uses, and MinIO with `backend/dev/minio/Dockerfile`. This folder copies
nothing from `backend/deploy/` and changes nothing there: that folder is the
operator's own production setup.

CI (`.github/workflows/self-host.yml`) checks these files on every change:
`docker compose config` with `.env.example`, and that `.env.example` defines
every variable the compose files and Caddy configuration use.
