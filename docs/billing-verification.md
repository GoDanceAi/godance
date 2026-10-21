# Billing Verification Runbook

Step-by-step verification of the Stripe webhook, off-session auto-recharge,
and NOWPayments IPN paths against a deployed environment. Run this in
**Stripe test mode** and the **NOWPayments sandbox** — it never needs live
keys or real money.

Steps tagged **[operator]** require Stripe/NOWPayments dashboard access or
SSH access to the droplet (logs + database). Steps with no tag are plain
HTTP calls anyone with a test account can run.

The automated tests in `backend/src/test/java/ai/godance/web/`
(`BillingIntegrationTest`, `AutoRechargeIntegrationTest`,
`BillingNowPaymentsIntegrationTest`) lock the same behaviour in CI; this
runbook proves it against the real deployment, where signing secrets, the
live Stripe API version, and webhook delivery are outside the test harness.

## 0. Prerequisites

- Base URL of the deployment under test, referred to below as `$API`
  (production is `https://api.godance.ai`).
- A test user. Create one and capture its JWT:
  ```sh
  API=https://api.godance.ai
  EMAIL="verify-$(date +%s)@godance.test"
  curl -s -X POST "$API/v1/auth/signup" \
    -H 'content-type: application/json' \
    -d "{\"email\":\"$EMAIL\",\"password\":\"correct-horse-battery-staple\",\"fullName\":\"Verify\"}"
  # Response contains accessToken — export it:
  JWT="Bearer <accessToken from response>"
  ```
- **[operator]** `STRIPE_SECRET_KEY` / `STRIPE_WEBHOOK_SECRET` set on the
  droplet to the **test-mode** values from the Stripe Dashboard, and the
  endpoint `$API/v1/billing/webhooks/stripe` registered under
  Developers → Webhooks (event `checkout.session.completed`).
- **[operator]** `NOWPAYMENTS_IPN_SECRET` set on the droplet, and the IPN
  callback URL `$API/v1/billing/webhooks/nowpayments` set in the
  NOWPayments dashboard.
- **[operator]** Stripe CLI installed and logged in (`stripe login`) to the
  same test-mode account.
- **[operator]** `psql` access to the droplet database (referred to as
  `$DATABASE_URL`). The verification queries below are read-only except
  where explicitly noted.

The user id (`$UID`) used throughout:
```sh
psql "$DATABASE_URL" -tAc "SELECT id FROM users WHERE email = '$EMAIL';"
```

## 1. Stripe webhook — credit on `checkout.session.completed`

### 1a. Endpoint reachability + signature verification — [operator]

```sh
stripe trigger checkout.session.completed
```

`stripe trigger` delivers a **fixture** session to every registered
test-mode endpoint. The fixture has no `godance_user_id` metadata, so the
expected result is **HTTP 400 `checkout.session.completed missing user id`**
— signature verification *passed* and the handler correctly rejected an
unattributable event. Confirm in the droplet log:

```
stripe webhook checkout.session.completed eventId=evt_... apiVersion=... sessionId=cs_...
```

and a `billing.webhook.rejected` audit row should **not** appear for this
case (the rejection is the missing-user 400, not a signature failure). A
`v1=` signature failure here instead means `STRIPE_WEBHOOK_SECRET` on the
droplet does not match the endpoint's signing secret — fix that first.

### 1b. Real top-up credits the ledger exactly once

This is the positive path — a session minted by the app carries
`metadata.godance_user_id`, so the webhook attributes the credit.

1. Mint a Checkout Session:
   ```sh
   curl -s -X POST "$API/v1/billing/topup" \
     -H "Authorization: $JWT" -H 'content-type: application/json' \
     -d '{"amountCents": 500}'
   ```
   Capture `checkoutUrl` and `sessionId` from the response.
2. Open `checkoutUrl` in a browser, pay with Stripe test card
   `4242 4242 4242 4242`, any future expiry, any CVC/ZIP.
