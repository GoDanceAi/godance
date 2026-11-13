# Round 9 — Coordination

**Date opened:** 2026-11-12 · **Parent:** `main` with all of round 7 merged
**Workstreams:** 01 Referral (backend and app, ships disabled) ·
02 Saved generations (backend and app)

Context: two ways to keep and grow the people round 7 brings in. A referral
credits both sides when an invited user first tops up. A saved generation is
kept past the 90-day deletion that otherwise applies to every job.

**Prerequisites.** Round 7 briefs 01, 02 and 03 are on `main`: 01 gives jobs
their outputs and deletion date, 02 gives the app its History page, and 03
gives the promo settings and the pattern for a promotional ledger credit.
The two workstreams here do not depend on each other and can run together.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-referral -b feature/referral main
git worktree add ../gd-saved    -b feature/saved-generations main
```

## Reserved resources

| Resource | 01 referral | 02 saved generations |
|---|---|---|
| Flyway migrations | **V26** | **V27** |
| Backend | new `service/Referral*`, `web/Referral*`, the two top-up paths in `service/BillingService.java` | `web/JobController.java`, job DTOs, `repository/GenerationJobRepository.java`, `service/DataRetentionService.java` |
| App | new `/referral` page, sign-up and sign-in pages (capture only), Billing page (one line) | History and job pages, Studio result |
| Marketing | none | `docs/index.astro` (last commit only) |
| Config | `godance.promo.referral.*` | `godance.retention.saved-limit` |

Check that V26 and V27 are still free before using them.

## Shared contract

Additions only. Nothing that exists today changes shape.

### Referral (01)

Both endpoints need a signed-in session. An API key cannot call them.

```json
GET /v1/referrals/me
{ "enabled": true,
  "code": "K7QM2XPD",
  "referrer_reward_cents": 100,
  "referee_reward_cents": 100,
  "min_topup_cents": 1000,
  "invited": 3,
  "rewarded": 1,
  "earned_cents": 100,
  "pending_reward_cents": null }

POST /v1/referrals/claim   { "code": "K7QM2XPD" }
→ 200 { "referee_reward_cents": 100, "min_topup_cents": 1000 }
```

- `enabled` is false when both reward amounts are 0. The other fields are
  then absent, and the app shows nothing.
- `pending_reward_cents` is what the caller will receive as an invited user
  on a qualifying top-up, or null when they were not invited.
- The referrer sees counts only, never who signed up.
- A refused claim is problem+json with a `code`:
  `referral_code_unknown` or `referral_not_eligible`.
- The link a user shares is `<app>/signup?ref=<code>`.

### Saved generations (02)

Session or API key, as for the other job routes.

```
PUT    /v1/jobs/{id}/saved   → 204
DELETE /v1/jobs/{id}/saved   → 204
GET    /v1/jobs?saved=true
```

- `JobView` gains `saved` (boolean).
- A saved job's `mediaExpiresAt` is null. Round 7's contract already allows
  that, and the app already shows no date for it.
- Only a `DONE` job that has not been purged can be saved. Anything else
  answers 409 with `code: "job_not_saveable"`.
- Past the account's limit, saving answers 409 with
  `code: "saved_limit_reached"`.
- Saving twice, or un-saving a job that is not saved, succeeds and changes
  nothing.

## Shared-file conflict watch

Both workstreams add lines to these. Keep each edit to the lines you need;
whoever merges second rebases.

- `config/SecurityConfig.java` — new route rules
- `service/AuditLogService.java` — new event constants
- `backend/src/main/resources/application.yml` — new settings
- `app/src/lib/api.ts` — new types
- `app/src/App.tsx`, `app/src/components/Layout.tsx` — 01 only (route and
  navigation entry)

Neither workstream changes a `CreditLedgerService` signature.

## Hard rules (both sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- Run the full suite on the parent commit and record the count first.
- Frontend: `npm run lint`, `npm run build` and `npm run test:e2e` stay
  green in `app/`.
- Billing, ledger and retention changes carry a test in the same change.
- Money in integer cents. Ledger entries are append-only and idempotent.
- The regional provider allowlist is not touched this round.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

Independent. Either merges first; the other rebases onto `main` before its
own merge.

## Operator decisions

Referral ships with both rewards at 0, so it does nothing until these are set:

- **Reward for each side.** Every reward is real provider spend. Set it with
  the platform fee in mind: with a 1000-cent minimum top-up and 100 cents to
  each side, an account that invites itself gets 20% extra on that top-up.
- **Minimum qualifying top-up.** Suggested: 1000 cents.
- **Rewards per referrer per month.** Suggested: 20. This is what bounds
  self-invitation.
- **Whether a USDT top-up qualifies.** The brief's default is card only.
- **Whether accounts under 16 take part.** The brief's default is no, on
  either side.
- **The offer's terms,** shown on the referral page.

Card disputes are not handled anywhere in the backend today, so a reward
paid on a top-up that is later disputed is not taken back automatically.

Saved generations:

- **The limit per account.** Suggested: 500. Saved files are kept at no
  charge for as long as the account exists.
- **The retention statement.** The privacy policy must say that saved
  generations are kept until un-saved or the account is closed.
