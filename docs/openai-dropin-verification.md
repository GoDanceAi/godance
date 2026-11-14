# OpenAI Drop-In Verification (production)

Proof that a freshly minted `gd_live_*` API key works against the
OpenAI-compatible surface on the live deployment — the "swap `base_url`
and key" promise (CLAUDE.md hard constraint #5), which was broken until
`ApiKeyAuthFilter` was wired into the security chain (round 2,
`feature/auth-security`).

**Last run:** 2026-07-02 against `https://api.godance.ai` · **Result: PASS**

## Procedure + observed results

| # | Step | Call | Expected | Observed 2026-07-02 |
|---|------|------|----------|---------------------|
| 1 | Signup | `POST /v1/auth/signup` | 200 + `accessToken` | ✅ JWT issued |
| 2 | Mint key | `POST /v1/keys` (JWT) `{"name":"e2e-dropin-verify"}` | 201 + `plaintext` `gd_live_…` | ✅ key returned once |
| 3 | List models with the key | `GET /v1/models` — `Authorization: Bearer gd_live_…` | 200 | ✅ 200 |
| 4 | Chat completion with the key | `POST /v1/chat/completions` (OpenAI shape, `deepseek-v4-flash`) | **402** `insufficient credit` on a $0 account — proves the key *authenticated* and reached billing | ✅ 402 problem+json with top-up hint |
| 5 | Control: invalid key | same call, `Bearer gd_live_invalid…` | **401** | ✅ 401 |
| 6 | Revoke | `DELETE /v1/keys/{id}` (JWT) | 204 | ✅ 204 |
| 7 | Revoked key rejected | `GET /v1/models` with revoked key | 401 | ✅ 401 |

The 402-vs-401 contrast is the load-bearing check: a `gd_live_*` key flows
through authentication → spending checks → billing exactly like the OpenAI
SDK expects (an OpenAI-SDK client pointed at `base_url=https://api.godance.ai/v1`
with this key produces these same calls). A funded account returning a
completion was verified separately with a JWT in Session 15; the key-auth
leg was the missing, now-proven piece.

## Repeat it

```sh
API=https://api.godance.ai
EMAIL="verify-$(date +%s)@godance.test"

# 1) signup → JWT
JWT=$(curl -s -X POST "$API/v1/auth/signup" -H 'content-type: application/json' \
  -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse-battery-staple\",\"fullName\":\"Verify\"}" \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['accessToken'])")

# 2) mint key
KEY=$(curl -s -X POST "$API/v1/keys" -H "authorization: Bearer $JWT" \
  -H 'content-type: application/json' -d '{"name":"dropin-verify"}' \
  | python3 -c "import json,sys;print(json.load(sys.stdin)['plaintext'])")

# 3) OpenAI-shape call with the key — expect 402 on a $0 account (auth OK),
#    200 with a funded account.
curl -s -w "\nHTTP %{http_code}\n" "$API/v1/chat/completions" \
  -H "authorization: Bearer $KEY" -H 'content-type: application/json' \
  -d '{"model":"deepseek-v4-flash","messages":[{"role":"user","content":"Say OK"}],"max_tokens":5}'
```

Clean up: `DELETE /v1/keys/{id}` with the JWT; confirm the key then 401s.
The 2026-07-02 test key was revoked immediately after the run.

## Images (round 10)

Every image model runs as an async job, so `POST /v1/images/generations`
answers `202` with a job. Standard OpenAI SDK code expects the images in the
response and fails on that. An API key can now be set to answer
synchronously (`image_response: "sync"`): the request waits for the job, up
to `godance.images.sync-wait` (90 s), and answers in OpenAI's shape. Keys
are `async` unless their owner, or `godance.images.new-key-response`, says
otherwise.

This section checks that promise with the OpenAI Python SDK itself.

**Not yet run against production.** The "Observed" column is for the
operator to fill in after the deploy. The same script was run against a
local stack with a stand-in provider on 2026-11-14 (openai-python 3.51.0);
every step matched "Expected".

### Procedure

Two keys on a funded account, one of each kind. Each generating step costs
one image.