3. Stripe delivers `checkout.session.completed` to the registered endpoint.
4. **[operator]** Confirm the credit landed:
   ```sh
   # Balance cache reflects the top-up (500¢):
   psql "$DATABASE_URL" -tAc \
     "SELECT credit_balance_cents FROM users WHERE id = '$UID';"

   # Exactly one STRIPE ledger row, keyed by the Stripe event id:
   psql "$DATABASE_URL" -tAc \
     "SELECT count(*), sum(delta_cents) FROM credit_ledger
      WHERE user_id = '$UID' AND source = 'STRIPE';"

   # Audit trail: a billing.topup.succeeded row:
   psql "$DATABASE_URL" -tAc \
     "SELECT event_type, outcome FROM audit_logs
      WHERE user_id = '$UID' AND event_type LIKE 'billing.topup%'
      ORDER BY created_at;"
   ```
   Expect: balance `500`, ledger `count=1, sum=500`, audit row
   `billing.topup.succeeded | SUCCESS`.

### 1c. Replay is idempotent — no double credit — [operator]

In the Stripe Dashboard → Developers → Webhooks → the endpoint → the
`checkout.session.completed` event from step 1b → **Resend**. Alternatively
from the CLI:

```sh
stripe events resend <evt_id_from_step_1b>
```

Re-run the queries from 1b. Expect **no change**: balance still `500`,
ledger still `count=1`, and a new audit row `billing.topup.duplicate |
SUCCESS`. The unique constraint on `credit_ledger.idempotency_key` (the
Stripe event id) is the hard guard; the duplicate audit row proves the
replay was recognised rather than silently dropped.

### 1d. API-version skew uses the `deserializeUnsafe()` fallback — [operator]

The SDK (`stripe-java` 28.4.0) is pinned to API version
`2025-02-24.acacia`. A live or test account on a newer version mints events
whose `api_version` release-name differs (e.g. `...dahlia`).
`EventDataObjectDeserializer.getObject()` returns empty for those, and
`BillingService.handleWebhook` falls through to `deserializeUnsafe()` —
this is the bug an earlier live smoke test caught.

Check which version the account is on, and that credits still applied:

```sh
# Account's current default API version — [operator], Stripe CLI:
stripe --version            # CLI version
# Dashboard → Developers → API version shows the account default.

# Droplet log line from step 1b — read the apiVersion field:
#   stripe webhook checkout.session.completed eventId=... apiVersion=2025-... sessionId=...
```

If `apiVersion` shows a release-name other than `acacia` **and** step 1b
still credited the ledger, the `deserializeUnsafe()` fallback is confirmed
working in production. If the account is still on an `...acacia` version,
the safe `getObject()` path is exercised instead — to force the fallback,
**[operator]** bump the account's default API version in the Dashboard
(Developers → API version) to a newer release, repeat 1b with a fresh
top-up, and confirm the credit still lands. A
`billing.webhook.rejected | reason=deserialize-failed` audit row means the
fallback itself failed — capture the `eventId`/`apiVersion` and escalate.

## 2. Auto-recharge — off-session PaymentIntent

### 2a. Capture a saved card

Auto-recharge needs a payment method captured with
`setup_future_usage=off_session`. Mint a top-up with `save_card` true:

```sh
curl -s -X POST "$API/v1/billing/topup" \
  -H "Authorization: $JWT" -H 'content-type: application/json' \
  -d '{"amountCents": 500, "saveCard": true}'
```

Pay the Checkout Session with `4242 4242 4242 4242`. After the webhook
processes, **[operator]** confirm the card was captured:

```sh
psql "$DATABASE_URL" -tAc \
  "SELECT stripe_customer_id, stripe_payment_method_id FROM users WHERE id = '$UID';"
```

Both columns must be non-null. A `billing.payment_method.saved` audit row
is also written.

### 2b. Enable auto-recharge

