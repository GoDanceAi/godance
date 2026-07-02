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
