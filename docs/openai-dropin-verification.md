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
every step matched "Expected". Steps 1 to 11 were run again on 2026-11-15
against the round 12 build, together with the video steps below, and again
matched.

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

## Video (round 12)

Code an AI assistant writes for video uses OpenAI's SDK:

```python
video = client.videos.create_and_poll(model="…", prompt="…", seconds="8")
client.videos.download_content(video.id, variant="video").write_to_file("out.mp4")
```

Godance now serves those routes (`/v1/videos`) as a second view of its
video jobs: a video's id is its job's id, and the same job is at
`/v1/jobs/{id}`. `POST /v1/videos/generations` is unchanged.

This section checks that with the OpenAI Python SDK itself.

**Not yet run against production.** The "Observed" column is for the
operator to fill in after the deploy. The script below was run against a
local stack on 2026-11-15 (openai-python 3.51.0, a stand-in provider, no
paid call); every step matched "Expected".

### What OpenAI's side looks like today

- **OpenAI shut this API down.** OpenAI's reference says: "The Sora 2 models
  and Videos API were shut down on September 24, 2026 and are no longer
  available. No one-to-one replacement API is available."
  ([reference](https://developers.openai.com/api/reference/resources/videos),
  [deprecations](https://developers.openai.com/api/docs/deprecations), both
  read 2026-11-15.) The SDK still ships `client.videos.*`, each method marked
  deprecated with that date
  ([`resources/videos.py`](https://github.com/openai/openai-python/blob/main/src/openai/resources/videos.py)),
  and assistants still write it. So Godance serves the shape, and **`model`
  is a Godance video model**: `seedance-2.0` in every example here. There is
  no default model; a request without one answers 400 naming the field.
- **Where the shapes were read.** `platform.openai.com/docs/api-reference/videos`
  answers 403 to an automated fetch. The request, the video object, the
  page and the delete response were read from OpenAI's generated SDK source
  (openai-python 3.51.0: `resources/videos.py`, `types/video.py`,
  `types/video_create_params.py`, `types/video_delete_response.py`,
  `pagination.py`, `_base_client.py`), and then compared with the reference
  page above, which is retained "for historical reference" and agrees.
- **The SDK always sends a multipart form** for `videos.create`, with or
  without a file, so `POST /v1/videos` takes a form as well as JSON.
- **The SDK reads an error's text from `error.message` only.** A problem
  document has no such member, so on `/v1/videos` routes the problem also
  carries OpenAI's `error` object (`message`, `type`, `param`, `code`). It
  is still `application/problem+json` with every member it had.
- **Redirects.** The SDK's own HTTP client follows redirects, but a caller
  who passes `http_client=httpx.Client()` gets the library default, which
  does not, and neither does `curl` without `-L`: both would save an empty
  body with no error. So `content` streams the bytes and never redirects.
- **`input_reference` through the SDK is a file.** The SDK's types also
  allow `{"file_id": …}`, but 3.51.0 refuses a dict there before it sends
  anything. A stored file is named with
  `extra_body={"input_reference": {"file_id": "…"}}`, which reaches the form
  as `input_reference[file_id]`.

### Procedure

One API key on a funded account. Steps 1, 5 and 9 each cost one clip
(`seedance-2.0`: about 16 cents a second, the fee included; `POST
/v1/estimates` gives the exact figure).

| # | Step | Call | Expected | Observed |
|---|------|------|----------|----------|
| 1 | Create and wait | `client.videos.create_and_poll(model="seedance-2.0", prompt=…, seconds="8", size="1280x720")` | A video with `status: "completed"`, `progress: 100`, `seconds: "8"`, `size: "1280x720"`, `error: None`; `cost_cents` and `job_url` set. The SDK polls every 5 s (`openai-poll-after-ms`) | |
| 2 | Download | `client.videos.download_content(video.id, variant="video").write_to_file("out.mp4")` | HTTP 200, `content-type: video/mp4`, no redirect; `out.mp4` plays | |
| 3 | Retrieve and list | `client.videos.retrieve(video.id)`, `client.videos.list(limit=5)` | The same video; the list's first entry is it | |
| 4 | The same job | `GET /v1/jobs/{video.id}` | `DONE`, `modality: "VIDEO"`, `params.seconds: 8`, `actualCostCents` equal to the video's `cost_cents` | |
| 5 | What the SDK sends | `client.videos.with_raw_response.create(model="seedance-2.0", prompt=…, seconds="4")` | Request `Content-Type: multipart/form-data`; HTTP 200, `status: "queued"`; a retrieve while it runs carries `openai-poll-after-ms: 5000` | |
| 6 | Errors read well | `seconds="eight"`; no `model`; an unknown id; `variant="thumbnail"`; `variant="spritesheet"`; `client.videos.remix(…)` | The SDK's exception text carries the sentence: `BadRequestError … seconds must be a whole number…` with `code=invalid_parameter`, `param=seconds`; `model is required…`; `NotFoundError … video not found` (`video_not_found`); `This video has no thumbnail.` (`video_content_not_found`); `variant_not_supported`; `video_remix_not_supported` | |
| 7 | An image, inputs off | `client.videos.create(…, input_reference=("first.png", png_bytes, "image/png"))` | While inputs are switched off: `PermissionDeniedError … Image inputs are not available.`, `code=inputs_disabled`; nothing stored or charged | |
| 8 | Delete | `client.videos.delete(video.id)`, then `retrieve` | `{id, object: "video.deleted", deleted: True}`; then `NotFoundError`. `GET /v1/jobs/{id}` still answers, `purged: true`, `actualCostCents` unchanged; the balance is the same before and after | |
| 9 | An image, inputs on | Only once inputs are switched on, on an account that accepted the input terms: step 1 with `input_reference=("first.png", png_bytes, "image/png")` and `seconds="4"` | `completed`; the job's `inputs` is one `first_frame` with a `file_id`; `GET /v1/files` lists that file | |

Things to watch on the deployed service that a local run cannot show:

- **A real clip takes minutes.** `create_and_poll` has no timeout of its
  own; it returns when the job ends. A job that fails returns a video with
  `status: "failed"` and `error.code: "generation_failed"`, and the
  reservation is returned.
- **The proxy and large files.** `content` streams the file through the
  API host. Check that a real clip (tens of megabytes) downloads whole
  through Caddy and, if the host is proxied, Cloudflare.
- **Catalog rows for OpenAI's own video models.** `sora-2`, `sora-2-pro`
  and `sora-2-pro-hd` are still in the catalog; their provider no longer
  serves them. What to do with those rows is an operator decision.

### Repeat it

```sh
pip install openai
export GODANCE_BASE_URL=https://api.godance.ai/v1
export GODANCE_API_KEY=gd_live_…
python videos_dropin_check.py          # steps 1 to 8
```

`videos_dropin_check.py`:

```python
"""Video drop-in check: the OpenAI Python SDK against Godance.

    GODANCE_BASE_URL  e.g. https://api.godance.ai/v1
    GODANCE_API_KEY   an API key on a funded account

Steps 1 and 5 each spend the price of one clip.
"""
import os
import uuid
import warnings

import openai
from openai import OpenAI

warnings.simplefilter("ignore", DeprecationWarning)   # the SDK marks videos.* deprecated

client = OpenAI(base_url=os.environ["GODANCE_BASE_URL"], api_key=os.environ["GODANCE_API_KEY"])
MODEL = "seedance-2.0"


def extra(model, name):
    """A field the SDK's type does not declare (a Godance extension)."""
    return (model.model_extra or {}).get(name)


# 1. Create and wait.
video = client.videos.create_and_poll(model=MODEL, prompt="A paper boat drifts down a gutter after rain.",
                                      seconds="8", size="1280x720")
print("1.", video.status, video.progress, repr(video.seconds), video.size, video.error,
      "| cost_cents", extra(video, "cost_cents"), "| job_url", extra(video, "job_url"))

# 2. Download.
content = client.videos.download_content(video.id, variant="video")
content.write_to_file("out.mp4")
print("2. HTTP", content.response.status_code, content.response.headers["content-type"],
      "| redirects followed:", len(content.response.history), "| bytes", os.path.getsize("out.mp4"))

# 3. Retrieve and list.
print("3.", client.videos.retrieve(video.id).status, "| first in the list:",
      client.videos.list(limit=5).data[0].id == video.id)

# 4. The same job.
job = client.get("/jobs/" + video.id, cast_to=dict)
print("4.", job["status"], job["modality"], job["params"],
      "| actualCostCents == cost_cents:", job["actualCostCents"] == extra(video, "cost_cents"))

# 5. What the SDK sends.
raw = client.videos.with_raw_response.create(model=MODEL, prompt="Steam rising from a teapot.", seconds="4")
second = raw.parse()
print("5. sent as", raw.http_request.headers["content-type"].split(";")[0], "| HTTP", raw.status_code, second.status,
      "| openai-poll-after-ms", client.videos.with_raw_response.retrieve(second.id).headers.get("openai-poll-after-ms"))
print("  ", client.videos.poll(second.id).status)

# 6 and 7. Errors read well.
png = bytes.fromhex("89504e470d0a1a0a0000000d49484452000000010000000108060000001f15c489"   # one pixel
                    "0000000d4944415478da636460f85f0f0002870180eb47ba920000000049454e44ae426082")
for label, call in [
    ("6. seconds='eight'", lambda: client.videos.create(model=MODEL, prompt="x", seconds="eight")),
    ("6. no model", lambda: client.videos.create(prompt="x")),
    ("6. unknown id", lambda: client.videos.retrieve(str(uuid.uuid4()))),
    ("6. thumbnail", lambda: client.videos.download_content(video.id, variant="thumbnail")),
    ("6. spritesheet", lambda: client.videos.download_content(video.id, variant="spritesheet")),
    ("6. remix", lambda: client.videos.remix(video.id, prompt="x")),
    ("7. an image, inputs off", lambda: client.videos.create(model=MODEL, prompt="x",
                                                             input_reference=("first.png", png, "image/png"))),
]:
    try:
        call()
        print(label, "-> no error")
    except openai.APIStatusError as e:
        print(label, "->", type(e).__name__, e, "| code", e.code, "| param", e.param)

# 8. Delete.
print("8.", client.videos.delete(video.id).model_dump())
try:
    client.videos.retrieve(video.id)
except openai.NotFoundError as e:
    print("   then:", e)
job = client.get("/jobs/" + video.id, cast_to=dict)
print("   the job is kept: purged", job["purged"], "| actualCostCents", job["actualCostCents"])
```
