# Brief 01 — Referral (ships disabled)

**Branch:** `feature/referral` (worktree `../gd-referral`) off `main` with round 7 merged
**Reserved migration:** V26
**Baseline:** full backend suite green on parent, and `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/`; record the counts first

## Mission (one line)

Let a user invite others with a link, and credit both sides once when the
invited user first tops up, behind settings that default to off.

## Why now

Round 7 gives a new user something to do in the app. A referral gives an
existing user a reason to bring the next one. The reward is paid on the
invited user's first top-up, not on registration, so an account that never
pays earns nobody anything.

Every reward is real provider spend, so both amounts default to 0 and the
feature does nothing until the operator sets them.

## Read first

1. This brief and `00-coordination.md` (the referral contract is binding)
2. `backend/CLAUDE.md`
3. Round 7 brief 03 and the code it produced: the promo settings and the
   signup credit's ledger entry, audit event and tests. Follow that pattern.
4. `service/BillingService.java`: `creditCheckoutSession` (card top-ups,
   including the early return for a parental consent payment) and the
   NOWPayments path. `service/AutoRechargeService.java` for the automatic
   top-up, which must not count.
5. `service/CreditLedgerService.java`: `topUp` and `hasEntryFrom`
6. `domain/CreditSource.java`. The ledger's `source` column is plain text,
   so a new value needs no migration.
7. `config/SecurityConfig.java`, and the existing rate limiter used on the
   auth routes
8. App: `app/src/pages/SignupPage.tsx`, `LoginPage.tsx`,
   `OAuthCallbackPage.tsx`, `BillingPage.tsx`, `app/src/lib/api.ts`,
   `app/e2e/mock-api.ts`

## Scope — backend

### Settings (`godance.promo.referral.*`)

| Setting | Default | Meaning |
|---|---|---|
| `referrer-reward-cents` | 0 | Credit to the inviting user |
| `referee-reward-cents` | 0 | Credit to the invited user |
| `min-topup-cents` | 1000 | Smallest top-up that qualifies |
| `monthly-reward-limit` | 20 | Rewards one referrer can earn in a UTC month |
| `claim-window-hours` | 168 | How long after sign-up a code can be claimed |
| `qualifying-sources` | `STRIPE` | Which top-up sources qualify |

With both rewards at 0 the feature is off: `GET /v1/referrals/me` says so,
a claim is refused, and no top-up path does anything new.

### Data (V26)

- A unique referral code per user, created the first time the user asks
  for it. Eight characters from an alphabet with no look-alikes.
- A `referrals` table: referrer, invited user (unique: a user is invited at
  most once), when it was claimed, its status (`PENDING`, `REWARDED`,
  `VOID`), when and on which top-up it was rewarded, and what each side got.

### Claim: `POST /v1/referrals/claim`

Accept the code only when all of these hold:
- the feature is on and the code exists
- the code is not the caller's own, and the referrer's account is active
- the caller's account is no older than the claim window
- the caller has no referral already and no paid top-up yet
- both accounts are adults (age status `ADULT`)

Rate-limit claims per account and per address with the existing limiter,
so codes cannot be guessed by trying them.

### Reward

- **Trigger.** A top-up that is newly applied, from a qualifying source, of
  at least the minimum, for a user with a `PENDING` referral. It is the
  first such top-up that counts.
- **What does not count.** A duplicate webhook delivery. A parental consent
  payment. An automatic recharge. A top-up below the minimum, which leaves
  the referral pending for a later one.
- **What is written.** One ledger credit to each side with a new source,
  `REFERRAL`, and idempotency keys built from the referral's id, and the
  referral marked `REWARDED`, all in one transaction.
- **The monthly limit** applies to the referrer only. Past it, the invited
  user is still credited and the referrer gets nothing for that referral.
- **Never in the way of the payment.** The reward runs after the top-up has
  committed. A failure in it is logged and audited, and never fails or
  rolls back the top-up.
- **Not lost.** A periodic sweep re-checks pending referrals whose invited
  user already has a qualifying top-up. The idempotency keys make a repeat
  harmless.

### Read: `GET /v1/referrals/me`

As in the contract. Counts only: no name, email or id of anyone invited.

### Audit events

`referral.claimed`, `referral.rewarded` with both amounts, and
`referral.skipped` with the reason.

## Scope — app

- **Capture.** `/signup?ref=<code>` and `/login?ref=<code>` store the code
  when it has the right shape. Keep it in `localStorage` for 30 days, so it
  survives a social sign-in round trip and an email opened in another tab.
- **Claim.** Whenever a user is signed in and a stored code exists, call
  the claim endpoint once, then clear the code whatever the answer. The
  server decides eligibility; the app does not try to.
- **Referral page** at `/referral`, with a navigation entry, both only
  when `enabled` is true:
  - the link, with a Copy button
  - what each side gets and the minimum top-up, from the API's numbers
  - invited, rewarded and earned
  - the terms text
- **Billing page.** When `pending_reward_cents` is set, one line saying
  what the user will receive and on what top-up.
- A stored code never changes where the user lands after sign-in.

## Hard rules

- Edits only in `backend/src/**`, migration V26, `application.yml`,
  `app/src/**` and `app/e2e/**`.
- With both rewards at 0, behaviour is identical to the parent commit.
- No change to `CreditLedgerService` signatures, and none to how a top-up
  itself is credited.
- A referrer never learns who they invited.
- No marketing change. No email is sent.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baselines. Read both top-up paths and state, with file and line,
where a newly applied top-up is known and how a consent payment and an
automatic recharge are told apart from it. Then post a 3–5 bullet plan
before writing code.

## Acceptance criteria

- `mvn -B verify` green, and the app's lint, build and e2e green.
- Backend tests cover:
  - off by default: no claim accepted, no ledger row, on any top-up
  - a claim refused for each condition in the list, and rate-limited
  - a qualifying card top-up credits both sides exactly once, and a
    redelivered webhook credits nothing more
  - a top-up below the minimum leaves the referral pending, and a later
    qualifying one rewards it
  - a consent payment and an automatic recharge reward nothing
  - a USDT top-up rewards nothing by default, and does when enabled
  - past the monthly limit the invited user is credited and the referrer
    is not
  - a forced failure in the reward leaves the top-up applied, and the
    sweep then pays it once
  - an API key cannot call either endpoint
- App tests cover: capture and claim after password sign-up, after social
  sign-in, and after sign-in in another tab; the code cleared after any
  answer; the page and navigation entry hidden when disabled; the Billing
  line.
- A curl transcript of both endpoints in the final summary.
- All work committed on `feature/referral`; `main` untouched.

## Out of scope (do not start)

Promo codes. Rewards that expire. Taking a reward back after a dispute.
Checking whether two accounts share a card. Referral links on the marketing
site. Emails about referrals. An admin view of referrals.
