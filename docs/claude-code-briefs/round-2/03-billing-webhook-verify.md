# Brief 03 — Stripe Webhook & Auto-Recharge Production Verification

**Branch:** `feature/billing-verify` · **No migration** · Read both coordination docs first. Own worktree; commit as you go.

## Mission

Prove the Stripe webhook and the auto-recharge path are correct end to end, and lock the behavior with automated tests. Auto-recharge was built in Session 13 but has **never been smoke-tested in production**.

## Scope

**Automated tests (add any that are missing):**
- Stripe webhook signature verification, including the `deserializeUnsafe()` fallback for API-version skew (the bug the live smoke test caught).
- A top-up credits the ledger **exactly once** and is idempotent on the Stripe event id (replay → no double credit).
- Auto-recharge off-session PaymentIntent: balance below threshold → one charge → balance += topup; declined card → hard-fail disables auto-recharge with the reason persisted (matches the Session 13 design).
- NOWPayments IPN: valid `finished` signature credits once; replay deduped; bad signature → 400.

**Production verification runbook** (commit as `docs/billing-verification.md`, or put in the PR body): exact steps for the operator to run against `api.godance.ai` — use the Stripe CLI to trigger/replay events, confirm ledger credit + audit rows + balance, drive an off-session auto-recharge by dropping a test-mode balance below threshold, and confirm a NOWPayments IPN credits. Note clearly which steps need operator dashboard/droplet access.

Fix any bug the tests surface, but keep changes within billing + test files. **Do not rewrite `CreditLedgerService` core logic.**

## Tests

Webhook + auto-recharge + NOWPayments IPN integration tests green (WireMock cassettes, no mocking of the service-under-test's collaborators where the bug surface matters). `mvn -q test` green on branch.

## Out of scope

New payment rails, flipping Stripe to live mode (that's operator task #8), frontend.
