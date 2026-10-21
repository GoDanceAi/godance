# Brief 4 — Crypto Payments: NOWPayments (USDT) + Binance Pay scaffold

**Branch:** `feature/crypto-nowpayments` · **Merges 4 of 4 (last).** · **Read `00-COORDINATION.md` first.**

## Mission

The legacy crypto rail is dead — Coinbase Commerce's hosted product was sunset and `CoinbaseService` is inert against the live API. Replace it with **NOWPayments** (USDT on TRC-20 / ERC-20 / BEP-20, self-serve onboarding) as the v1 crypto top-up rail, writing to the same credit ledger as Stripe. Scaffold **Binance Pay** as a deferred v1.1 rail behind the same internal interface. This workstream is mostly isolated from the catalog work — you own the billing/payments files.

## Scope

- **Add `NowPaymentsService`** (`service/NowPaymentsService.java`) — mirror the structure of the existing `CoinbaseService` (Spring `RestClient`, no official SDK):
  - `createInvoice(user, amountCents)` → POST NOWPayments invoice/payment, return a hosted payment URL + payment id.
  - `verifyAndParse(payload, sigHeader)` → verify the IPN HMAC-SHA512 signature over the **raw** request body (NOWPayments uses sorted-JSON HMAC; header `x-nowpayments-sig`), constant-time compare. Reject on mismatch.
  - Map NOWPayments statuses (`waiting`, `confirming`, `confirmed`, `finished`, `partially_paid`, `failed`, `expired`) → credit only on a terminal success (`finished`).
- **`NowPaymentsProperties`** (`@ConfigurationProperties("godance.nowpayments")`) — `apiKey`, `ipnSecret`, `apiBase` (`${NOWPAYMENTS_API_BASE:https://api.nowpayments.io}`), `successUrl`, `cancelUrl`, all via `${ENV_VAR:dev-default}` placeholders. Add the block to `application.yml` and `application-prod.yml`.
- **`CreditSource` enum:** add `NOWPAYMENTS` (and `BINANCE_PAY` for the scaffold).
- **`BillingService`:** add `startNowPaymentsTopUp(user, amountCents)` and `handleNowPaymentsWebhook(payload, signature, clientIp)`. The webhook handler verifies the signature, extracts the user id (pass it through invoice metadata / `order_id`), and calls `CreditLedgerService.topUp(...)` with `source=NOWPAYMENTS` and **`idempotencyKey = NOWPayments payment_id`** (so retries credit exactly once — same guarantee as the Stripe path). Do **not** modify `CreditLedgerService`'s core logic; just call it.
- **`BillingController`:** add `POST /v1/billing/nowpayments/topup` and the webhook receiver `POST /v1/billing/webhooks/nowpayments` (raw-body binding, returns 200 on success/dedup, 400 on bad signature) — mirror the existing Stripe/Coinbase endpoints.
- **Retire the dead Coinbase rail:** disable the Coinbase top-up entry point (`POST /v1/billing/coinbase/topup`) and webhook so nothing routes to it. Keep `CoinbaseService` as dead code or delete it — but ensure no active path and no test depends on it being live. Update `CreditSource` docs/comments accordingly.
- **Binance Pay scaffold (v1.1, deferred):** define a small internal `CryptoPaymentRail` interface implemented by `NowPaymentsService`, plus a stub `BinancePayService implements CryptoPaymentRail` that throws `UnsupportedOperationException` / is feature-flagged off. No live Binance integration — just the seam so v1.1 is a drop-in. Note Binance Pay requires merchant KYC onboarding (weeks).

## Flyway

You own **`V9`** (and `V10` only if truly needed). Add a migration only if you introduce a payment-tracking table (e.g. `crypto_payments(payment_id, user_id, provider, amount_cents, status, created_at)` for invoice→status reconciliation). If you can rely solely on the credit-ledger idempotency key (as Stripe does), you may need no migration — prefer the simpler path. Do not use any version above V10.

## Conventions

- Money in integer cents end-to-end; convert NOWPayments' fiat-equivalent amount carefully (top-up amount is denominated in the user's fiat top-up, not the crypto amount). Honor `godance.billing.topup-min-cents` / `topup-max-cents`.
- Idempotency on the webhook via the upstream payment id (the ledger's unique `idempotency_key` is the hard guard).
- Reactive, RFC 7807 errors, secrets via env vars only (this is the public repo — no real keys).
- Tests: WireMock cassette for the NOWPayments invoice call; a webhook test proving (a) valid signature + `finished` → ledger credited once, (b) replay → deduped (no double credit), (c) bad signature → 400, (d) non-terminal status → no credit.

## Acceptance criteria

`mvn -q test` green. A user can start a NOWPayments USDT top-up (`POST /v1/billing/nowpayments/topup` → hosted URL), and a signed `finished` IPN credits exactly the right cents to their balance, idempotently. The dead Coinbase entry points are disabled. Binance Pay exists as a flagged-off scaffold behind `CryptoPaymentRail`. No edits outside the billing/payments file set; `V9` is your only (optional) migration.

## Out of scope

Catalog/providers, frontend (the marketing hero "USDC → USDT" copy fix and the top-up UI are a deferred UI pass), live Binance Pay, on-chain wallets / x402 / agent payments (explicitly excluded by the project brief — crypto is a top-up method, not an architecture).