```sh
curl -s -X PUT "$API/v1/billing/auto-recharge" \
  -H "Authorization: $JWT" -H 'content-type: application/json' \
  -d '{"enabled": true, "threshold_cents": 500, "topup_cents": 2000}'
```

Response: `enabled=true`, `paymentMethodOnFile=true`.

### 2c. Drive a charge by dropping the balance below threshold — [operator]

The off-session charge is enqueued when a debit pushes the balance below
the threshold. Drop the balance, then trigger the debit hook with one
billable request:

```sh
# Drop the test-mode balance just above threshold (600¢ > 500¢):
psql "$DATABASE_URL" -c \
  "UPDATE users SET credit_balance_cents = 600 WHERE id = '$UID';"
```

Then make one small billable API call (e.g. a cheap `/v1/chat/completions`
request) so the debit crosses the threshold. The debit hook enqueues a
one-shot Quartz job that fires the off-session PaymentIntent. (If you would
rather not spend credit, wait for the daily auto-recharge cron safety net
instead — it scans every enabled user.)

Confirm within a few seconds:

```sh
# Balance jumped by topup_cents (2000¢):
psql "$DATABASE_URL" -tAc \
  "SELECT credit_balance_cents FROM users WHERE id = '$UID';"

# One auto-recharge STRIPE ledger row:
psql "$DATABASE_URL" -tAc \
  "SELECT delta_cents, note FROM credit_ledger
   WHERE user_id = '$UID' AND note LIKE 'Auto-recharge%';"

# Audit trail:
psql "$DATABASE_URL" -tAc \
  "SELECT event_type, outcome FROM audit_logs
   WHERE user_id = '$UID' AND event_type = 'billing.autorecharge.fired';"
```

Expect a `+2000` ledger row and `billing.autorecharge.fired | SUCCESS`. In
the Stripe Dashboard a corresponding off-session PaymentIntent appears
under Payments.

### 2d. Declined card hard-fails and disables auto-recharge — [operator]

Repeat 2a with Stripe's off-session-failure test card
**`4000 0000 0000 0341`** (attaches to a customer fine, but off-session
charges fail). Re-enable auto-recharge (2b), drop the balance and trigger
the debit (2c). The off-session PaymentIntent is declined; the service
hard-fails on the first failure:

```sh
psql "$DATABASE_URL" -tAc \
  "SELECT auto_recharge_enabled, auto_recharge_last_failure_at,
          auto_recharge_last_failure_reason
   FROM users WHERE id = '$UID';"

psql "$DATABASE_URL" -tAc \
  "SELECT event_type, outcome FROM audit_logs
   WHERE user_id = '$UID' AND event_type = 'billing.autorecharge.failed';"
```

Expect `auto_recharge_enabled = f`, a populated `last_failure_at`, a
`last_failure_reason` naming the decline (e.g. `card_declined: ...`), an
audit row `billing.autorecharge.failed | FAILURE`, and **no** credit ledger
row for the failed attempt. The user must re-enable auto-recharge (which
means running a fresh interactive top-up with a working card first).

## 3. NOWPayments IPN — crypto top-up credit

### 3a. Real sandbox payment

```sh
curl -s -X POST "$API/v1/billing/nowpayments/topup" \
  -H "Authorization: $JWT" -H 'content-type: application/json' \
  -d '{"amountCents": 2500}'
```

Open the returned `checkoutUrl` (a NOWPayments hosted invoice) and complete
the payment in the **NOWPayments sandbox** — in BTC or ETH where offered, so
the IPN carries an amount below 0.001 (the case most likely to break
signature canonicalisation). When the payment reaches the
terminal `finished` status, NOWPayments POSTs an IPN to
`$API/v1/billing/webhooks/nowpayments`.

**[operator]** Confirm:

