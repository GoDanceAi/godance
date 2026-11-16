# Brief 02 — Billing screens: auto-recharge and usage

**Branch:** `feature/billing-screens` (worktree `../gd-billing-ui`) off `main`; rebase onto brief 01 before merging
**Reserved migration:** none. This brief has no backend changes.
**Baseline:** `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/` on parent; record the counts first

## Mission (one line)

Let a user turn on, adjust and turn off auto-recharge from Billing, with
the exact amounts and a confirmation, and see their own recent usage on a
page linked from Billing and the dashboard.

## Why now

Both APIs were built in the early rounds and never got a screen:

- `GET` and `PUT /v1/billing/auto-recharge`. The backend tops a user up
  off-session from a saved card when the balance falls below a threshold.
  A card is saved by any card top-up, which uses
  `setup_future_usage=off_session`.
- `GET /v1/usage?limit=N`, the caller's recent usage records.

Auto-recharge charges a card without the user present, so the screen's
wording and confirmation matter as much as its controls.

## Read first

1. This brief and `00-coordination.md` (the money rule is binding)
2. Backend, read only:
   - `web/BillingController.java`, `web/dto/BillingDtos.java`
     (`AutoRechargeConfigRequest`: `enabled`, `threshold_cents`,
     `topup_cents`; `AutoRechargeConfigView` adds `payment_method_on_file`,
     `last_failure_at`, `last_failure_reason`)
   - `service/AutoRechargeService.java`: what it requires (a saved card),
     the limits it enforces, when it turns itself off after a failure, and
     how often it can charge
   - `service/BillingService.java`: where the card is saved
   - `application.yml`: `billing.topup-max-cents`
3. `web/UsageController.java` (`UsageView`: modality, provider, model,
   tokens, `costCents`, cache hit, fallback used, request id, time)
4. App: `app/src/pages/BillingPage.tsx`, `app/src/lib/auth.tsx`
   (`formatCents`), `app/src/pages/DashboardPage.tsx`, `app/src/App.tsx`,
   `app/src/lib/api.ts`, `app/e2e/mock-api.ts`, `app/e2e/billing.spec.ts`

## Scope

### 1. Auto-recharge on the Billing page

- **State.** An "Auto-recharge" section showing:
  - on or off
  - the threshold and the top-up amount
  - whether a card is on file
  - the last failure, with its time and reason, when there is one
- **No card on file.** Explain that topping up once with a card saves it for
  auto-recharge, and offer the existing top-up. Do not offer to turn it on.
- **Turning on or changing:**
  - Two amount fields, in dollars and cents, sent as cents.
  - Validate against the server's limits, and show the server's own message
    when it refuses.
  - A confirmation names the exact charge: "When your balance falls below
    $X, we will charge your card $Y."
  - The wording lives in one constant, as a draft for the operator.
- **Turning off.** One action, with no charge. Show the new state.
- **After a failure.** Show the reason in plain words, and what the user can
  do: top up with a card again, or turn it off.
- Re-read the state after every change rather than assuming it.

### 2. Usage page: `/usage`

- **Data.** `GET /v1/usage?limit=100`, newest first.
- **Table.** One row per record:
  - time (local)
  - modality and model
  - input and output tokens (text) or units
  - cost from `costCents` with `formatCents`
  - "cached" and "fallback" marks
  - the request id, with a copy button
- **Never show `providerCostCents`.** It is what Godance pays; see the
  operator decision.
- **Filters.** Modality and model, on the client.
- **A note.** It shows the latest 100 records; billing history is in Billing.
- **Links.** From Billing ("View usage") and from the dashboard balance card.
- **Empty state.** Links to the Studio and Chat.

## Hard rules

- Edits only in `BillingPage.tsx`, the new `UsagePage.tsx`, one route in
  `App.tsx`, one link in `DashboardPage.tsx`, `app/src/lib/**` and
  `app/e2e/**`.
- **No backend change.**
- **Nothing that can charge a card** is sent without the confirmation that
  states both amounts.
- No amount is computed in floating point. Convert input to integer cents
  once, and send cents.

## Opening move

Run the baseline. Read `AutoRechargeService` and state, with file and line,
the limits it enforces and when it disables itself. Write the failing e2e
test: with no card on file, the section explains how to save one and offers
no way to turn auto-recharge on. Then post a 3–5 bullet plan before writing
code.

## Acceptance criteria

- `npm run lint`, `npm run build`, `npm run test:e2e` green on desktop and
  mobile.
- **e2e covers:**
  - no card on file
  - turning on with the confirmation, including the exact amounts in it
  - a server refusal shown as given
  - changing amounts
  - turning off
  - the failure state
  - the usage page with records, filters, the empty state, and both links
  - `providerCostCents` never rendered (assert on the page text with a mock
    that has it)
- Screenshots of the auto-recharge section in each state, and of the usage
  page, in light and dark.
- All work committed on `feature/billing-screens`; `main` untouched.

## Out of scope (do not start)

- Changing auto-recharge's backend rules.
- Account-wide spending caps set by the user.
- Invoices or receipts.
- Paging usage beyond the API's limit.
- Removing `providerCostCents` from the API (operator decision).
