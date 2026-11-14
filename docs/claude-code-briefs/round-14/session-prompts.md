# Round 14 — Opening prompts (paste as the first message of each session)

Start only after round 13 is merged to `main`, and after the briefs have
been checked against what rounds 10–13 actually produced (see
`00-coordination.md`).

A, C, D and E can run at the same time. B can start alongside them against
mocks, and rebases onto A before it merges. D needs the operator's routing
design documents before it starts.

## Session A — Published recipes, profiles and follows

```
You're a Claude Code session working on Godance round-14 brief 01 —
Published recipes, profiles and follows: adult users with a profile publish
a recipe with example outputs and a licence; after a moderator approves it,
anyone can see and run it, follow its author, and see what it was made from
and how often it is run.

Setup: working dir is a dedicated git worktree on branch
feature/published-recipes, off main with round 13 merged. Confirm the
backend test count with a full run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-14/01-published-recipes.md (the brief)
2. docs/claude-code-briefs/round-14/00-coordination.md (the publishing
   contract is binding; V39 is yours)
3. backend/CLAUDE.md
4. Round 10's recipes and origin record, round 11's input slots and policy
5. docs/gallery-content-standard.md, the admin controllers and AdminRoles,
   RateLimiter, AuditLogService, DataRetentionService, AccountClosureService,
   ObjectStorageClient

Hard rules:
- Commit to feature/published-recipes as you go. Do NOT merge to main. Do
  NOT push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- Nothing is public before a moderator approves it.
- Public routes expose exactly the listed fields — never a user id, email,
  source job or input file.
- Examples are copied on approval and never depend on the source job.

Opening move: run the baseline. Write the failing test that a PENDING item
is absent from /v1/public/recipes and present after approval with exactly
the exposed fields. Then summarize your build plan in 3–5 bullets before
writing code.

Done = mvn -B verify green (baseline + yours), every acceptance case covered
by a test, a curl transcript from profile to run, all commits on your
branch, main untouched. Hand back.
```

## Session B — Community pages

```
You're a Claude Code session working on Godance round-14 brief 02 —
Community pages: static, indexable pages for published recipes and profiles
on the marketing site, and in the app the profile, Publish flow, following
feed, reports and the moderation queue.

Setup: working dir is a dedicated git worktree on branch
feature/community-pages, off main with round 13 merged. Develop against
mocks; rebase onto brief 01 before merging. Confirm app and marketing checks
are green before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-14/02-community-pages.md (the brief)
2. docs/claude-code-briefs/round-14/00-coordination.md (the publishing
   contract is binding)
3. marketing/src/lib/catalog.ts, the gallery pages and switch, Layout.astro,
   .github/workflows/deploy-frontends.yml
4. The app's recipe pages, the Studio hand-off, Settings, the admin pages,
   api.ts, e2e/mock-api.ts

Hard rules:
- Commit to feature/community-pages as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- The marketing build never fails because the API is down.
- Only public routes feed public pages.
- No client framework or new runtime dependency on the marketing site.

Opening move: run the baselines. Write the failing test that a build with
two mocked public recipes produces two community pages and a profile page.
Then summarize your build plan in 3–5 bullets before writing code.

Done = marketing and app checks green on desktop and mobile, every
acceptance case covered, screenshots in the summary, all commits on your
branch, main untouched. Hand back.
```

## Session C — Bring your own key

```
You're a Claude Code session working on Godance round-14 brief 03 — Bring
your own key: users store provider keys encrypted; requests to that
provider go out on their key and Godance charges only its fee; the regional
allowlist and caps still apply.

Setup: working dir is a dedicated git worktree on branch feature/byok, off
main with round 13 merged. Confirm the backend count and app checks before
changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-14/03-bring-your-own-key.md (the brief)
2. docs/claude-code-briefs/round-14/00-coordination.md (the credential
   routes are binding; V40 is yours)
3. backend/CLAUDE.md (hard constraints 1 and 2)
4. security/KeyVault.java, ProviderGateway, the image and video gateways,
   AlicloudProviderAllowlist, the provider Properties classes, every adapter
5. CostCalculator, CreditLedgerService.settle, UsageRecord,
   GenerationJobSettlement, ChatCompletionService, SpendingLimitService

Hard rules:
- Commit to feature/byok as you go. Do NOT merge to main. Do NOT push or
  open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- No plaintext key at rest, in logs, audit entries, responses or fixtures.
- The regional allowlist decides first, always; a credential never reaches
  a forbidden provider.
- With godance.byok.providers empty, behaviour is identical to the parent.

Opening move: run the baselines. Post a table of every adapter's key
handling and migration effort. Write the failing test that an OpenAI
credential from the Alicloud region still gets 503 and spends nothing. Then
summarize your build plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), app checks green, every
acceptance case covered, all commits on your branch, main untouched. Hand
back.
```

## Session D — Routing policies

```
You're a Claude Code session working on Godance round-14 brief 04 — Routing
policies: round 4's Phase 2 (routed_policy and routed_rule, a margin view)
and Phase 3 (user policies, /v1/routing/policies, the validator,
X-Godance-Route, the compliance opt-out), plus opt-in fallbacks for batch
rows.

Setup: working dir is a dedicated git worktree on branch
feature/routing-policies, off main with round 13 merged. You need the
operator's routing design and roadmap documents (not in the repository);
ask for them first. Confirm the backend test count with a full run before
changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-14/04-routing-policies.md (the brief)
2. docs/claude-code-briefs/round-14/00-coordination.md (V41 is yours)
3. backend/CLAUDE.md
4. docs/claude-code-briefs/round-4/* and the operator's design documents
5. routing/**, ChatCompletionService, ProviderGateway, UsageRecord, the
   admin usage summary, round 12's batch service

Hard rules:
- Commit to feature/routing-policies as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- Every round 4 invariant holds; a direct model call is unchanged.
- With user policies disabled and no batch opting in, behaviour is
  identical to the parent commit.
- Content refusals are never retried on another model.

Opening move: run the baseline. Quote the Phase 3 policy shape and validator
rules from the design documents. Write the failing test that a routed chat
request records routed_policy and routed_rule. Then summarize your build
plan in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours, round 4 tests included), every
acceptance case covered, curl transcripts in the summary, all commits on
your branch, main untouched. Hand back.
```

## Session E — Self-hosting guide

```
You're a Claude Code session working on Godance round-14 brief 05 —
Self-hosting guide: a complete, honest guide and packaging to run your own
copy from an empty server, with the configuration reference generated and
checked in CI.

Setup: working dir is a dedicated git worktree on branch
feature/self-hosting, off main with round 13 merged. Confirm the CI checks
are green on parent before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-14/05-self-hosting.md (the brief)
2. docs/claude-code-briefs/round-14/00-coordination.md
3. README.md, backend/README.md, backend/CLAUDE.md, backend/HANDOFF.md
4. backend/deploy/* (read only), backend/docker-compose.yml,
   application.yml and application-prod.yml, app/.env.example,
   .github/workflows/*

Hard rules:
- Commit to feature/self-hosting as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- No application code changes and no changes to backend/deploy/.
- No real secret, private host or operator note in any file.
- Nothing documented that is not true of main.

Opening move: run the checks. List every environment variable the
production compose file uses, with where each is read. Then summarize your
plan in 3–5 bullets before writing anything.

Done = a clean-machine run of the quick start recorded in the summary, the
configuration reference generated with its drift check, the compose check in
CI, all commits on your branch, main untouched. Hand back.
```
