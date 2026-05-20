# Godance — Deployment runbook

Self-hosted, single-node production deploy. One DigitalOcean droplet
(4 GB) running everything in Docker behind Caddy. No managed services
required.

## What's in this folder

```
deploy/
├── .env.prod.template       Annotated env file — copy to .env.prod
├── Caddyfile                Reverse proxy + Let's Encrypt config
├── Dockerfile               Multi-stage backend image
├── docker-compose.prod.yml  Compose stack: caddy + backend + postgres + redis
└── README.md                This file
```

## Prerequisites

- A DigitalOcean droplet, Ubuntu 24.04, 4 GB RAM minimum.
- Public DNS A record `api.godance.ai → <droplet IP>`.
- Ports 22 (SSH from your IP), 80, 443 open in the droplet firewall.
- Real values for every variable in `.env.prod.template`.

## First-time deploy

**On your laptop**, clone the repo to the droplet:

```bash
# 1. SSH into the droplet
ssh root@<droplet-ip>

# 2. Install Docker + compose plugin
curl -fsSL https://get.docker.com | sh
apt install -y docker-compose-plugin

# 3. Clone the repo
cd /opt
git clone https://github.com/GoDanceAi/godance.git
cd godance/backend     # the backend lives here in the public monorepo
```

**Configure the env file** (still on the droplet):

```bash
# Copy template, edit with real values
cp deploy/.env.prod.template deploy/.env.prod
nano deploy/.env.prod
chmod 600 deploy/.env.prod    # readable only by root
```

Things that need real values up-front:

| Variable | Source |
|---|---|
| `JWT_SIGNING_KEY` | `openssl rand -base64 48` |
| `DATABASE_PASSWORD` | `openssl rand -base64 24` |
| `OBJECT_STORAGE_*` | DigitalOcean → Spaces → create bucket + key |
| `STRIPE_SECRET_KEY` | Stripe dashboard → Developers → API keys |
| `STRIPE_WEBHOOK_SECRET` | Stripe dashboard → Webhooks → add endpoint `https://api.godance.ai/v1/billing/webhooks/stripe` → copy signing secret |
| `OPENAI_API_KEY` | platform.openai.com → API keys |
| `ANTHROPIC_API_KEY` | console.anthropic.com → API keys (optional) |
| `GOOGLE_API_KEY` | aistudio.google.com → API keys (optional) |

**Bring it up**:

```bash
# From godance/backend, with deploy/.env.prod populated:
docker compose -f deploy/docker-compose.prod.yml \
               --env-file deploy/.env.prod \
               up -d --build
```

The first run takes ~3 minutes:
- Builds the backend image (Maven download + compile)
- Pulls postgres / redis / caddy images
- Caddy negotiates TLS with Let's Encrypt (~10–30 s once DNS resolves)

Verify:

```bash
# All three should be (healthy)
docker compose -f deploy/docker-compose.prod.yml ps

# Public health endpoint
curl -s https://api.godance.ai/healthz | jq
```

## Updating after a code change

```bash
cd /opt/godance
git pull
docker compose -f backend/deploy/docker-compose.prod.yml \
               --env-file backend/deploy/.env.prod \
               up -d --build backend
```

Only the `backend` service is rebuilt; postgres/redis/caddy keep
running with their volumes intact.

## Rolling back

The `image: godance/backend:latest` tag is rewritten on every build.
For a real rollback safety net, tag versions explicitly:

```bash
# Before deploy
docker tag godance/backend:latest godance/backend:$(date +%Y%m%d-%H%M)

# To roll back
docker tag godance/backend:20260601-1200 godance/backend:latest
docker compose -f deploy/docker-compose.prod.yml up -d backend
```

For more disciplined release management later, switch to a registry
(GHCR / Docker Hub) and pin SHA-tagged images.

## Backups

The data that matters lives in two named volumes:

- `godance_postgres_data` — all user, ledger, conversation data.
- `godance_caddy_data` — TLS certificates + Let's Encrypt account.

Daily logical backup of Postgres:

```bash
docker compose -f deploy/docker-compose.prod.yml exec -T postgres \
  pg_dump -U godance -d godance --no-owner --no-acl \
  | gzip > /var/backups/godance-$(date +%F).sql.gz
```

Wire that into cron or a DigitalOcean droplet snapshot schedule.

## Troubleshooting

- **Caddy can't issue cert:** check DNS has propagated
  (`dig +short api.godance.ai`) and the droplet firewall allows
  inbound 80/443. Watch with
  `docker compose -f deploy/docker-compose.prod.yml logs -f caddy`.
- **Backend won't start, complains about a missing env var:** the
  app fails fast on missing required config. Re-read `.env.prod`
  against `.env.prod.template`.
- **`/healthz` returns 200 but no real endpoints work:** likely
  Flyway failed on first run. `docker compose ... logs backend`
  will show the migration error.
- **Stripe webhooks 400-ing in prod:** confirm the
  `STRIPE_WEBHOOK_SECRET` matches the secret shown in the Stripe
  Dashboard for the prod endpoint (not your dev `stripe listen`
  secret — that one rotates).

## Hardening for v0.2 and beyond

Out of scope for the initial launch; add when traffic justifies it:

- **Move Postgres to DO Managed Database** for automatic backups and
  point-in-time recovery.
- **Move Redis to DO Managed Redis** for similar reasons.
- **Add CI/CD** via GitHub Actions: build image on push to `main`,
  push to GHCR, SSH-deploy to the droplet.
- **Add monitoring** — at minimum, set up DigitalOcean monitoring
  alerts on CPU + disk; ideally pipe `docker compose logs` to
  Loki/Promtail or a managed log service.
- **Add a second region** (Alicloud) for failover. Requires changes to
  the Quartz cron (only one region should run scheduled jobs) and
  Cloudflare DNS health-check failover.
