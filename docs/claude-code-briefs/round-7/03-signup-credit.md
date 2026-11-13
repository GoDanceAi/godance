# Brief 03 — Signup credit (ships disabled)

**Branch:** `feature/signup-credit` (worktree `../gd-signup-credit`) off `main` @ `bce9491`
**Reserved migration:** none expected. V25 only if truly forced.
**Baseline:** full suite green on parent; record the exact count first

## Mission (one line)

Grant a one-time promotional credit when an account's email is first
verified, controlled by one setting that defaults to off.

## Why now

The marketing site says "Start free — no card needed", but a new account has
a zero balance and cannot run anything until it tops up.
`CreditSource.PROMO` has existed since the first ledger migration and no
code writes it. Brief 02 gives new users somewhere to spend a first credit.

Every grant is real provider spend, so the code default for the amount is
0 and a fresh deployment grants nothing. The decided amount is $1.00:
production turns the feature on with `PROMO_SIGNUP_CREDIT_CENTS=100`.

## Read first

1. This brief and `00-coordination.md`
2. `backend/CLAUDE.md`
3. `service/CreditLedgerService.java` — `topUp` and its idempotency handling
4. The places an email becomes verified:
   - `service/AuthService.java` — the email verification method, and
     password-reset completion (both call `setEmailVerified(true)`)
   - `service/OAuthLoginService.java` — linking a social sign-in to an
     existing account, and creating a new account from one
5. `service/ParentalConsentService.java` — where consent is granted
6. `service/AuditLogService.java` for the audit-event pattern, and
   `config/BillingProperties.java` for the properties pattern
7. `service/BillingService.java` — how the existing `topUp` callers behave,
   so a promo grant triggers nothing meant for a payment

## Scope

### Settings

- `godance.promo.signup-credit-cents`, from `PROMO_SIGNUP_CREDIT_CENTS`,
  default `0`. Zero disables the feature entirely.
- `godance.promo.signup-credit-daily-limit`, default `200`: the most grants
  in one UTC day. Past it, no further grants that day.

### One service, one method

`SignupCreditService.grantIfEligible(user)`. It grants only when all of
these hold:

- the amount is above zero
- the email is verified and is a real address, not the placeholder given to
  a social sign-in that shared none (`User.SYNTHETIC_EMAIL_SUFFIX`)
- the account is active
- the age status is `ADULT` or `CONSENT_GRANTED`
- today's grants are under the daily limit

The grant is `CreditLedgerService.topUp` with source `PROMO` and idempotency
key `promo-signup-<userId>`, so an account can never receive it twice,
whichever path verifies it and however often.

### Call sites

Call it after the user row is saved, at each of these:

1. email verification
2. password-reset completion, which also verifies the email
3. a social sign-in linked to an existing account
4. a new account created from a social sign-in with an email
5. parental consent granted, for an account whose email was already verified

### Behaviour around the edges

- A failure inside the grant never fails the surrounding sign-in or
  verification. Log it and write an audit event.
- Accounts that were verified before the feature is enabled receive nothing.
  There is no backfill and no grant on ordinary login.
- A changed email that is verified again earns nothing more; the
  idempotency key already exists.
- Audit events: `promo.signup_credit.granted` with the amount, and
  `promo.signup_credit.skipped` with the reason when the daily limit blocks a
  grant.
- Check that the admin ledger view shows a `PROMO` row correctly. Report it
  if it does not; fixing the app is not in this brief.

## Hard rules

- Edits only in `backend/src/**` and `application.yml` (plus the env
  template if the repo has one for backend settings).
- No change to `CreditLedgerService` signatures.
- No frontend or marketing change.
- Money in integer cents. Ledger entries are append-only.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baseline. Read every `topUp` caller and confirm in writing that
nothing downstream assumes a `topUp` is a payment (receipts, consent,
auto-recharge). Then list the five call sites as file and line, and post a
3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests.
- With the amount at 0, behaviour is identical to the parent commit and no
  ledger row is ever written.
- With an amount set, each of the five paths grants exactly once, and any
  two paths in sequence still grant once.
- A pending-consent account gets nothing on verification and the credit on
  consent.
- A placeholder-email account gets nothing until a real address is verified.
- The daily limit stops grants and records the skip.
- A forced ledger failure leaves verification successful.

## Out of scope (do not start)

Referral rewards. Promo codes. Expiring promo credit. Restricting promo
credit to particular models. Any app or marketing change. Backfilling
existing accounts.
