# Self-hosting Godance

This guide runs your own copy of Godance on one server with Docker Compose:
the API, the app (Studio, chat, history, recipes, batches, the admin
console), Postgres, Redis and object storage, behind Caddy with automatic
TLS. The packaging is in [`deploy/self-host/`](../deploy/self-host/). It was
written against `main` and tested end to end on a trial machine; the record
of that run is summarised in [the last section](#how-this-guide-was-tested).

1. [What you get, and what you do not](#1-what-you-get-and-what-you-do-not)
2. [Requirements](#2-requirements)
3. [Quick start](#3-quick-start)
4. [Configuration reference](#4-configuration-reference)
5. [Provider keys](#5-provider-keys)
6. [The regional allowlist](#6-the-regional-allowlist)
7. [Billing](#7-billing)
8. [Security checklist](#8-security-checklist)
9. [Upgrades](#9-upgrades)
10. [Optional parts](#10-optional-parts)
11. [Troubleshooting](#11-troubleshooting)

## 1. What you get, and what you do not

**You get** the code that runs `godance.ai`, under the
[Apache License 2.0](../LICENSE), and packaging to run it:

- the OpenAI-compatible API. Implemented today: `/v1/chat/completions`,
  `/v1/images/generations`, `/v1/videos/generations` and `/v1/models`, with
  `/v1/jobs` to poll image and video jobs. Audio and embeddings are not
  implemented.
- the app on the same host name, the single credit ledger, the admin console,
  and the MCP server at `/mcp`.
- a compose stack built from this repository: the API image is built with the
  same Dockerfile production uses (`backend/deploy/Dockerfile`).

**You do not get:**

- **Updates or support,** unless the operator of `godance.ai` offers them.
  Nothing here phones home or updates itself; you pull and rebuild when you
  choose (see [Upgrades](#9-upgrades)).
- **The name.** The licence covers the code, not the name: section 6 of the
  Apache License grants no permission to use the licensor's trade names or
  marks beyond describing where the work came from. No trademark policy for
  "Godance" has been published yet; that gap is the operator's to fill.
- **Your own legal pages.** The app links to `godance.ai` for its terms,
  privacy policy and documentation, and the parental-consent page names a
  `godance.ai` address. A copy that serves other people needs its own, which
  means editing `app/`.
- **A billing-off mode.** Every request is charged to an account's balance.
  Without a payment provider, an admin funds accounts (see [Billing](#7-billing)).
- **Other shapes:** Kubernetes or Helm, a one-click installer, several
  regions, or running without Postgres, Redis or object storage.

## 2. Requirements

- **A server** running Linux with Docker Engine and Docker Compose v2.
  Production runs on 4 GB of RAM; the stack's Java heap is set for that
  (`JAVA_OPTS`). The trial below used about 1.1 GB once running. Use 2 vCPUs
  or more: the images are built on the server.
- **Disk:** about 1.3 GB for the images (the API's is 615 MB), more for
  build caches while building, plus your data. Start with 20 GB and watch it.
- **Two host names** pointing at the server: one for the app and API (say
  `godance.example.test`), one for files (say `files.godance.example.test`),
  with TCP ports 80 and 443 open (UDP 443 too, for HTTP/3). Caddy obtains
  their certificates from Let's Encrypt. With external storage you need only
  the first.
- **Object storage:** the bundled MinIO, or any S3-compatible service (see
  [Object storage](#object-storage-bundled-minio-or-your-own)).
- **Email, optional:** a [Resend](https://resend.com) API key and a domain
  verified there. Without one, nothing is sent, and the API writes each
  message's first line (verification and password-reset links included) to
  its log.
- **Provider keys** for the models you want to offer (see
  [Provider keys](#5-provider-keys)).
- `git` and `openssl` on the server.

## 3. Quick start

Run everything from `deploy/self-host/`. Compose names the stack `godance`,
so its containers are `godance-api-1` and so on.

**1. Point DNS at the server** for both host names, and open ports 80 and 443.

**2. Get the code and make `.env`:**

```bash
git clone https://github.com/GoDanceAi/godance.git
cd godance/deploy/self-host
cp .env.example .env
chmod 600 .env
sed -i \
  -e "s|^JWT_SIGNING_KEY=.*|JWT_SIGNING_KEY=$(openssl rand -hex 32)|" \
  -e "s|^KEY_VAULT_KEY=.*|KEY_VAULT_KEY=$(openssl rand -base64 32)|" \
  -e "s|^DATABASE_PASSWORD=.*|DATABASE_PASSWORD=$(openssl rand -hex 24)|" \
  -e "s|^OBJECT_STORAGE_SECRET_KEY=.*|OBJECT_STORAGE_SECRET_KEY=$(openssl rand -hex 24)|" \
  .env
```

(On macOS, write `sed -i ''`.) Then edit `.env` and set:

- `GODANCE_URL`, `GODANCE_FILES_URL` and `GODANCE_FILES_HOST`: your two host
  names (the URLs with `https://`, the files host also alone);
- `ACME_EMAIL`: where Let's Encrypt writes about certificates;
- `GODANCE_ADMIN_EMAILS`: the address you will sign up with;
- `REGION` (see [The regional allowlist](#6-the-regional-allowlist)), and the
  provider keys you have.

`grep -n '=change-me' .env` must print nothing: the stack refuses to start
while any placeholder is left. Back up `KEY_VAULT_KEY` somewhere other than the
server now (see [Security checklist](#8-security-checklist)).

> **A trial on one machine**, with no domain and no TLS: use these instead,
> and open `http://godance.localhost:8080`. Browsers and curl send any
> `*.localhost` name to the machine itself.
>
> ```ini
> GODANCE_URL=http://godance.localhost:8080
> GODANCE_FILES_URL=http://files.godance.localhost:8080
> GODANCE_FILES_HOST=files.godance.localhost
> HTTP_PORT=8080
> HTTPS_PORT=8443
> ```

**3. Build, one image at a time** (this keeps memory down on a small
server), then start:

```bash
export GIT_SHA=$(git rev-parse HEAD)    # shown as git_sha on /healthz
docker compose build api
docker compose build web
docker compose build minio              # bundled storage only
docker compose up -d
docker compose ps -a                    # every service healthy; preflight Exited (0)
curl -fsS https://godance.example.test/healthz
```

`/healthz` answers `{"status":"ok","region":…,"git_sha":…}`. On the first
start the API creates the database schema (Flyway) and the bucket; it is
healthy about a minute after `up`.

The app has `GODANCE_URL` built in: after changing it, run
`docker compose build web` again. The web container refuses to start with an
app built for another address, and says so in `docker compose logs web`.

**4. Make yourself the admin:**

1. Open `GODANCE_URL`, choose **Create an account**, and sign up with the
   address in `GODANCE_ADMIN_EMAILS`.
2. Verify the address: click the link in the email, or, with no email
   configured, find it in the log and open it in the same browser:
   ```bash
   docker compose logs api | grep "Verification URL"
   ```
3. Restart the API once. While no verified admin exists, it promotes the
   accounts in `GODANCE_ADMIN_EMAILS`:
   ```bash
   docker compose restart api
   docker compose logs api | grep "promoted"
   ```
4. Sign in again, then turn on two-factor sign-in under **Settings**. Every
   admin-console request needs it: until then they answer
   `403 two_factor_required`.

The admin console is at `GODANCE_URL/admin`. From then on, roles are given
and taken there, and `GODANCE_ADMIN_EMAILS` only matters again if no
verified admin is left.

**5. Fund an account and try the API.** In the admin console, **Users**,
find the account and adjust its credit (see [Billing](#7-billing)). Create an
API key under **API keys**, then:

```bash
curl https://godance.example.test/v1/chat/completions \
  -H "Authorization: Bearer $GODANCE_API_KEY" -H "Content-Type: application/json" \
  -d '{"model": "gpt-4o-mini", "messages": [{"role": "user", "content": "Hello"}]}'
```

OpenAI's SDKs work with `base_url` set to `GODANCE_URL/v1`. The Studio is at
`GODANCE_URL/create/image` and `/create/video`.

### What runs

| Service | What it is | Reached from |
|---|---|---|
| `web` | Caddy with the app built in: TLS, the app, and the API's routes (`/v1/*`, `/healthz`, `/actuator/*`, `/mcp`, `/oauth2/*`, `/login/oauth2/*`) | ports 80 and 443 |
| `api` | the Spring Boot API, `SPRING_PROFILES_ACTIVE=prod` | Caddy only |
| `postgres`, `redis` | data and rate-limit counters, on a network with no route out | the API only |
| `minio` | bundled object storage, on an internal network | Caddy only (the files host) |
| `preflight` | checks `.env`, then exits; the API starts only if it passed | — |

Containers keep at most 250 MB of logs each. Data lives in the volumes
`godance_postgres_data`, `godance_minio_data`, `godance_redis_data` and
`godance_caddy_data` (certificates).

### Object storage: bundled MinIO or your own

The API stores every generated image and video in an S3-compatible bucket
and gives them out as signed links on its storage endpoint. People's
browsers fetch those links, so the endpoint must be reachable from the
internet, and at the same address the API uses, since the host name is part
of each signature. That is why storage has its own host name, which also
keeps stored files off the app's origin, as production's DigitalOcean Spaces
does.

**Bundled MinIO** (the default, `COMPOSE_FILE=docker-compose.yml:docker-compose.minio.yml`):

- MinIO runs on an internal network with no published port; Caddy serves it
  as `GODANCE_FILES_URL`.
- From the internet, Caddy passes only `GET` and `HEAD` for objects in the
  bucket. Every other method, MinIO's own `/minio/*` routes and bucket
  listings are refused with 403.
- The API reaches the same host name over the internal `storage` network,
  where `GODANCE_FILES_HOST` resolves to Caddy, and Caddy lets that network
  (`STORAGE_SUBNET`) do anything. MinIO's console is off.
- MinIO publishes no images any more, so `docker compose build minio`
  compiles it from source with `backend/dev/minio/Dockerfile`, the file the
  development stack and tests use: release `RELEASE.2024-12-13T22-19-12Z`.
  MinIO is AGPL-3.0. Read its advisories in the
  [Security checklist](#minio-advisories) before you rely on it.

**Your own S3-compatible storage** (AWS S3, DigitalOcean Spaces, Cloudflare
R2, a MinIO you run elsewhere): in `.env`, set

```ini
COMPOSE_FILE=docker-compose.yml
OBJECT_STORAGE_ENDPOINT=https://<your provider's S3 endpoint>
OBJECT_STORAGE_REGION=<its region>
OBJECT_STORAGE_ACCESS_KEY=<key id>
OBJECT_STORAGE_SECRET_KEY=<secret>
OBJECT_STORAGE_BUCKET=<bucket>
OBJECT_STORAGE_PATH_STYLE=false      # true if your provider wants the bucket in the path
```

then `docker compose up -d --remove-orphans` (which also removes a bundled
MinIO container left from before). `GODANCE_FILES_*` and `STORAGE_SUBNET` are
then unused, and so is the second host name. The key needs to read, write,
list and delete objects in the bucket; the API creates the bucket at startup
if it is missing and the key may. Objects stay private: every link is
signed, for `OBJECT_STORAGE_PRESIGN_TTL` (7 days at most). Production uses
this mode, with DigitalOcean Spaces.

## 4. Configuration reference

Settings come from `.env`: Compose passes every line of it to the API, so
any variable below can be added there. Changing one needs
`docker compose up -d` (it recreates the API); changing `GODANCE_URL` also
needs `docker compose build web`. Settings without a variable of their own
take Spring Boot's name for them (the setting in capitals, `.` as `_`, no
`-`: `GODANCE_MCP_ENABLED=false` turns MCP off). Maps and lists marked `*`
are set in `backend/src/main/resources/application.yml` and need a rebuild of
the API image.

The compose file sets some for you, from `GODANCE_URL` or the stack's own
services: `DATABASE_URL`, `REDIS_HOST`, `CORS_ALLOWED_ORIGINS`,
`APP_BASE_URL`, `JWT_ISSUER`, `OAUTH_POST_LOGIN_REDIRECT_URI`, the four
payment success and cancel URLs and, with the bundled MinIO, the
`OBJECT_STORAGE_ENDPOINT`, `_REGION` and `_PATH_STYLE`. Its values win over
`.env`.

<!-- BEGIN GENERATED CONFIGURATION REFERENCE: do not edit by hand. Edit backend/src/test/resources/config-reference/descriptions.txt, then run backend/scripts/config-reference.sh -->

Generated from the code by `backend/scripts/config-reference.sh`: every `@ConfigurationProperties` field, every `${…}` placeholder in `application.yml` and `application-prod.yml`, and every `@Value` key, 194 settings in all. The default is what the `prod` profile gives. **required** means the API does not start without the variable (an empty value counts as set). `*` stands for any map key, `[*]` for any list item: those are set in `application.yml`, not the environment.

#### godance.admin

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.admin.emails` | `GODANCE_ADMIN_EMAILS` | empty | Comma-separated addresses promoted to ADMIN at startup, only while no active, verified account is an admin. Each must already be signed up and verified. After that, roles are changed in the admin console. |

#### godance.attribution

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.attribution.first-touch-limit-per-account` | `GODANCE_ATTRIBUTION_FIRSTTOUCHLIMITPERACCOUNT` | `20` | Recording calls one account may make per hour. |
| `godance.attribution.first-touch-window-hours` | `ATTRIBUTION_FIRST_TOUCH_WINDOW_HOURS` | `168` | How long after sign-up the app may record where an account came from (campaign tags, landing page); 0 never records it. |

#### godance.batches

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.batches.default-concurrency` | `BATCHES_DEFAULT_CONCURRENCY` | `2` | Rows a batch runs at once when the request names no number. |
| `godance.batches.max-concurrency` | `BATCHES_MAX_CONCURRENCY` | `4` | The most rows a batch may run at once. |
| `godance.batches.max-rows` | `BATCHES_MAX_ROWS` | `500` | The most rows one recipe batch may have; more answers 400 `batch_too_large`. |
| `godance.batches.max-unfinished-per-account` | `BATCHES_MAX_UNFINISHED` | `2` | Batches one account may have queued, running or paused. |
| `godance.batches.results-zip-max` | `BATCHES_RESULTS_ZIP_MAX` | `500MB` | The largest `results.zip`; past it the route answers 413 and points to the CSV. |
| `godance.batches.tick-cron` | `BATCHES_TICK_CRON` | `0/3 * * * * ?` | How often the batch runner starts rows (Quartz cron, with seconds). A cron that never fires, such as `0 0 0 1 1 ? 2099`, stops new rows; running jobs still finish. |

#### godance.billing

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.billing.currency` | `BILLING_CURRENCY` | `usd` | The currency of top-ups and the ledger, three letters as Stripe writes it (`usd`). |
| `godance.billing.image-markup-percent` | `IMAGE_MARKUP_PERCENT` | `5` | Platform fee on image models, in percent, for models with no markup of their own in the catalog. |
| `godance.billing.text-markup-percent` | `TEXT_MARKUP_PERCENT` | `5` | Platform fee on text models, in percent of the provider's cost. A model's own markup, set in the admin console's catalog, wins; `GET /v1/models` shows each model's fee as `markup_percent`. |
| `godance.billing.topup-max-cents` | `TOPUP_MAX_CENTS` | `100000` | The largest single top-up, in cents. |
| `godance.billing.topup-min-cents` | `TOPUP_MIN_CENTS` | `100` | The smallest top-up, in cents; at least 50 (Stripe's card minimum). |
| `godance.billing.video-markup-percent` | `VIDEO_MARKUP_PERCENT` | `5` | Platform fee on video models, in percent, for models with no markup of their own in the catalog. |

#### godance.binancepay

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.binancepay.enabled` | `BINANCE_PAY_ENABLED` | `false` | The Binance Pay rail, which is scaffolded and not live. Leave it false. |

#### godance.gateway

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.gateway.fallback-chains.*` | — | defined in application-prod.yml | For a model, the models tried in order when it answers 5xx or 429 or times out. Each one passes the regional allowlist again. Set in `application.yml`. |

#### godance.images

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.images.max-inline-bytes` | `IMAGES_MAX_INLINE_BYTES` | `25MB` | The most image data one response carries as `b64_json`; larger results answer with links. |
| `godance.images.new-key-response` | `IMAGES_NEW_KEY_RESPONSE` | `async` | What a new API key gets when its creation names nothing: `async` (a job to poll) or `sync` (image requests wait and answer like OpenAI's API). Existing keys never change. |
| `godance.images.sync-poll-interval` | `GODANCE_IMAGES_SYNCPOLLINTERVAL` | `1s` | How often a waiting `sync` request reads its job. |
| `godance.images.sync-wait` | `IMAGES_SYNC_WAIT` | `90s` | The longest a `sync` image request waits before answering 202 with the job; at most 300s, the proxy's limit. |

#### godance.inputs

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.inputs.allow-people` | `INPUTS_ALLOW_PEOPLE` | `false` | Whether an upload may show a person. False: every upload is checked, and one that shows a person or cannot be checked is refused. |
| `godance.inputs.check-model` | `INPUTS_CHECK_MODEL` | empty | The vision chat model that runs the person check. Empty: no check, so with `allow-people` false every upload answers 503 `input_check_unavailable`. |
| `godance.inputs.enabled` | `INPUTS_ENABLED` | `false` | Input files: images an account uploads to use in a generation. Off: no upload, no terms acceptance and no request that names an input is accepted (`inputs_disabled`). |
| `godance.inputs.max-bytes` | `INPUTS_MAX_BYTES` | `20MB` | The largest input file; keep `spring.webflux.multipart.max-disk-usage-per-part` just above it. |
| `godance.inputs.max-files-per-account` | `INPUTS_MAX_FILES_PER_ACCOUNT` | `200` | Files one account may have stored at once, used or not. |
| `godance.inputs.max-megapixels` | `INPUTS_MAX_MEGAPIXELS` | `40` | The most pixels an input image may have, in millions. |
| `godance.inputs.provider-url-ttl` | `GODANCE_INPUTS_PROVIDERURLTTL` | `1h` | How long a link lasts when it is handed to a provider that fetches inputs by URL. |
| `godance.inputs.signed-url-ttl` | `GODANCE_INPUTS_SIGNEDURLTTL` | `5m` | How long a link lasts when an input's owner reads it. |
| `godance.inputs.terms-version` | `INPUTS_TERMS_VERSION` | `1` | The version of the input terms; raise it when the text changes, and every account accepts again. |
| `godance.inputs.unused-ttl-hours` | `INPUTS_UNUSED_TTL_HOURS` | `24` | How long an uploaded file that nothing uses is kept. |
| `godance.inputs.upload-limit-per-account` | `GODANCE_INPUTS_UPLOADLIMITPERACCOUNT` | `60` | Uploads one account may try per hour. |
| `godance.inputs.upload-limit-per-ip` | `GODANCE_INPUTS_UPLOADLIMITPERIP` | `120` | Uploads one client address may try per hour. |

#### godance.jwt

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.jwt.expiry-minutes` | `JWT_EXPIRY_MINUTES` | `60` | How long a session lasts, in minutes. |
| `godance.jwt.issuer` | `JWT_ISSUER` | `https://godance.ai` | The `iss` claim of session tokens; a URI. The self-host compose file sets it to `GODANCE_URL`. |
| `godance.jwt.secret` | `JWT_SIGNING_KEY` | **required** | Signs session tokens (HS256); at least 32 bytes. Changing it signs everyone out. |

#### godance.key-vault

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.key-vault.key` | `KEY_VAULT_KEY` | **required** | Encrypts the secrets kept in the database (authenticator secrets for two-factor sign-in) with AES-256: 32 random bytes in Base64. Never change or lose it: without it nobody with two-factor sign-in on can sign in. Back it up apart from the database. |

#### godance.mail

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.mail.api-base` | `RESEND_API_BASE` | `https://api.resend.com` | Resend's API address. |
| `godance.mail.api-key` | `RESEND_API_KEY` | `re_dev_dummy_key_do_not_use_for_real_calls` | Resend API key. Empty or the dummy default: nothing is sent, and each message's first line (verification and password-reset links included) is written to the API's log. |
| `godance.mail.app-base-url` | `APP_BASE_URL` | `https://app.godance.ai` | The app's origin, for links in emails. The self-host compose file sets it to `GODANCE_URL`. |
| `godance.mail.from` | `EMAIL_FROM` | `Godance <noreply@godance.ai>` | The sender of every email, on a domain verified in Resend. |

#### godance.mcp

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.mcp.enabled` | `GODANCE_MCP_ENABLED` | `true` | The MCP server at `/mcp`, which needs an API key. False takes the route away (404). |
| `godance.mcp.gallery-cache-ttl` | `GODANCE_MCP_GALLERYCACHETTL` | `5m` | How long the gallery index is kept before it is read again. |
| `godance.mcp.gallery-timeout` | `GODANCE_MCP_GALLERYTIMEOUT` | `5s` | How long one read of the gallery may take. |
| `godance.mcp.gallery-url` | `GODANCE_MCP_GALLERYURL` | `https://godance.ai/gallery` | Where the MCP tools `search_recipes` and `get_recipe` read the gallery (`<url>/index.json`, `<url>/<slug>.json`). |
| `godance.mcp.rate-limit.requests` | `GODANCE_MCP_RATELIMIT_REQUESTS` | `120` | MCP requests one API key may make per window; off with `godance.rate-limit.enabled`. |
| `godance.mcp.rate-limit.window` | `GODANCE_MCP_RATELIMIT_WINDOW` | `1m` | The window MCP requests are counted over. |

#### godance.nowpayments

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.nowpayments.api-base` | `NOWPAYMENTS_API_BASE` | `https://api.nowpayments.io` | NOWPayments' API address (`https://api-sandbox.nowpayments.io` for its sandbox). |
| `godance.nowpayments.api-key` | `NOWPAYMENTS_API_KEY` | **required** | NOWPayments API key, for USDT top-ups. Empty: USDT top-ups fail. |
| `godance.nowpayments.cancel-url` | `NOWPAYMENTS_CANCEL_URL` | **required** | Where NOWPayments sends the user who cancels. The self-host compose file derives it from `GODANCE_URL`. |
| `godance.nowpayments.ipn-secret` | `NOWPAYMENTS_IPN_SECRET` | **required** | Verifies NOWPayments' payment notifications (IPN). An empty secret refuses every notification. The handler credits whatever a validly signed notification says: use the secret NOWPayments issued, never a made-up value. |
| `godance.nowpayments.success-url` | `NOWPAYMENTS_SUCCESS_URL` | **required** | Where NOWPayments sends the user after paying. The self-host compose file derives it from `GODANCE_URL`. |

#### godance.oauth

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.oauth.apple.client-id` | `APPLE_OAUTH_CLIENT_ID` | `oauth-not-configured` | Sign in with Apple's Services ID. Apple is offered only with all four Apple settings real. Its redirect URI is `<GODANCE_URL>/login/oauth2/code/apple`. |
| `godance.oauth.apple.key-id` | `APPLE_OAUTH_KEY_ID` | `oauth-not-configured` | The id of your Sign in with Apple key. |
| `godance.oauth.apple.private-key` | `APPLE_OAUTH_PRIVATE_KEY` | empty | The PEM body of that key's `.p8` file; the API signs Apple's client secret with it. |
| `godance.oauth.apple.team-id` | `APPLE_OAUTH_TEAM_ID` | `oauth-not-configured` | Your Apple developer team id. |
| `godance.oauth.google.client-id` | `GOOGLE_OAUTH_CLIENT_ID` | `oauth-not-configured` | Sign-in with Google. Offered only when the id and secret are real (not blank, not a placeholder such as `oauth-not-configured` or one starting `dev-`). Its redirect URI is `<GODANCE_URL>/login/oauth2/code/google`. |
| `godance.oauth.google.client-secret` | `GOOGLE_OAUTH_CLIENT_SECRET` | `oauth-not-configured` | Google's client secret; see `client-id`. |
| `godance.oauth.post-login-redirect-uri` | `OAUTH_POST_LOGIN_REDIRECT_URI` | `https://app.godance.ai/oauth/callback` | Where the app receives the session after a social sign-in. The self-host compose file sets `<GODANCE_URL>/oauth/callback`. |
| `godance.oauth.x.client-id` | `X_OAUTH_CLIENT_ID` | `oauth-not-configured` | Sign-in with X, on the same rule as Google's. Its redirect URI is `<GODANCE_URL>/login/oauth2/code/x`. |
| `godance.oauth.x.client-secret` | `X_OAUTH_CLIENT_SECRET` | `oauth-not-configured` | X's client secret; see `client-id`. |

#### godance.object-storage

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.object-storage.access-key` | `OBJECT_STORAGE_ACCESS_KEY` | **required** | The storage access key id. |
| `godance.object-storage.bucket` | `OBJECT_STORAGE_BUCKET` | **required** | The bucket for every stored file; the API creates it at startup if it is missing. |
| `godance.object-storage.endpoint` | `OBJECT_STORAGE_ENDPOINT` | **required** | The S3-compatible storage endpoint. The API stores files there and signs links to them with this address, so people's browsers must reach it too. `docker-compose.minio.yml` sets it to `GODANCE_FILES_URL`. |
| `godance.object-storage.path-style-access` | `OBJECT_STORAGE_PATH_STYLE` | `false` | True puts the bucket in the path (MinIO); false in the host name (AWS S3, DigitalOcean Spaces). |
| `godance.object-storage.presign-ttl` | `OBJECT_STORAGE_PRESIGN_TTL` | `7d` | How long links to generated files last; at most 7d. |
| `godance.object-storage.region` | `OBJECT_STORAGE_REGION` | `us-east-1` | The region the S3 request signer names; MinIO accepts any. |
| `godance.object-storage.secret-key` | `OBJECT_STORAGE_SECRET_KEY` | **required** | The storage secret key. |

#### godance.pricing

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.pricing.alias-map.litellm.*` | — | defined in application.yml | Maps a LiteLLM model id to Godance's model name, for the pricing check. Set in `application.yml`. |
| `godance.pricing.alias-map.openrouter.*` | — | defined in application.yml | Maps an OpenRouter model id to Godance's model name, for the pricing check. Set in `application.yml`. |
| `godance.pricing.fallback-pages[*].provider` | — | none | As `llm-fallback-model-id`: no effect in this build. |
| `godance.pricing.fallback-pages[*].url` | — | none | As `llm-fallback-model-id`: no effect in this build. |
| `godance.pricing.feeds.litellm-url` | `PRICING_LITELLM_URL` | `https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json` | Where the pricing check reads LiteLLM's price list. |
| `godance.pricing.feeds.openrouter-url` | `PRICING_OPENROUTER_URL` | `https://openrouter.ai/api/v1/models` | Where the pricing check reads OpenRouter's model prices. |
| `godance.pricing.llm-fallback-fetch-timeout` | `PRICING_LLM_FALLBACK_FETCH_TIMEOUT` | `15s` | As `llm-fallback-model-id`: no effect in this build. |
| `godance.pricing.llm-fallback-max-pages-per-run` | `PRICING_LLM_FALLBACK_MAX_PAGES` | `20` | As `llm-fallback-model-id`: no effect in this build. |
| `godance.pricing.llm-fallback-model-id` | `PRICING_LLM_FALLBACK_MODEL` | `gemini-3.1-flash-lite` | For reading providers' pricing pages with a model. The reader in this build does nothing, so this has no effect. |
| `godance.pricing.max-multiplier` | `PRICING_MAX_MULTIPLIER` | `10.0` | A new price more than this many times the old one is an anomaly, not a change. |
| `godance.pricing.min-microcents` | `PRICING_MIN_MICROCENTS` | `1` | A new price at or below this, in microcents (1 cent is 10,000), is an anomaly, not a change. |
| `godance.pricing.noise-threshold-pct` | `PRICING_NOISE_THRESHOLD_PCT` | `1.0` | A price change smaller than this, in percent, is logged and not proposed. |
| `godance.pricing.page-allowlist-regex` | `GODANCE_PRICING_PAGEALLOWLISTREGEX` | empty | As `llm-fallback-model-id`: no effect in this build. |
| `godance.pricing.refresh-cron` | `PRICING_REFRESH_CRON` | `0 0 4 * * ?` | When the daily pricing check runs (Quartz cron, UTC). It runs only with `REGION=digitalocean`: it reads the price feeds below and stages each change for an admin to approve in the admin console. |

#### godance.promo

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.promo.referral.claim-limit-per-account` | `GODANCE_PROMO_REFERRAL_CLAIMLIMITPERACCOUNT` | `5` | Claims one account may try per hour. |
| `godance.promo.referral.claim-limit-per-ip` | `GODANCE_PROMO_REFERRAL_CLAIMLIMITPERIP` | `20` | Claims one client address may try per hour. |
| `godance.promo.referral.claim-window-hours` | `PROMO_REFERRAL_CLAIM_WINDOW_HOURS` | `168` | How long after signing up an account may claim a code. |
| `godance.promo.referral.min-topup-cents` | `PROMO_REFERRAL_MIN_TOPUP_CENTS` | `1000` | The smallest top-up, in cents, that earns the rewards. |
| `godance.promo.referral.monthly-reward-limit` | `PROMO_REFERRAL_MONTHLY_REWARD_LIMIT` | `20` | The most rewards one referrer earns in a UTC month; past it only the invitee is credited. |
| `godance.promo.referral.qualifying-sources` | `PROMO_REFERRAL_QUALIFYING_SOURCES` | `STRIPE` | The top-up rails that earn a reward, comma-separated: `STRIPE` (card) and `NOWPAYMENTS` (USDT). |
| `godance.promo.referral.referee-reward-cents` | `PROMO_REFERRAL_REFEREE_REWARD_CENTS` | `0` | Credit, in cents, to the invited account on that top-up. |
| `godance.promo.referral.referrer-reward-cents` | `PROMO_REFERRAL_REFERRER_REWARD_CENTS` | `0` | Credit, in cents, to an account whose invitee makes a first qualifying top-up. With both rewards 0 referrals are off: no code is made and every claim is refused. |
| `godance.promo.signup-credit-cents` | `PROMO_SIGNUP_CREDIT_CENTS` | `0` | A one-time credit, in cents, when an account's email is first verified. Real provider spend; 0 turns it off. |
| `godance.promo.signup-credit-daily-limit` | `PROMO_SIGNUP_CREDIT_DAILY_LIMIT` | `200` | The most signup credits granted in one UTC day. |

#### godance.providers

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.providers.alibaba.api-base` | `ALIBABA_API_BASE` | `https://dashscope-intl.aliyuncs.com/compatible-mode` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.alibaba.api-key` | `ALIBABA_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.alibaba.timeout` | `ALIBABA_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.anthropic.api-base` | `ANTHROPIC_API_BASE` | `https://api.anthropic.com` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.anthropic.api-key` | `ANTHROPIC_API_KEY` | **required** | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.anthropic.api-version` | `ANTHROPIC_API_VERSION` | `2023-06-01` | The `anthropic-version` header sent with every request to Anthropic. |
| `godance.providers.anthropic.timeout` | `ANTHROPIC_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.baidu.api-base` | `BAIDU_API_BASE` | `https://qianfan.baidubce.com/v2` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.baidu.api-key` | `BAIDU_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.baidu.timeout` | `BAIDU_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.bfl.api-base` | `BFL_API_BASE` | `https://api.bfl.ai` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.bfl.api-key` | `BFL_API_KEY` | **required** | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.bfl.poll-interval` | `BFL_POLL_INTERVAL` | `2s` | How often a running image or video job is checked at the provider. |
| `godance.providers.bfl.poll-timeout` | `BFL_POLL_TIMEOUT` | `300s` | How long Godance waits for a job at the provider before giving up on it. |
| `godance.providers.deepseek.api-base` | `DEEPSEEK_API_BASE` | `https://api.deepseek.com` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.deepseek.api-key` | `DEEPSEEK_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.deepseek.timeout` | `DEEPSEEK_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.google.api-base` | `GOOGLE_API_BASE` | `https://generativelanguage.googleapis.com` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.google.api-key` | `GOOGLE_API_KEY` | **required** | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.google.timeout` | `GOOGLE_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.kling.api-base` | `KLING_API_BASE` | `https://api-singapore.klingai.com` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.kling.api-key` | `KLING_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.kling.poll-interval` | `KLING_POLL_INTERVAL` | `5s` | How often a running image or video job is checked at the provider. |
| `godance.providers.kling.poll-timeout` | `KLING_POLL_TIMEOUT` | `600s` | How long Godance waits for a job at the provider before giving up on it. |
| `godance.providers.minimax.api-base` | `MINIMAX_API_BASE` | `https://api.minimax.io` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.minimax.api-key` | `MINIMAX_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.minimax.timeout` | `MINIMAX_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.moonshot.api-base` | `MOONSHOT_API_BASE` | `https://api.moonshot.ai` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.moonshot.api-key` | `MOONSHOT_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.moonshot.timeout` | `MOONSHOT_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.openai.api-base` | `OPENAI_API_BASE` | `https://api.openai.com` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.openai.api-key` | `OPENAI_API_KEY` | **required** | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.openai.timeout` | `OPENAI_TIMEOUT` | `30s` | How long one request to the provider may take. |
| `godance.providers.runway.api-base` | `RUNWAY_API_BASE` | `https://api.dev.runwayml.com` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.runway.api-key` | `RUNWAY_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.runway.poll-interval` | `RUNWAY_POLL_INTERVAL` | `5s` | How often a running image or video job is checked at the provider. |
| `godance.providers.runway.poll-timeout` | `RUNWAY_POLL_TIMEOUT` | `600s` | How long Godance waits for a job at the provider before giving up on it. |
| `godance.providers.stability.api-base` | `STABILITY_API_BASE` | `https://api.stability.ai` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.stability.api-key` | `STABILITY_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.stability.timeout` | `STABILITY_TIMEOUT` | `90s` | How long one request to the provider may take. |
| `godance.providers.volcengine.api-base` | `VOLCENGINE_API_BASE` | `https://ark.cn-beijing.volces.com` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.volcengine.api-key` | `VOLCENGINE_API_KEY` | **required** | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.volcengine.poll-interval` | `VOLCENGINE_POLL_INTERVAL` | `5s` | How often a running image or video job is checked at the provider. |
| `godance.providers.volcengine.poll-timeout` | `VOLCENGINE_POLL_TIMEOUT` | `600s` | How long Godance waits for a job at the provider before giving up on it. |
| `godance.providers.xai.api-base` | `XAI_API_BASE` | `https://api.x.ai` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.xai.api-key` | `XAI_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.xai.timeout` | `XAI_TIMEOUT` | `60s` | How long one request to the provider may take. |
| `godance.providers.zhipu.api-base` | `ZHIPU_API_BASE` | `https://open.bigmodel.cn/api/paas/v4` | The provider's API address. Change it only to go through a proxy, or to a stand-in when testing. |
| `godance.providers.zhipu.api-key` | `ZHIPU_API_KEY` | empty | The platform's key for this provider, used for every account's requests. Empty: the provider's models are still listed, and requests to them fail at the provider. |
| `godance.providers.zhipu.timeout` | `ZHIPU_TIMEOUT` | `60s` | How long one request to the provider may take. |

#### godance.rate-limit

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.rate-limit.enabled` | `GODANCE_RATELIMIT_ENABLED` | `true` | Switches every limit in this group, and the MCP limit, on or off. The limits count in Redis; past one the API answers 429 with `Retry-After`, and if Redis cannot be reached requests pass. |
| `godance.rate-limit.forgot-per-email.requests` | `GODANCE_RATELIMIT_FORGOTPEREMAIL_REQUESTS` | `5` | Password-reset emails per address. |
| `godance.rate-limit.forgot-per-email.window` | `GODANCE_RATELIMIT_FORGOTPEREMAIL_WINDOW` | `24h` | The window this limit counts over. |
| `godance.rate-limit.forgot-per-ip.requests` | `GODANCE_RATELIMIT_FORGOTPERIP_REQUESTS` | `10` | Password-reset requests per client address. |
| `godance.rate-limit.forgot-per-ip.window` | `GODANCE_RATELIMIT_FORGOTPERIP_WINDOW` | `1h` | The window this limit counts over. |
| `godance.rate-limit.login-per-email.requests` | `GODANCE_RATELIMIT_LOGINPEREMAIL_REQUESTS` | `10` | Sign-in attempts per email address. |
| `godance.rate-limit.login-per-email.window` | `GODANCE_RATELIMIT_LOGINPEREMAIL_WINDOW` | `15m` | The window this limit counts over. |
| `godance.rate-limit.login-per-ip.requests` | `GODANCE_RATELIMIT_LOGINPERIP_REQUESTS` | `30` | Sign-in attempts per client address. |
| `godance.rate-limit.login-per-ip.window` | `GODANCE_RATELIMIT_LOGINPERIP_WINDOW` | `15m` | The window this limit counts over. |
| `godance.rate-limit.release-link-per-email.requests` | `GODANCE_RATELIMIT_RELEASELINKPEREMAIL_REQUESTS` | `5` | Release links per email address. |
| `godance.rate-limit.release-link-per-email.window` | `GODANCE_RATELIMIT_RELEASELINKPEREMAIL_WINDOW` | `24h` | The window this limit counts over. |
| `godance.rate-limit.release-link-per-ip.requests` | `GODANCE_RATELIMIT_RELEASELINKPERIP_REQUESTS` | `10` | Requests for a link that releases an email address from an account, per client address. |
| `godance.rate-limit.release-link-per-ip.window` | `GODANCE_RATELIMIT_RELEASELINKPERIP_WINDOW` | `1h` | The window this limit counts over. |
| `godance.rate-limit.resend-per-account.requests` | `GODANCE_RATELIMIT_RESENDPERACCOUNT_REQUESTS` | `5` | Verification resends per account. |
| `godance.rate-limit.resend-per-account.window` | `GODANCE_RATELIMIT_RESENDPERACCOUNT_WINDOW` | `24h` | The window this limit counts over. |
| `godance.rate-limit.resend-per-ip.requests` | `GODANCE_RATELIMIT_RESENDPERIP_REQUESTS` | `10` | Verification resends per client address. |
| `godance.rate-limit.resend-per-ip.window` | `GODANCE_RATELIMIT_RESENDPERIP_WINDOW` | `1h` | The window this limit counts over. |
| `godance.rate-limit.signup-per-ip.requests` | `GODANCE_RATELIMIT_SIGNUPPERIP_REQUESTS` | `10` | Sign-ups per client address (each sends a verification email). |
| `godance.rate-limit.signup-per-ip.window` | `GODANCE_RATELIMIT_SIGNUPPERIP_WINDOW` | `1h` | The window this limit counts over. |
| `godance.rate-limit.two-factor-per-account.requests` | `GODANCE_RATELIMIT_TWOFACTORPERACCOUNT_REQUESTS` | `10` | Two-factor codes tried, and changes to two-factor sign-in, per account. |
| `godance.rate-limit.two-factor-per-account.window` | `GODANCE_RATELIMIT_TWOFACTORPERACCOUNT_WINDOW` | `15m` | The window this limit counts over. |
| `godance.rate-limit.two-factor-per-ip.requests` | `GODANCE_RATELIMIT_TWOFACTORPERIP_REQUESTS` | `30` | Two-factor codes tried, and changes to two-factor sign-in, per client address. |
| `godance.rate-limit.two-factor-per-ip.window` | `GODANCE_RATELIMIT_TWOFACTORPERIP_WINDOW` | `15m` | The window this limit counts over. |
| `godance.rate-limit.verify-per-ip.requests` | `GODANCE_RATELIMIT_VERIFYPERIP_REQUESTS` | `30` | Email-verification attempts per client address. |
| `godance.rate-limit.verify-per-ip.window` | `GODANCE_RATELIMIT_VERIFYPERIP_WINDOW` | `15m` | The window this limit counts over. |

#### godance.recipes

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.recipes.max-per-account` | `RECIPES_MAX_PER_ACCOUNT` | `500` | The most recipes one account can have; past it saving answers 409 `recipe_limit_reached`. |
| `godance.recipes.max-versions` | `RECIPES_MAX_VERSIONS` | `200` | The most versions one recipe can have. |

#### godance.region

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.region` | `REGION` | **required** | Where this deployment runs, any case: `digitalocean`, `alicloud` or `local`; anything else stops startup. `alicloud` dispatches only to providers on the fixed Alicloud allowlist and answers 503 for the rest. `digitalocean` also runs the daily pricing check. See "The regional allowlist". |

#### godance.retention

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.retention.saved-limit` | `RETENTION_SAVED_LIMIT` | `500` | The most generations one account can save. Saved generations are kept past the 90-day deletion for as long as the account exists; 0 means nothing can be saved. |

#### godance.scheduling

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.scheduling.auto-recharge-cron` | `GODANCE_SCHEDULING_AUTORECHARGECRON` | `0 0 3 * * ?` | When the auto-recharge sweep runs (Quartz cron, UTC). It charges saved cards of accounts below their threshold that the per-request check missed; it needs Stripe. |
| `godance.scheduling.data-retention-cron` | `GODANCE_SCHEDULING_DATARETENTIONCRON` | `0 30 3 * * ?` | When the retention job runs (Quartz cron, UTC). It deletes generated media and job prompts 90 days after the job, except saved generations. |
| `godance.scheduling.job-sweep-cron` | `GODANCE_SCHEDULING_JOBSWEEPCRON` | `0 */2 * * * ?` | How often image and video jobs whose worker stopped (a restart ends them) are failed and refunded. |
| `godance.scheduling.referral-sweep-cron` | `GODANCE_SCHEDULING_REFERRALSWEEPCRON` | `0 17 * * * ?` | When referral rewards the top-up path missed are paid; nothing happens while referrals are off. |

#### godance.security

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.security.cors-allowed-origins` | `CORS_ALLOWED_ORIGINS` | `https://app.godance.ai` | Browser origins, comma-separated, that may call `/v1/**` from script; at least one, each an absolute http(s) URL. The self-host compose file sets it to `GODANCE_URL`. |
| `godance.security.session-cookie-secure` | `GODANCE_SECURITY_SESSIONCOOKIESECURE` (`SESSION_COOKIE_SECURE` works only without the prod profile) | `true` | Marks the session cookie Secure (it is then named `__Host-godance_session`; always `HttpOnly`, `SameSite=Strict`). The prod profile sets true. |

#### godance.stripe

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.stripe.api-base` | `STRIPE_API_BASE` | empty | Empty: Stripe's API. Set it only to point tests at a stand-in. |
| `godance.stripe.cancel-url` | `STRIPE_CANCEL_URL` | **required** | Where Stripe Checkout sends the user who cancels. The self-host compose file derives it from `GODANCE_URL`. |
| `godance.stripe.secret-key` | `STRIPE_SECRET_KEY` | **required** | Stripe secret key, for card top-ups and auto-recharge. Empty: those fail, and an admin funds accounts instead. |
| `godance.stripe.success-url` | `STRIPE_SUCCESS_URL` | **required** | Where Stripe Checkout sends the user after paying. The self-host compose file derives it from `GODANCE_URL`. |
| `godance.stripe.webhook-secret` | `STRIPE_WEBHOOK_SECRET` | **required** | Verifies Stripe's webhook signatures. An empty secret refuses every webhook. The handler credits whatever a validly signed event says: use the `whsec_` secret Stripe shows for your endpoint, never a made-up value. |

#### godance.webhooks

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `godance.webhooks.allow-private-destinations` | `GODANCE_WEBHOOKS_ALLOWPRIVATEDESTINATIONS` | `false` | True lets job-completion webhooks go to private addresses and plain http. For tests with a local receiver; leave it false. |

#### Spring Boot

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `server.port` | `PORT` | `8080` | The API's port inside its container. Leave 8080: the image's health check and Caddy expect it. |
| `spring.data.redis.host` | `REDIS_HOST` | **required** | Redis's host name. The compose file sets `redis`. |
| `spring.data.redis.password` | `REDIS_PASSWORD` | empty | Redis's password. The bundled Redis has none and sits on a network only the API reaches. |
| `spring.data.redis.port` | `REDIS_PORT` | `6379` | Redis's port. |
| `spring.datasource.hikari.maximum-pool-size` | `DB_POOL_MAX` | `25` | The most database connections the API opens. |
| `spring.datasource.hikari.minimum-idle` | `DB_POOL_MIN` | `5` | Database connections kept open when idle. |
| `spring.datasource.password` | `DATABASE_PASSWORD` | **required** | The database password. The compose file also gives it to Postgres. |
| `spring.datasource.url` | `DATABASE_URL` | **required** | The JDBC URL of the database. The compose file sets the bundled Postgres. |
| `spring.datasource.username` | `DATABASE_USERNAME` | **required** | The database user. The compose file also gives it to Postgres as its owner. |
| `spring.webflux.multipart.max-disk-usage-per-part` | `INPUTS_MAX_PART_BYTES` | `21MB` | The largest part of a multipart upload; keep it just above `godance.inputs.max-bytes`. |

#### The container

| Setting | Environment variable | Default | What it does |
|---|---|---|---|
| `GODANCE_GIT_SHA` | `GODANCE_GIT_SHA` | empty | The commit `/healthz` reports as `git_sha`. The image takes it from the `GIT_SHA` build argument. |

<!-- END GENERATED CONFIGURATION REFERENCE -->

**Keeping this reference true.** It is generated from the code by
`backend/scripts/config-reference.sh`, and `ConfigurationReferenceTest` (part
of `mvn verify`, so CI's backend job) fails when a setting is added,
removed or changed without it. To fix that failure, describe each new setting
in `backend/src/test/resources/config-reference/descriptions.txt`, run the
script, and commit both files. When two branches each add settings,
whichever merges second runs it after merging.

## 5. Provider keys

Each provider has one platform key, in `.env`, used for every account's
requests. Keys stay in the environment; nothing writes them to the database.

| Provider | Variable | Models (examples) |
|---|---|---|
| OpenAI | `OPENAI_API_KEY` | GPT text models, GPT Image, Sora |
| Anthropic | `ANTHROPIC_API_KEY` | Claude |
| Google | `GOOGLE_API_KEY` | Gemini, Imagen, Veo |
| Black Forest Labs | `BFL_API_KEY` | FLUX |
| Volcengine Ark | `VOLCENGINE_API_KEY` | Doubao, Seedream, Seedance |
| DeepSeek | `DEEPSEEK_API_KEY` | DeepSeek |
| Moonshot | `MOONSHOT_API_KEY` | Kimi |
| Alibaba DashScope | `ALIBABA_API_KEY` | Qwen, Qwen-Image |
| Zhipu | `ZHIPU_API_KEY` | GLM |
| MiniMax | `MINIMAX_API_KEY` | MiniMax, Hailuo |
| xAI | `XAI_API_KEY` | Grok |
| Baidu | `BAIDU_API_KEY` | ERNIE |
| Stability AI | `STABILITY_API_KEY` | Stable Diffusion XL |
| Kling | `KLING_API_KEY` | Kling |
| Runway | `RUNWAY_API_KEY` | Gen-4 |

`GET /v1/models` (and the admin console's catalog) lists each model with its
provider.

**A provider with no key still has its models listed.** Nothing hides them:
the app's model pickers and `GET /v1/models` show every active model in the
catalog, whatever keys are set. A request to one goes to the provider without
a key and fails there. If that model has a fallback chain
(`godance.gateway.fallback-chains`), the API then tries the next model in it,
so the answer can come from another provider whose key you did set; the
request is billed at the price of the model that answered. When every model
in the chain fails, the request fails.

To stop offering a provider's models, an admin switches them off in the
admin console, under **Catalog** (`PATCH /v1/admin/pricing/models/{id}` with
`{"enabled": false}`). A model switched off leaves `GET /v1/models` and is
refused.

## 6. The regional allowlist

`REGION` says where the deployment runs. It is required: the API does not
start without it, or with a value other than `digitalocean`, `alicloud` or
`local`.

- **`alicloud`**: the API dispatches only to providers on the Alicloud
  allowlist: DeepSeek, Moonshot, Alibaba DashScope, Zhipu, MiniMax,
  Volcengine Ark, Baidu and Kling. A request for any other provider's model
  answers 503 and reaches no provider. Fallback chains are checked against the
  list at every step, so a fallback never leaves it.
- **`digitalocean`**: every provider may be used, and the daily pricing check
  runs (see `godance.pricing.*`). The name is production's; any deployment
  outside Chinese-owned infrastructure can use it.
- **`local`**: as `digitalocean`, without the pricing check.

**It cannot be turned off.** The allowlist is fixed in the code
(`AlicloudProviderAllowlist`), and the check sits in the gateways that
dispatch every chat, image and video request. No setting changes the list or
skips the check. Anthropic, OpenAI and Google's terms of service restrict
serving their models from Chinese-owned infrastructure, and the code enforces
that rather than trusting configuration, which can be mis-set. So `REGION`
must say truthfully where you run: on Chinese-owned infrastructure, set
`alicloud`.

## 7. Billing

Godance keeps one append-only credit ledger in integer cents. Every request
is priced and charged to the account's balance; a request costing a fraction
of a cent carries the fraction to the account's next request.

**Payment providers are optional.** Stripe (cards) and NOWPayments (USDT)
are the two top-up rails. Leave their four variables empty to run without
them: top-ups and auto-recharge then fail, and the billing pages still offer
them.

**Without them, an admin funds accounts.** In the admin console, **Users**,
search for the account and enter an amount and a note; the API behind it is

```bash
curl -X POST "$GODANCE_URL/v1/admin/users/<user id>/credit-adjust" \
  -H "Authorization: Bearer <an admin's session token>" -H "Content-Type: application/json" \
  -d '{"amountCents": 500, "note": "first credit", "idempotencyKey": "<a fresh UUID>"}'
```

It needs the `ADMIN` or `FINANCE` role and two-factor sign-in, and refuses
API keys. The adjustment is a ledger entry like any other (source
`ADMIN_ADJUSTMENT`), with an audit record naming the admin, so the balance
and history stay in the one ledger. A negative amount takes credit away. The
same `idempotencyKey` sent twice applies once.

**Webhook secrets matter even when a rail is unused.** The payment webhooks
(`/v1/billing/webhooks/stripe`, `/v1/billing/webhooks/nowpayments`) are public,
and they credit whatever a validly signed message says. So a webhook secret
must be either the one your provider issued or empty:

- **Empty** refuses every webhook. Tested: a Stripe event and a NOWPayments
  notification signed with an empty key were refused (Stripe 400; NOWPayments
  500, logged as `HmacSHA512 unavailable; JVM broken`, which here only means
  the secret is empty), and no credit was added.
- **Never a made-up value.** A value someone can guess, such as an example
  placeholder, lets anyone add credit to any account. The `.env` check refuses
  a webhook secret under 24 characters, and a payment key without its
  webhook secret.

To take payments, set the provider's keys and register the webhook:
`GODANCE_URL/v1/billing/webhooks/stripe` in Stripe's dashboard, subscribed to
`checkout.session.completed`, `checkout.session.async_payment_succeeded` and
`checkout.session.async_payment_failed`, with its signing secret as
`STRIPE_WEBHOOK_SECRET`; `GODANCE_URL/v1/billing/webhooks/nowpayments` as the
IPN callback in NOWPayments, with its IPN secret as `NOWPAYMENTS_IPN_SECRET`.

**The platform fee** is a markup on the provider's cost, in percent, by
modality: `TEXT_MARKUP_PERCENT`, `IMAGE_MARKUP_PERCENT` and
`VIDEO_MARKUP_PERCENT` (5 each by default). A model's own markup, set in the
admin console's catalog, wins over its modality's. `GET /v1/models` shows
each model's fee as `markup_percent`. Top-ups are bounded by
`TOPUP_MIN_CENTS` and `TOPUP_MAX_CENTS`; signup and referral credits are off
until you give them an amount (`godance.promo.*`).

## 8. Security checklist

- [ ] **Secrets live in `.env`, and only there.** It is `chmod 600`, never
  committed (the repository ignores it), and holds `JWT_SIGNING_KEY`,
  `KEY_VAULT_KEY`, the database and storage passwords, provider keys and
  payment secrets. Compose gives it to the API as environment variables.
  `preflight` refuses placeholders and unusable values before the API starts.
- [ ] **`KEY_VAULT_KEY` is backed up apart from the database, and never
  changes.** The API encrypts the secrets it keeps in the database (each
  account's authenticator secret for two-factor sign-in) with it, using
  AES-256-GCM. Lose or change it and nobody with two-factor sign-in on can
  sign in. Passwords are stored as BCrypt hashes and API keys as SHA-256
  hashes. The rest of the database is stored as it is, including each
  account's signing secret for job webhooks: protect the disk.
- [ ] **The disk is encrypted at rest**, if your data needs it. Docker's
  volumes are ordinary directories on the server's disk. The operator's notes
  for DigitalOcean are in
  [`backend/deploy/encrypt-at-rest.md`](../backend/deploy/encrypt-at-rest.md).
- [ ] **`JWT_SIGNING_KEY` is long and random.** Changing it signs everyone
  out.
- [ ] **The session cookie** is `__Host-godance_session`: `HttpOnly`,
  `Secure`, `SameSite=Strict`, host-only. The `prod` profile, which the image
  runs, always sets it `Secure`; browsers accept that over plain HTTP only on
  `localhost` names, which is why the trial uses `*.localhost`.
- [ ] **CORS** allows only `GODANCE_URL` (the compose file sets it). The app
  is on that origin, so nothing else is needed. Add an origin to
  `CORS_ALLOWED_ORIGINS`, in `.env`, only for another browser app of yours.
- [ ] **Staff use two-factor sign-in.** Every admin-console request checks the
  account's role (`SUPPORT`, `FINANCE` or `ADMIN`) and that two-factor sign-in
  is on, and refuses API keys.
- [ ] **Only Caddy is reachable.** It is the only service with published
  ports; the API sits behind it, Postgres and Redis on a network with no route
  out, MinIO behind the files host. Keep the server's firewall to 22, 80
  and 443.
- [ ] **Backups run and restore** (below).
- [ ] **Logs.** Caddy's logs drop query strings (they hold tokens and the
  signatures of file links). The API's log holds verification and
  password-reset links while email is off: treat it as sensitive.
- [ ] **Images are rebuilt for security fixes:** `docker compose build --pull`
  now and then picks up new base images; see [Upgrades](#9-upgrades).

### MinIO advisories

The bundled MinIO is `RELEASE.2024-12-13T22-19-12Z` built from source. On
2026-11-16, [OSV](https://osv.dev) listed these advisories against it:

| Advisory | What | Fixed release |
|---|---|---|
| CVE-2026-40344 | Unauthenticated object write (unsigned-trailer uploads) | none listed |
| CVE-2026-41145 | Unauthenticated object write (query-string credentials, unsigned-trailer uploads) | none listed |
| CVE-2026-42600 | Path traversal in a storage-REST endpoint | none listed |
| CVE-2026-39414 | Denial of service through S3 Select | none listed |
| CVE-2026-34204 | Metadata injection through replication headers | none listed |
| CVE-2026-33322 | JWT algorithm confusion in OIDC sign-in | none listed |
| CVE-2026-33419 | LDAP sign-in brute force | none listed |
| CVE-2025-62506 | Privilege escalation through session policies | RELEASE.2025-10-15 |
| CVE-2025-31489 | Incomplete signature checks on unsigned-trailer uploads | RELEASE.2025-04-03 |
| CVE-2025-27414 | SFTP authentication bypass | RELEASE.2025-02-27 |

How the stack is set up against them: nothing but `GET` and `HEAD` of objects
in the bucket reaches MinIO from the internet, so neither write advisory nor
S3 Select, replication, the storage-REST routes or MinIO's admin routes are
reachable from outside. MinIO uses no OIDC, LDAP or SFTP here, and its
console is off. Writes come only from the API, over the internal network.
That narrows the exposure; it does not patch MinIO. If you would rather rely
on a maintained service, use [your own storage](#object-storage-bundled-minio-or-your-own),
as production does.

### Backups

What to keep, apart from the server: the database, the stored files, `.env`
(with `KEY_VAULT_KEY`). Certificates (`godance_caddy_data`) are re-issued if
lost, and Redis holds nothing that matters.

```bash
cd godance/deploy/self-host
mkdir -p backups                        # ignored by git; copy its files off the server
# The database: a consistent dump while running.
docker compose exec -T postgres sh -c 'pg_dump -U "$POSTGRES_USER" -d godance --no-owner --no-acl' \
  | gzip > "backups/godance-$(date -u +%F).sql.gz"
# The bundled MinIO's files: stop it for a consistent copy (image requests fail meanwhile).
docker compose stop minio
docker run --rm -v godance_minio_data:/data:ro -v "$PWD/backups":/backup alpine:3.22 \
  tar czf "/backup/godance-files-$(date -u +%F).tgz" -C /data .
docker compose start minio
```

With your own storage, use your provider's versioning or replication instead.
Generated files are deleted 90 days after their job anyway, except the ones
people save (`godance.retention.*`).

To restore onto a fresh stack (same `.env`, empty volumes), before the API
has ever started:

```bash
cd godance/deploy/self-host
docker compose up -d --wait postgres
gunzip -c backups/godance-<date>.sql.gz \
  | docker compose exec -T postgres sh -c 'psql -q -U "$POSTGRES_USER" -d godance'
# The bundled MinIO's files, into its new volume before MinIO first starts:
docker compose up --no-start minio
docker run --rm -v godance_minio_data:/data -v "$PWD/backups":/backup alpine:3.22 \
  tar xzf "/backup/godance-files-<date>.tgz" -C /data
docker compose up -d
```

The volume names start with the Compose project's name, `godance`.

The operator's own daily backup, `backend/deploy/backup.sh`, is written for
production's stack and uploads to Spaces; it is an example, not part of this
one.

## 9. Upgrades

There are no regular releases: the only tag, `v1.0.0` (May 2026), is far
behind `main`, which is what production runs. Upgrade to a commit you have
read, and keep the one you came from.

```bash
cd godance/deploy/self-host
# 1. Back up first (see Backups).
# 2. Read what changed. There is no changelog file: read the commits, and look
#    for new database migrations and new settings.
git fetch
git log --oneline HEAD..origin/main
git diff --stat HEAD..origin/main -- ../../backend/src/main/resources/db/migration
git diff HEAD..origin/main -- .env.example
# 3. Update, rebuild, restart.
git pull
export GIT_SHA=$(git rev-parse HEAD)
docker compose build api
docker compose build web
docker compose build minio              # bundled storage only
docker compose up -d
docker compose logs api | grep -i "flyway\|Started"
curl -fsS "$GODANCE_URL/healthz"        # git_sha is the new commit
```

The API applies new database migrations (Flyway) when it starts, before it
serves anything. They only move forward: going back to an older commit
across a migration means restoring the backup you took first. Add any new
variable from `.env.example` to your `.env`; `preflight` and the API's own
checks stop the start if a required one is missing.

## 10. Optional parts

- **The marketing site and the gallery.** `marketing/` is the static Astro
  site for `godance.ai`, gallery included; this stack does not run it. Build
  it with `SITE_URL`, `PUBLIC_API_BASE` and `PUBLIC_APP_URL` set to your
  addresses and serve `marketing/dist/` from any static host. The Studio
  reads gallery recipes (`/gallery/*.json`) from `https://godance.ai` unless
  the app is built with `VITE_GALLERY_URL` (in `.env`, then
  `docker compose build web`) pointing at your site; if the gallery cannot be
  read, the Studio works without it. The MCP tools read it from
  `godance.mcp.gallery-url`.
- **MCP.** The MCP server at `GODANCE_URL/mcp` is on by default and needs an
  API key; `GODANCE_MCP_ENABLED=false` takes it away (404).
- **Batches.** Recipe batches are on; `godance.batches.*` bounds them.
- **Inputs.** Image uploads used in generations are off
  (`INPUTS_ENABLED=false`). Turning them on also needs a person check
  (`INPUTS_CHECK_MODEL`, a vision model you have a key for) unless you set
  `INPUTS_ALLOW_PEOPLE=true`; see `godance.inputs.*`.
- **Social sign-in.** Google, Apple and X sign-in work only with their
  credentials set (`godance.oauth.*`); their buttons show either way. Register
  `GODANCE_URL/login/oauth2/code/google` (or `apple`, `x`) as the redirect URI.
- **The community** (published recipes, profiles and follows) is not in this
  version; round 14 of the roadmap adds it.

Two things the app always loads from elsewhere: its fonts, from Google
Fonts, and (as above) the gallery list, so people's browsers contact
`fonts.googleapis.com` and `godance.ai`.

## 11. Troubleshooting

| Symptom | Cause and fix |
|---|---|
| `docker compose up` says preflight failed; the API never starts | `docker compose logs preflight` names each variable to fix. |
| `web` keeps restarting; its log says the app "was built for" another address | `GODANCE_URL` changed after the build: `docker compose build web`, then `docker compose up -d`. |
| `failed to create network godance_storage: Pool overlaps with other one on this address space` | Another Docker network uses `STORAGE_SUBNET`: pick a free one, e.g. `172.29.253.0/28`. |
| The site has no certificate; `docker compose logs web` shows ACME errors | Both host names must resolve to the server and ports 80 and 443 be open before Caddy can get certificates. While testing, use Let's Encrypt's staging server (a line in the `Caddyfile`) to stay under its rate limits. |
| `api` waits and never starts | With the bundled MinIO it waits for the files host to answer through Caddy: check `docker compose ps web minio` and `docker compose logs web`. |
| The API exits at start | `docker compose logs api` names the setting, for example `godance.region must be one of [LOCAL, DIGITALOCEAN, ALICLOUD]` or `godance.key-vault.key must be 32 bytes (AES-256) in Base64`. |
| Generated images do not show in the browser | Their links point at `GODANCE_FILES_URL` (or your own storage's endpoint), which the browser must reach: `curl -I` a link from the job. |
| No verification email | Email is off without `RESEND_API_KEY`: `docker compose logs api \| grep "Verification URL"`. |
| `403 two_factor_required` in the admin console | Turn on two-factor sign-in under Settings. |
| Requests to some models fail, or are answered by another model | Their provider has no key: see [Provider keys](#5-provider-keys). |
| A change to `Caddyfile` or `storage.caddy` has no effect | Caddy reads them at start: `docker compose up -d --force-recreate web`. |
| The NOWPayments webhook answers 500 with `HmacSHA512 unavailable; JVM broken` | `NOWPAYMENTS_IPN_SECRET` is empty, so every notification is refused. Expected while you take no USDT. |

## How this guide was tested

On 2026-11-16, from a fresh clone of the branch that added this guide, on a
Linux machine with Docker Engine 29.1.3 and Compose 2.40.3, following the
quick start's trial variant (on port 18080, because 8080 was taken). A
WireMock server stood in for the providers. Checked: `/healthz` green, sign-up
in the app, verification from the log, the admin bootstrap, two-factor
sign-in, an admin credit adjustment, a chat completion, an image job stored
in the bundled MinIO and fetched through the files host, the files host
refusing writes from outside, forged payment webhooks refused, a model
switched off, and the Studio loading. A server with a domain and Let's
Encrypt was not part of that run: there, only the four address values
differ, and CI checks that Caddy accepts that configuration.
