# Brief 03 — Bring your own key

**Branch:** `feature/byok` (worktree `../gd-byok`) off `main` with round 13 merged
**Reserved migration:** V40
**Baseline:** full backend suite green on parent; `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`

## Mission (one line)

Let a user store their own provider key, encrypted. Requests to that
provider's models then go out on their key, and Godance charges only its
fee. The regional allowlist, caps, recipes, batches and every other
Godance feature still apply.

## Why now

Experienced developers often have provider contracts, credits or rate limits
of their own. They still want one API, one ledger, recipes, batches and MCP
on top. Bring-your-own-key keeps them on Godance instead of beside it.

It has to be done without weakening two hard constraints:
- no plaintext secrets at rest
- the regional allowlist as the single chokepoint

## Read first

1. This brief and `00-coordination.md` (the credential routes are binding;
   V40 is yours)
2. `backend/CLAUDE.md`, hard constraints 1 and 2
3. `security/KeyVault.java`: how secrets are encrypted today
4. Gateway:
   - `gateway/ProviderGateway.java` (`dispatch()`, the region chokepoint)
   - `gateway/ImageProviderGateway.java`, `gateway/VideoProviderGateway.java`
   - `gateway/AlicloudProviderAllowlist.java`
   - the `config/*Properties.java` classes where provider keys are bound
     today
5. Every adapter in `gateway/providers/`: where each reads its key and
   builds its auth header
6. `gateway/CostCalculator.java`, `service/CreditLedgerService.settle`,
   `domain/UsageRecord.java`, `service/GenerationJobSettlement.java`,
   `service/ChatCompletionService.java`
7. `service/SpendingLimitService.java`
8. Each provider's terms on using a customer's key through a third-party
   service

## Scope — backend

### Data (V40)

- **`provider_credentials`:**
  - user, provider (the `Provider` enum), the encrypted key (through
    `KeyVault`), `last4`
  - status (`valid`, `invalid`, `unchecked`), `fallback_to_platform`
  - created and checked times
  - unique on (user, provider)
- **On `usage_records`:** `byok` (boolean).

### Credentials

- **Routes** as in the contract, session only. An API key cannot read,
  set or remove credentials.
- **Saving a key.** Validate it with the cheapest call the provider offers
  for checking a key, such as listing models. Store it only when that call
  succeeds.
  - Never log the key or the provider's response body.
  - Never return the key; return only `last4`.
- **Supported providers.** `godance.byok.providers` lists them, empty by
  default. A provider outside the list answers 400 with
  `byok_provider_not_supported`. The operator adds each provider after
  checking its terms.
- **Audit events:** `byok.saved`, `byok.removed`, `byok.rejected`. Never the
  key.

### Using a credential

- **A seam,** for example `CredentialResolver`, that an adapter asks for the
  key to use for this request: the user's, when one is stored and valid for
  the provider, or the platform's.
  - Migrate adapters one at a time, starting with the providers on the list.
  - An adapter not migrated always uses the platform's key. A user with a
    credential for that provider is told so by `/v1/provider-credentials`,
    which reports `"status": "unsupported_adapter"`.
- **Routing is unchanged.** `ProviderGateway.dispatch()` stays the single
  region check, before any key is chosen. A credential never makes a
  forbidden provider reachable. Test it from the Alicloud region.
- **When the provider rejects the key** (401 or 403):
  - mark the credential `invalid`
  - if `fallback_to_platform` is true, retry once with the platform's key
    at the normal price
  - otherwise fail the request with 402 and `byok_credential_rejected`
  
  Refund any pre-debit as the existing failure path does.

### Billing

- **What a BYOK request is charged.** The fee only:
  `godance.byok.fee-basis-points` (default 500) applied to what the request
  would cost at list price, in integer microcents and settled through
  `settle`.
  - The pre-debit holds that fee, not the full price.
  - The usage record is marked `byok`.
- **Caps and limits.** Spending caps and every limit still apply, to the
  fee.
- **Tests.** The estimate equals the pre-debit for BYOK and platform
  requests on every supported model, and a fallback retry is billed at the
  platform price exactly once.
- **Estimates.** `POST /v1/estimates` answers the BYOK fee when the caller
  has a valid credential for the model's provider, with `"byok": true` in
  the response.

### Docs

A "Bring your own key" section in `marketing/src/pages/docs/index.astro`:
- the supported providers
- the fee
- the fallback setting
- what the regional rule means for credentials

## Scope — app

A "Provider keys" section in Settings:
- each supported provider with its status and `last4`
- add or replace
- remove
- the fallback toggle
- a one-line explanation of the fee

It is shown only when at least one provider is supported.

## Hard rules

- Edits only in `backend/src/**`, migration V40, `application.yml`,
  `app/src/**`, `app/e2e/**`, and the docs section.
- **No plaintext secret at rest, in a log, an audit entry, a response or a
  test fixture.** Fixtures use visibly fake keys.
- **The regional allowlist decides first, always.**
- With `godance.byok.providers` empty, behaviour is identical to the parent
  commit.
- Billing changes carry tests. Money in integer cents and microcents.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes. Do not mock the adapter interface.

## Opening move

Run the baselines. Post a table of every adapter:
- where it reads its key
- how it sends it
- what migrating it to the seam would take

Write the failing test: a user with a valid OpenAI credential calling an
OpenAI model from the Alicloud region still gets 503 and spends nothing.
Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests.
- **Tests cover:**
  - save with validation success and failure
  - the key never returned, logged or audited (assert on captured logs)
  - each migrated adapter sending the user's key, with the cassette checking
    the header
  - an unmigrated adapter using the platform's key
  - region refusal with a credential
  - a rejected key with and without fallback
  - fee billing and estimates
  - caps on the fee
  - account closure deleting credentials
- App tests for the Settings section.
- All work committed on `feature/byok`; `main` untouched.

## Out of scope (do not start)

- OAuth connections to providers.
- Organisation keys.
- Per-key credentials for API keys.
- Automatic switching between user and platform keys by price.