| # | Step | Call | Expected | Observed |
|---|------|------|----------|----------|
| 1 | Create a sync key | `POST /v1/keys` (session) `{"name":"dropin-sync","image_response":"sync"}` | 201, `image_response: "sync"` | |
| 2 | Create an async key | `POST /v1/keys` (session) `{"name":"dropin-async","image_response":"async"}` | 201, `image_response: "async"` | |
| 3 | Standard SDK code, sync key | `client.images.generate(model="gpt-image-2", prompt=…)`, read `data[0].url` | A signed link. `data[0].b64_json` is set too: OpenAI returns its GPT Image models as base64, and Godance returns both | |
| 4 | Another model family, sync key | `client.images.generate(model="seedream-5.0", prompt=…)` | `data[0].url` set, `data[0].b64_json` absent | |
| 5 | `response_format`, sync key | the same with `response_format="b64_json"` | `data[0].b64_json` decodes to the image, `data[0].url` absent | |
| 6 | Async key | `client.images.with_raw_response.generate(…)` | **202** with `job_id`, `status`, `poll_url`, as before this change | |
| 7 | Sync key asking for a job | step 3 with header `Prefer: respond-async` | **202**, no wait | |
| 8 | The job behind step 3 | `GET /v1/jobs/{job_id}` with either key | `DONE`, the same outputs, `actualCostCents` equal to the response's `cost_cents` | |
| 9 | Switch a key | `PATCH /v1/keys/{id}` (session) `{"image_response":"sync"}` on the async key, then step 6 again | 200 with the key; the request now answers 200 with `data` | |
| 10 | A key cannot switch itself | the same `PATCH` with `Authorization: Bearer gd_live_…` | 403 | |
| 11 | Revoke both keys | `DELETE /v1/keys/{id}` (session) | 204 | |

Two things to watch on the deployed service that a local run cannot show:

- **The proxy.** A waiting request is held for up to 90 seconds. Caddy
  allows five minutes. If `api.godance.ai` is proxied through Cloudflare, a
  response must start within about 100 seconds, so keep
  `godance.images.sync-wait` under that or make the record DNS-only.
- **A slow model.** When a job outlasts the wait, step 3 answers `202` with
  `Retry-After: 5` and the job body, and the job finishes normally. The SDK
  then returns a result whose `data` is `None`; the docs tell callers to
  check for the 202.

### Repeat it

```sh
pip install openai
export GODANCE_BASE_URL=https://api.godance.ai/v1
export GODANCE_SYNC_KEY=gd_live_…     # step 1
export GODANCE_ASYNC_KEY=gd_live_…    # step 2
python images_dropin_check.py          # steps 3 to 7
```

`images_dropin_check.py`:

```python
"""Image drop-in check: the OpenAI Python SDK against Godance.

    GODANCE_BASE_URL   e.g. https://api.godance.ai/v1
    GODANCE_SYNC_KEY   an API key whose image_response is "sync"
    GODANCE_ASYNC_KEY  an API key whose image_response is "async"

Each step spends the price of one image.
"""
import base64
import os

from openai import OpenAI

base_url = os.environ["GODANCE_BASE_URL"]
sync = OpenAI(base_url=base_url, api_key=os.environ["GODANCE_SYNC_KEY"])
asynchronous = OpenAI(base_url=base_url, api_key=os.environ["GODANCE_ASYNC_KEY"])
PROMPT = "a lighthouse at dusk, watercolor"


def short(url):
    return url.split("?")[0] + "?…signed…"


# 1. The standard code, unchanged: a sync key and data[0].url.
result = sync.images.generate(model="gpt-image-2", prompt=PROMPT)
print("1. sync key, gpt-image-2, no response_format")
print("   data[0].url      =", short(result.data[0].url))
print("   data[0].b64_json =", len(base64.b64decode(result.data[0].b64_json)), "bytes, as OpenAI returns GPT Image models")

# 2. A model outside the GPT Image family answers with a link only.
result = sync.images.generate(model="seedream-5.0", prompt=PROMPT)
print("2. sync key, seedream-5.0, no response_format")
print("   data[0].url      =", short(result.data[0].url))
print("   data[0].b64_json =", result.data[0].b64_json)

# 3. response_format picks one.
result = sync.images.generate(model="seedream-5.0", prompt=PROMPT, response_format="b64_json")
print('3. sync key, seedream-5.0, response_format="b64_json"')
print("   data[0].url      =", result.data[0].url)
print("   data[0].b64_json =", len(base64.b64decode(result.data[0].b64_json)), "bytes")

# 4. An async key answers with a job, as before.
raw = asynchronous.images.with_raw_response.generate(model="gpt-image-2", prompt=PROMPT)
print("4. async key, gpt-image-2")
print("   HTTP", raw.status_code, raw.http_response.json())

# 5. A sync key can still ask for the job on one request.
raw = sync.images.with_raw_response.generate(model="gpt-image-2", prompt=PROMPT, extra_headers={"Prefer": "respond-async"})
print("5. sync key with Prefer: respond-async")
print("   HTTP", raw.status_code, sorted(raw.http_response.json()))
```
