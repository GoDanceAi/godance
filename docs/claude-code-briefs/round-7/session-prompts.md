# Round 7 — Opening prompts (paste as the first message of each session)

## Session A — Job API foundations

```
You're a Claude Code session working on Godance round-7 brief 01 — Job API
foundations: record every output of a generation job, return the prompt and
working links when a job is read, page the job list, describe each model's
capabilities, add a cost-estimate endpoint, and reject an output count a
model cannot produce before any charge.

Setup: working dir is a dedicated git worktree on branch
feature/job-api-foundations, off main at commit bce9491. Confirm the
baseline test count with a full run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-7/01-job-api-foundations.md (the brief)
2. docs/claude-code-briefs/round-7/00-coordination.md (the shared contract
   is binding; V23 and V24 are yours; V12 stays unused)
3. backend/CLAUDE.md
4. web/JobController.java, web/dto/ImageDtos.java,
   service/GenerationJobSettlement.java, service/ImageGenerationService.java,
   service/VideoGenerationService.java
5. gateway/ModelCatalog.java, web/ModelsController.java,
   gateway/CostCalculator.java, and every image and video adapter in
   gateway/providers/

Hard rules:
- Commit to feature/job-api-foundations as you go. Do NOT merge to main.
  Do NOT push or open a pull request unless asked. main auto-deploys.
- Before tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- Additions only on the wire: no existing field changes name, type or meaning.
- Billing and ledger changes carry a test in the same change.
- Do NOT touch the regional allowlist, routing/, backend/deploy/ or app/.

Opening move: run the baseline, read the delivered-count and default-length
code named in item 5, then write the failing test for the up-front count
check (`n: 2` on a Flux model answers 400 and writes no ledger row). Then
summarize your build plan in 3–5 bullets, including the capability values
you will seed and their source, before writing production code.

Done = mvn -B verify green (baseline + yours), marketing builds, a curl
transcript of each new or changed endpoint, all commits on your branch, main
untouched. Hand back.
```

## Session B — Studio and History

```
You're a Claude Code session working on Godance round-7 brief 02 — Studio
and History: app pages that let a signed-in user generate images and videos
with the price shown before running, and browse and re-run past generations.

Setup: working dir is a dedicated git worktree on branch
feature/studio-history, off main at commit bce9491. Confirm lint, build and
the e2e suite are green in app/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-7/02-studio-and-history.md (the brief)
2. docs/claude-code-briefs/round-7/00-coordination.md (the shared contract;
   the backend for it is being built in parallel, so develop against mocks)
3. app/src/App.tsx, app/src/components/Layout.tsx
4. app/src/pages/ChatPage.tsx, app/src/lib/api.ts, app/src/lib/auth.tsx
5. app/src/pages/BillingPage.tsx, app/src/index.css
6. app/e2e/mock-api.ts, app/e2e/chat.spec.ts

Hard rules:
- Commit to feature/studio-history as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Edits only in app/src/** and app/e2e/**. No backend change.
- No calls outside the contract. If it lacks something you need, stop and
  raise it; do not invent a field.
- No new runtime dependency without flagging it first.
- Never render a real API key; the code panel uses $GODANCE_API_KEY.

Opening move: run the baseline, read ChatPage.tsx and mock-api.ts end to
end, then summarize your build plan in 3–5 bullets with the component split
and the endpoints you will mock, before writing code.

Done = npm run lint, npm run build and npm run test:e2e green on desktop and
mobile, the new create and history specs passing, screenshots of each page
in light and dark, all commits on your branch, main untouched. Hand back.
```

## Session C — Signup credit

```
You're a Claude Code session working on Godance round-7 brief 03 — Signup
credit: a one-time promotional ledger credit when an account's email is
first verified, behind a setting that defaults to off.

Setup: working dir is a dedicated git worktree on branch
feature/signup-credit, off main at commit bce9491. Confirm the baseline test
count with a full run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-7/03-signup-credit.md (the brief)
2. docs/claude-code-briefs/round-7/00-coordination.md (no migration is
   reserved for you; V25 only if truly forced)
3. backend/CLAUDE.md
4. service/CreditLedgerService.java and every caller of topUp
5. service/AuthService.java, service/OAuthLoginService.java,
   service/ParentalConsentService.java
6. service/AuditLogService.java, config/BillingProperties.java

Hard rules:
- Commit to feature/signup-credit as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- With the amount at 0 the feature must change nothing.
- No change to CreditLedgerService signatures. No frontend or marketing change.
- A failed grant never fails the sign-in or verification around it.

Opening move: run the baseline, read every topUp caller and state whether
anything downstream assumes a topUp is a payment, list the five call sites
as file and line, then summarize your build plan in 3–5 bullets before
writing code.

Done = mvn -B verify green (baseline + yours), every acceptance case in the
brief covered by a test, all commits on your branch, main untouched. Hand back.
```
