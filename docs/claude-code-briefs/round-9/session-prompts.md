# Round 9 — Opening prompts (paste as the first message of each session)

Start both only after all of round 7 is merged to `main`. They can run at
the same time.

## Session A — Referral

```
You're a Claude Code session working on Godance round-9 brief 01 —
Referral: a user invites others with a link, and both sides are credited
once when the invited user first tops up. It ships disabled: both reward
amounts default to 0.

Setup: working dir is a dedicated git worktree on branch feature/referral,
off main with round 7 merged. Confirm the backend test count with a full
run, and that lint, build and e2e are green in app/, before changing
anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-9/01-referral.md (the brief)
2. docs/claude-code-briefs/round-9/00-coordination.md (the referral
   contract is binding; V26 is yours)
3. backend/CLAUDE.md
4. docs/claude-code-briefs/round-7/03-signup-credit.md and the code it
   produced — follow its settings, ledger and audit pattern
5. service/BillingService.java (both top-up paths),
   service/AutoRechargeService.java, service/CreditLedgerService.java
6. app/src/pages/SignupPage.tsx, LoginPage.tsx, OAuthCallbackPage.tsx,
   BillingPage.tsx, app/src/lib/api.ts, app/e2e/mock-api.ts

Hard rules:
- Commit to feature/referral as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- With both rewards at 0 the feature must change nothing.
- The reward runs after the top-up has committed and can never fail or
  roll back the payment. No change to how a top-up itself is credited.
- A parental consent payment and an automatic recharge never earn a reward.
- A referrer never learns who they invited.
- No change to CreditLedgerService signatures. No marketing change.

Opening move: run the baselines. Read both top-up paths and state, with
file and line, where a newly applied top-up is known and how a consent
payment and an automatic recharge are told apart from it. Then summarize
your build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), app lint, build and e2e
green, every acceptance case in the brief covered by a test, a curl
transcript of both endpoints, all commits on your branch, main untouched.
Hand back.
```

## Session B — Saved generations

```
You're a Claude Code session working on Godance round-9 brief 02 — Saved
generations: a user saves a generation so it is kept past the 90-day
deletion, up to a limit per account, and finds saved ones in History.

Setup: working dir is a dedicated git worktree on branch
feature/saved-generations, off main with round 7 merged. Confirm the
backend test count with a full run, and that lint, build and e2e are green
in app/, before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-9/02-saved-generations.md (the brief)
2. docs/claude-code-briefs/round-9/00-coordination.md (the saved-generation
   contract is binding; V27 is yours)
3. backend/CLAUDE.md
4. docs/claude-code-briefs/round-7/01-job-api-foundations.md and the code
   it produced
5. service/DataRetentionService.java, service/AccountClosureService.java,
   repository/GenerationJobRepository.java, and the retention tests
6. The app's History and job pages and Studio result, app/src/lib/api.ts,
   app/e2e/mock-api.ts

Hard rules:
- Commit to feature/saved-generations as you go. Do NOT merge to main. Do
  NOT push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- Closing an account deletes saved jobs exactly as it deletes the rest.
- Additions only on the wire. Nothing here changes what a job costs.
- The saved-recipes item is optional: do it only if round 8 brief 03 is on
  main, and say in your summary whether you did.

Opening move: run the baselines. Read the purge path end to end and write
the failing test first: a saved job older than the retention period
survives a purge run with its files still in storage. Then summarize your
build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), app lint, build and e2e
green, marketing builds, every acceptance case in the brief covered by a
test, a curl transcript of the new routes, all commits on your branch,
main untouched. Hand back.
```
