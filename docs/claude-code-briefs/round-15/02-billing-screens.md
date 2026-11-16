# 02 — Billing screens on existing APIs

## Outcome

Billing explains balance/top-ups, recent loaded usage and auto-recharge clearly. Every money-changing action is explicit. No complete payment-history or usage-ledger API is invented.

Read `00-coordination.md`, `session-prompts.md`, `app/src/pages/BillingPage.tsx`, `app/src/lib/{api,auth}.ts*`, existing billing E2E tests, and backend `web/{BillingController,UsageController}.java`, `web/dto/BillingDtos.java`, `service/{BillingService,AutoRechargeService}.java`, `config/BillingProperties.java`.

## Existing contract

- `POST /v1/billing/topup` and `POST /v1/billing/nowpayments/topup`: `{ amountCents, save_card? }`; response `{ sessionId, checkoutUrl, amountCents }`. `save_card` defaults false and applies to Stripe, not USDT.
- `GET` / `PUT /v1/billing/auto-recharge`. PUT: `{ enabled, threshold_cents?, topup_cents? }`. Response: `{ enabled, threshold_cents, topup_cents, payment_method_on_file, last_failure_at, last_failure_reason }`.
- Enabling requires both amounts and a saved card. Threshold permits zero; top-up bounds are deployment-configurable. Missing/non-card payment method yields 409; provider lookup failures can yield 502. After 409, refetch because the server can clear an invalid saved method.
- `GET /v1/usage?limit=N` returns recent rows, newest first, default 50 and maximum 200. It has no paging, server date filter or total count. Customer-facing amounts are `costCents`; `providerCostCents` is internal and must be discarded before view/export state.
- Use the existing signed-in session helpers. Billing endpoints refuse API keys; usage itself also permits API keys. Never substitute admin ledger endpoints for customer history.

Verify the current DTOs before implementation; the source remains authoritative for errors and optional fields.

## Scope

1. Retain hosted top-ups and existing payment methods. Add an explicit unchecked Stripe-only save-card choice, with accurate off-session explanation; saving a card alone does not enable auto-recharge.
2. Show recent usage using an explicit allowlist of fields (for example time, model, modality, token counts, `costCents`, cache/fallback indicators). Optional client filters operate only on loaded rows. Any total says **recent loaded usage**, not monthly/lifetime spend or complete billing history.
3. Show current auto-recharge enabled state, both configured amounts, saved-method availability and failure status. Never invent card brand, last4, removal, invoices, receipts or payment-history screens: the customer API has none.
4. Enable/update through a review step showing both threshold and recharge amount, USD values, and off-session charging. Put draft consent wording in one marked source constant, e.g. `DRAFT_AUTO_RECHARGE_CONSENT`, with an operator-review comment. Do not put implementation labels into the user flow.
5. Disabling is a separate explicit action. A failed write preserves editable inputs and last confirmed server state; never claim it succeeded. Disable repeat submits while pending.
6. Checkout return parameters never prove payment. Refresh existing account balance and describe asynchronous payment confirmation truthfully; do not replay checkout automatically.

## Safety and limits

Money arithmetic uses integer cents and safe-integer checks. Parse decimal amount text without `parseFloat` rounding; reject malformed, negative or excessive-precision input. Keep server validation authoritative for configurable limits; do not promise hardcoded deployment bounds.

No POST/PUT, off-session setup or automatic retry from render, route entry, URL parameters or effects. The user must explicitly submit and confirm enabling/changing enabled recurring charges. Explain both amounts in the actual UI, not just a test.

Drop `providerCostCents` with a narrow mapper immediately after fetching; do not keep raw usage responses in page state, debugging panels, CSV/export models, visible text, accessible labels or screenshots. This is a frontend containment rule, not a claim that the backend no longer returns the field.

No new backend route/DTO changes, payment provider integration, runtime dependency or configuration default change. Do not edit shared navigation or ChatPage. Use a feature-specific helper on exported `apiFetch` and focused mock helpers.

## Acceptance and evidence

- Initial loading, empty usage, 401/403, provider failure, GET failure, write failure and retry paths are usable; retry never replays a money-changing request automatically.
- Synthetic usage data containing a distinctive internal provider cost never exposes that value or field in the UI, accessibility tree or any export; customer `costCents` remains correct. Filtered totals are labelled with their loaded scope.
- Stripe save-card starts unchecked; USDT never sends card-saving intent. Navigation and rendering cause no payment/configuration writes.
- Auto-recharge enable and enabled-update confirmation states both amounts and off-session meaning. Test zero threshold, invalid cents, pending double submit, missing card, 409 refetch and 502 failure. Cancellation sends no PUT.
- A checkout-success URL alone does not change the balance or show payment confirmed.
- Existing top-up flows remain intact. Run app lint/build and focused billing/auth/navigation E2E on desktop/mobile; final full app CI must pass.
- Include light/dark screenshots of usage and the confirmation step using synthetic data only. No actual payment, card save or provider request is part of validation.

Opening move: map each proposed control to the exact existing API and errors, then post the plan. Write a failing test proving no auto-recharge PUT occurs until the user confirms both amounts, then implement.
