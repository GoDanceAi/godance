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
├── backup.sh                Daily Postgres backup → DO Spaces
├── encrypt-at-rest.md       Docker's data on an encrypted Volume
└── README.md                This file
```

## Prerequisites

- A DigitalOcean droplet, Ubuntu 24.04, 4 GB RAM minimum.
- A 50 GiB DigitalOcean Volume holding Docker's data. A droplet's own disk
  is not encrypted at rest; a Volume is. Set it up with
  [encrypt-at-rest.md](encrypt-at-rest.md#a-new-droplet) before the first
  `docker compose up`.
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
| `KEY_VAULT_KEY` | `openssl rand -base64 32`. Encrypts authenticator secrets in the database. The backend won't boot without it, and the deploy stops before touching the running container if it's missing. Back it up apart from the database: if it is lost, nobody with two-factor sign-in on can sign in |
| `DATABASE_PASSWORD` | `openssl rand -base64 24` |
| `OBJECT_STORAGE_*` | DigitalOcean → Spaces → create bucket + key |
| `STRIPE_SECRET_KEY` | Stripe dashboard → Developers → API keys |
| `STRIPE_WEBHOOK_SECRET` | Stripe dashboard → Webhooks → add endpoint `https://api.godance.ai/v1/billing/webhooks/stripe` subscribed to `checkout.session.completed`, `checkout.session.async_payment_succeeded` and `checkout.session.async_payment_failed` → copy signing secret |
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

Pushes to `main` that touch `backend/**` auto-deploy via GitHub Actions
(`.github/workflows/deploy.yml`): test and OSV scan → build image → push to
GHCR as `:<commit sha>` and `:latest` → SSH-deploy here. The deploy
fast-forwards `/opt/godance` to the commit it built and runs that commit's
image (`BACKEND_TAG=<sha>`). It passes only once `/healthz` reports that
commit and `/actuator/health` (PostgreSQL and Redis included) is `UP`.
No manual step needed.

To deploy by hand (e.g. CI is down):

```bash
cd /opt/godance
git pull
docker compose -f backend/deploy/docker-compose.prod.yml \
               --env-file backend/deploy/.env.prod \
               up -d --build backend
```

Only the `backend` service is rebuilt; postgres/redis/caddy keep
running with their volumes intact.

## Changing the Caddyfile

A deploy updates the file on the droplet but leaves the running proxy alone,
so a Caddyfile change does nothing until you recreate the container. Once the
deploy has finished:

```bash
cd /opt/godance/backend
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod \
               run --rm --no-deps caddy \
               caddy validate --config /etc/caddy/Caddyfile --adapter caddyfile
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod \
               up -d --no-deps --force-recreate caddy
```

The first command checks the new file in a throwaway container; the proxy
keeps running. The second drops open connections, chat streams included, for a
couple of seconds. `--no-deps` leaves the backend alone, and certificates stay
in `caddy_data`. Recreating also deletes the old container's logs.

`caddy reload` and a plain `up -d caddy` don't apply the change, and neither
says so. `git` replaces the file, the container's bind mount stays on the old
one, and the reload exits 0 with the old config.

## Rolling back

Every build is in GHCR under its full commit sha. To go back to an earlier
release, run its image:

```bash
cd /opt/godance/backend
export BACKEND_TAG=<full sha of the release to go back to>
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod pull backend
docker compose -f deploy/docker-compose.prod.yml --env-file deploy/.env.prod up -d --no-build backend
curl -s https://api.godance.ai/healthz | jq .git_sha    # the first 7 characters of that sha
```

Flyway migrations only move forward: an image older than the newest
migration still starts, but check that release's migrations before rolling
back across a schema change. The next push to `main` deploys forward again.

## Backups

The data that matters lives in two named volumes:

- `godance_postgres_data` — all user, ledger, conversation data.
- `godance_caddy_data` — TLS certificates + Let's Encrypt account.

`backup.sh` runs a daily logical `pg_dump`, gzips it, uploads it to
`s3://$OBJECT_STORAGE_BUCKET/db-backups/`, and prunes dumps older than
14 days (local + remote). The local copies go to `LOCAL_DIR`, set below to
the encrypted Volume.

Install once on the droplet:

```bash
# One-time: install AWS CLI v2 (the `awscli` apt package is not in the
# droplet's enabled repos).
apt-get install -y unzip
curl -fsSL https://awscli.amazonaws.com/awscli-exe-linux-x86_64.zip -o /tmp/awscliv2.zip
cd /tmp && unzip -oq awscliv2.zip && ./aws/install --update && cd -

LOCAL_DIR=/mnt/godance_data/backups /opt/godance/backend/deploy/backup.sh   # run once to verify it works

# Daily cron at 02:00 UTC. PATH is set explicitly — cron.d jobs run with a
# minimal PATH that excludes /usr/local/bin, where `aws` is installed.
cat >/etc/cron.d/godance-backup <<'EOF'
PATH=/usr/local/bin:/usr/bin:/bin
LOCAL_DIR=/mnt/godance_data/backups
0 2 * * * root /opt/godance/backend/deploy/backup.sh >> /var/log/godance-backup.log 2>&1
EOF
```

For remote retention beyond the script's 14-day prune you can also set a
lifecycle expiry rule on the Spaces bucket. `godance_caddy_data` is
recreatable (Caddy re-issues certs), so it isn't backed up.

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

- **Move Postgres to DO Managed Database** for point-in-time recovery
  on top of the `backup.sh` logical dumps.
- **Move Redis to DO Managed Redis** for similar reasons.
- **Add monitoring** — at minimum, set up DigitalOcean monitoring
  alerts on CPU + disk; ideally pipe `docker compose logs` to
  Loki/Promtail or a managed log service.
- **Add a second region** (Alicloud) for failover. Requires changes to
  the Quartz cron (only one region should run scheduled jobs) and
  Cloudflare DNS health-check failover.
