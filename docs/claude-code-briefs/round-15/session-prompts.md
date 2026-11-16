# Round 15 — Opening prompts

Paste one as the first message of each session, or use `goal-prompt.md` to
run the whole round in one session.

Start after rounds 7–14 are on `main`. All five sessions can run at the same
time. Session B rebases onto A for the dashboard, and C rebases onto A for
the chat page.

## Session A — App links

```
You're a Claude Code session working on Godance round-15 brief 01 — App
links: a Gallery link in the app, a routing policies screen and policies in
the Chat model picker, and a fallbacks option on the new-batch page, each
shown only where it works.

Setup: working dir is a dedicated git worktree on branch feature/app-links,
off main. Confirm lint, build and e2e are green in app/ before changing
anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-15/01-app-links.md (the brief)
2. docs/claude-code-briefs/round-15/00-coordination.md ("links appear only
   when usable" is binding)
3. app/src/components/Layout.tsx, lib/gallery.ts, components/GalleryExamples.tsx,
   pages/SettingsPage.tsx, components/ProviderKeysSection.tsx,
   pages/NewBatchPage.tsx, pages/ChatPage.tsx, pages/DashboardPage.tsx
4. Backend (read only): web/RoutingPolicyController.java,
   routing/RoutingPolicyDocuments.java, routing/RoutingPolicyValidator.java,
   web/dto/BatchDtos.java

Hard rules:
- Commit to feature/app-links as you go. Do NOT merge to main. Do NOT push or
  open a pull request unless asked. main auto-deploys.
- No backend change. Every link has e2e tests for shown and hidden.
- Never send fallbacks: true unless the user ticked the box.

Opening move: run the baseline. Write the failing e2e test for the Gallery
link with a one-recipe index and an empty one. Then summarize your plan in
3–5 bullets before writing code.

Done = app lint, build and e2e green on desktop and mobile, every acceptance
case covered, screenshots in the summary, all commits on your branch, main
untouched. Hand back.
```

## Session B — Billing screens

```
You're a Claude Code session working on Godance round-15 brief 02 — Billing
screens: auto-recharge settings in Billing with exact amounts and a
confirmation, and a usage page linked from Billing and the dashboard.

Setup: working dir is a dedicated git worktree on branch
feature/billing-screens, off main; rebase onto brief 01 before merging.
Confirm lint, build and e2e are green in app/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-15/02-billing-screens.md (the brief)
2. docs/claude-code-briefs/round-15/00-coordination.md (the money rule is
   binding)
3. Backend (read only): web/BillingController.java, web/dto/BillingDtos.java,
   service/AutoRechargeService.java, service/BillingService.java,
   web/UsageController.java
4. app/src/pages/BillingPage.tsx, lib/auth.tsx (formatCents),
   pages/DashboardPage.tsx, e2e/billing.spec.ts

Hard rules:
- Commit to feature/billing-screens as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- No backend change. Nothing that can charge a card without a confirmation
  stating both amounts. Integer cents only.
- Never render providerCostCents.

Opening move: run the baseline. State, with file and line, the limits
AutoRechargeService enforces and when it disables itself. Write the failing
e2e test for the no-card-on-file state. Then summarize your plan in 3–5
bullets before writing code.

Done = app lint, build and e2e green, every acceptance case covered,
screenshots in the summary, all commits on your branch, main untouched.
Hand back.
```

## Session C — Chat history

```
You're a Claude Code session working on Godance round-15 brief 03 — Chat
history: save every chat as a conversation through the existing API, and
let users find, reopen, continue and remove them.

Setup: working dir is a dedicated git worktree on branch feature/chat-history,
off main; rebase onto brief 01 before merging. Confirm lint, build and e2e
are green in app/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-15/03-chat-history.md (the brief)
2. docs/claude-code-briefs/round-15/00-coordination.md
3. Backend (read only): web/ConversationController.java,
   web/dto/ConversationDtos.java, service/ConversationService.java
4. app/src/pages/ChatPage.tsx, e2e/chat.spec.ts, e2e/sse.ts, e2e/mock-api.ts

Hard rules:
- Commit to feature/chat-history as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- No backend change. A failed save never breaks the chat. Existing chat specs
  stay green unchanged. Messages render as text.

Opening move: run the baseline. State what the service stores per message
and what archive does. Write the failing e2e test: send, reload /chat/<id>,
messages still there. Then summarize your plan in 3–5 bullets before
writing code.

Done = app lint, build and e2e green on desktop and mobile, every acceptance
case covered, screenshots in the summary, all commits on your branch, main
untouched. Hand back.
```

## Session D — Site links and docs

```
You're a Claude Code session working on Godance round-15 brief 04 — Site
links and docs: link the community and comparison pages, let the marketing
deploy read the gallery and community switches from repository variables,
and document routing policies, bring your own key and the public community
API.

Setup: working dir is a dedicated git worktree on branch feature/site-links,
off main. Confirm `npm ci && npm run lint && npm run build && npm test` is
green in marketing/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-15/04-site-links-and-docs.md (the brief)
2. docs/claude-code-briefs/round-15/00-coordination.md
3. marketing/src/components/Nav.astro, Footer.astro, pages/index.astro,
   pages/gallery/**, pages/community/**, lib/gallery-config.ts,
   lib/community.ts, pages/docs/index.astro, public/llms*.txt, scripts/llms/**
4. .github/workflows/deploy-frontends.yml, marketing/README.md

Hard rules:
- Commit to feature/site-links as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- With every variable unset the built site is unchanged apart from docs;
  prove it with a build diff. No secrets in the workflow. No backend change.

Opening move: run the baseline. Write the failing test for the Community
link shown with community on and one public recipe, absent when off. Then
summarize your plan in 3–5 bullets before writing code.

Done = marketing lint, build and test green, the build diff and screenshots
in the summary, all commits on your branch, main untouched. Hand back.
```

## Session E — Admin switches panel

```
You're a Claude Code session working on Godance round-15 brief 05 — Admin
switches panel: a read-only GET /v1/admin/features and an admin Switches
tab showing which feature switches are on and what each hides.

Setup: working dir is a dedicated git worktree on branch
feature/admin-switches, off main. Confirm the backend test count with a full
run, and that lint, build and e2e are green in app/, before changing
anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-15/05-admin-switches.md (the brief)
2. docs/claude-code-briefs/round-15/00-coordination.md (the features
   contract is binding)
3. backend/CLAUDE.md; the properties classes behind each switch;
   web/AdminConsoleController.java and AdminRoles
4. app/src/pages/admin/AdminLayout.tsx, OverviewPage.tsx, lib/gallery.ts,
   e2e/admin-roles.spec.ts

Hard rules:
- Commit to feature/admin-switches as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- Read-only. Never a secret or an allow-list member in the response. No
  migration, no default changes.

Opening move: run the baselines. List every switch rounds 7–14 added with its
property and environment variable. Write the failing test for SUPPORT
getting the contract's keys and USER getting 403. Then summarize your plan
in 3–5 bullets before writing code.

Done = mvn -B verify green, app lint, build and e2e green, every acceptance
case covered, a screenshot in the summary, all commits on your branch, main
untouched. Hand back.
```
