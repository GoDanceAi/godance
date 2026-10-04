# Round 5 — Opening prompts (paste as the first message of each session)

## Session A — Admin Panel Phase A

```
You're a Claude Code session working on Godance round-5 brief 01 — Admin
Control Panel Phase A: a browser UI for pricing-proposal approvals, catalog
management, user lookup/credit adjustment, and a revenue-vs-cost overview.

Setup: working dir is a dedicated git worktree on branch feature/admin-panel,
off main at commit c9d87b1. Confirm the baseline test count with a full run
before changing anything.

Read these before writing any code:
1. claude-code-briefs/round-5/01-admin-panel-phase-a.md (the brief)
2. claude-code-briefs/round-5/00-coordination.md (reserved migrations: V12
   is yours IF needed; V13 belongs to the email session)
3. backend/CLAUDE.md
4. web/AdminPricingController.java, security/AdminAuthorizationFilter.java,
   config/AdminProperties.java — extend this plumbing, don't reinvent it
5. app/src/App.tsx, app/src/lib/api.ts, app/src/pages/BillingPage.tsx

Hard rules:
- Commit to feature/admin-panel as you go. `git merge --no-ff -m "..."` only.
- Do NOT `git push`. Do NOT deploy.
- Before `mvn clean test`: `cd backend && docker compose up -d`.
- Do NOT touch .env.prod.template, routing/, backend/deploy/, marketing/.
- Every mutating admin endpoint: audit event + a 403 test for
  non-allowlisted JWTs.

Opening move: boot the local stack, mint an allowlisted admin JWT the way
the existing admin integration test does, curl the existing proposals
endpoints, and read the credit-ledger write path. Then summarize your build
plan in 3–5 bullets before you start.

Done = mvn clean test green (baseline + yours), app builds (npm ci && npm
run build), four admin pages working locally, all commits on your branch,
main untouched. Hand back.
```

## Session B — Email Sending

```
You're a Claude Code session working on Godance round-5 brief 02 — Email
Sending: replace the [email-stub] logs with a real Resend-backed sender
(verification, 80% cap warning, auto-recharge failure, pricing digest),
with a logging fallback for dev.

Setup: working dir is a dedicated git worktree on branch
feature/email-sending, off main at commit c9d87b1. Confirm the baseline
test count with a full run before changing anything.

Read these before writing any code:
1. claude-code-briefs/round-5/02-email-sending.md (the brief)
2. claude-code-briefs/round-5/00-coordination.md (you own
   .env.prod.template this round; V13 only if truly forced)
3. backend/CLAUDE.md
4. Every `email-stub` call site (grep it), SpendingLimitService's warning
   path, the auto-recharge failure path, and the pricing cron's digest seam
5. An existing WireMock-based integration test for the API-mock pattern

Hard rules:
- Commit to feature/email-sending as you go. No push, no deploy.
- Scope is backend mail package + the four call sites + tests +
  .env.prod.template. No frontend, no controllers, no other files.
- Mail failures: log + audit `mail.send.failed`, NEVER propagate into the
  calling business flow.
- Before `mvn clean test`: `cd backend && docker compose up -d`.

Opening move: list every call site you'll wire (file:line) and the Resend
request shape you'll pin in a WireMock fixture, as a 3–5 bullet plan,
before writing code.

Done = mvn clean test green (baseline + yours), [email-stub] behavior
preserved under the dummy key, all commits on your branch, main untouched.
Hand back.
```
