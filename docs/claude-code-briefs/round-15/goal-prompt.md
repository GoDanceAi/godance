# Round 15 — One-session goal prompt

For running the whole round in one Claude Code session with `/goal`. It is
under the 4,000-character limit. Paste the block as the goal.

```
Goal: finish Godance round 15 (docs/claude-code-briefs/round-15/) in this one session: every workstream meets its brief's acceptance criteria.

Read once: round-15/00-coordination.md (the features contract, "links appear only when usable", the money rule and the conflict watch are binding), round-15/session-prompts.md (each session's hard rules and opening move apply here), backend/CLAUDE.md. Rounds 7-14 are on main; use the real class and file names you find there.

Order and branches (one git worktree each, all off main):
1. 05 Admin switches panel: feature/admin-switches
2. 04 Site links and docs: feature/site-links
3. 01 App links: feature/app-links
4. 02 Billing screens: feature/billing-screens, off feature/app-links
5. 03 Chat history: feature/chat-history, off feature/app-links
PRs: 02 and 03 to feature/app-links; the rest to main. Merge order: 05 and 04 any time; 01, then 02 and 03.

Per workstream: read its brief in full and follow every section; run the baseline on its parent and record counts; do the opening move and post a 3-5 bullet plan before code; commit as you go ("area: summary"). Done = every acceptance criterion met with the evidence the brief asks for (e2e for each link shown and hidden, screenshots in light and dark, the site build diff), plus a short summary.

Rules:
- No backend change except 05's read-only GET /v1/admin/features. No migration. No switch default changes.
- Every new link or screen appears only when its feature works for that user.
- Auto-recharge: nothing that can charge a card without a confirmation stating both amounts; integer cents only; never render providerCostCents.
- A failed chat save never breaks the chat.
- With every repository variable unset, the built site is unchanged apart from the new docs.
- No secret, key or allow-list member in any response, file, fixture, screenshot or log.
- No new runtime dependency without flagging it.

Checks: backend `cd backend && docker compose up -d` then `mvn -B verify`; app `npm run lint`, `npm run build`, `npm run test:e2e`; marketing `npm ci && npm run lint && npm run build && npm test`. Without Docker, say so, run what you can, and use the PR's CI as the backend gate.

Git: never push to main, never merge. Push each feature branch and open one PR per workstream as listed; state the merge order in each description. Fix CI failures at their root; never skip, disable or weaken a test.

Operator decisions: don't wait; ship safe defaults; put draft wording (auto-recharge consent) in one marked constant; list open decisions in the report.

Stop and ask if an API cannot support a screen without a backend change, a brief contradicts main in a way that changes a contract, or a CI failure persists after you've fixed its root cause once.

Finished when all five PRs are open with green CI and you've posted a report: per workstream its branch, PR link, test counts before/after, evidence, anything undone and why; then the merge order and open decisions.
```