```sh
psql "$DATABASE_URL" -tAc \
  "SELECT credit_balance_cents FROM users WHERE id = '$UID';"

psql "$DATABASE_URL" -tAc \
  "SELECT count(*), sum(delta_cents) FROM credit_ledger
   WHERE user_id = '$UID' AND source = 'NOWPAYMENTS';"

psql "$DATABASE_URL" -tAc \
  "SELECT event_type, outcome FROM audit_logs
   WHERE user_id = '$UID' AND event_type LIKE 'billing.topup.nowpayments%'
   ORDER BY created_at;"
```

Expect the balance to include the `2500¢` top-up, one `NOWPAYMENTS` ledger
row, and a `billing.topup.nowpayments.succeeded | SUCCESS` audit row.
Non-terminal IPN deliveries (`waiting`, `confirming`, …) ack with 200 and
write `billing.webhook.ignored` — they must not credit.

### 3b. Replay is idempotent — [operator]

NOWPayments re-delivers an IPN until it gets a 2xx. To verify dedup without
a second real payment, replay the exact `finished` IPN body. The signature
is HMAC-SHA512 over the key-sorted (recursively) body as JavaScript's
`JSON.stringify` prints it, using `NOWPAYMENTS_IPN_SECRET` — header
`x-nowpayments-sig`. Sign it the way NOWPayments' Node.js SDK does (below),
not with Python's `json.dumps`: Python prints `0.00005` as `5e-05` and
escapes non-ASCII, so a BTC/ETH-sized amount would fail verification.

Capture the original IPN JSON (from the droplet access log or the
NOWPayments dashboard IPN history) into `ipn.json`, then on the droplet
where the secret is available as an env var:

```sh
# Canonicalise + sign with the IPN secret (never paste the secret literally):
SIG=$(docker run --rm -i -e NOWPAYMENTS_IPN_SECRET node:22-alpine node -e '
const crypto = require("crypto");
const sortObject = (o) => Array.isArray(o) ? o.map(sortObject)
  : o && typeof o === "object"
    ? Object.keys(o).sort().reduce((r, k) => { r[k] = sortObject(o[k]); return r; }, {})
    : o;
let body = "";
process.stdin.on("data", (c) => { body += c; }).on("end", () => console.log(crypto
  .createHmac("sha512", process.env.NOWPAYMENTS_IPN_SECRET.trim())
  .update(JSON.stringify(sortObject(JSON.parse(body))))
  .digest("hex")));
' < ipn.json)

curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  "$API/v1/billing/webhooks/nowpayments" \
  -H "x-nowpayments-sig: $SIG" \
  -H 'content-type: application/json' \
  --data-binary @ipn.json
```

Expect HTTP `200`, the balance **unchanged** from 3a, the `NOWPAYMENTS`
ledger row count still `1`, and a new
`billing.topup.nowpayments.duplicate | SUCCESS` audit row.

### 3c. Bad signature is rejected — [operator]

```sh
curl -s -o /dev/null -w '%{http_code}\n' -X POST \
  "$API/v1/billing/webhooks/nowpayments" \
  -H 'x-nowpayments-sig: deadbeef00not_a_real_signature' \
  -H 'content-type: application/json' \
  --data-binary @ipn.json
```

Expect HTTP `400`, no balance change, and a
`billing.webhook.rejected | DENIED` audit row with
`provider=nowpayments` in `event_detail`.

## Sign-off checklist

- [ ] 1a — `stripe trigger` reaches the endpoint; signature verifies (400 missing-user is expected).
- [ ] 1b — real top-up credits the ledger exactly once; audit row written.
- [ ] 1c — replayed event does not double-credit; duplicate audit row written.
- [ ] 1d — `apiVersion` confirmed; `deserializeUnsafe()` fallback exercised or accounted for.
- [ ] 2a/2b — saved card captured; auto-recharge enabled.
- [ ] 2c — below-threshold debit fires an off-session PaymentIntent and credits.
- [ ] 2d — declined card hard-fails, disables auto-recharge, persists the reason.
- [ ] 3a — NOWPayments `finished` IPN credits once.
- [ ] 3b — replayed IPN deduped.
- [ ] 3c — bad-signature IPN rejected with 400.
