# Round 15 — Prompt for a new Codex session

Paste the block below into a new Codex session opened on the project. The planning pack is published on `codex/round-15-briefs`; implementation branches still start from verified `origin/main`, not the planning branch. Keep any shared root checkout intact.

```text
Implement Godance Round 15: navigation and existing-feature completion. Work until all five workstreams meet their briefs and five PRs are open with green required latest-head CI, or report concrete unresolved blockers honestly. Do not implement beyond the briefs to manufacture completion.

Start in the existing GoDance project checkout. Set REPO from git rev-parse --show-toplevel and verify its resolved location is on /mnt/disco-grande before creating any worktree.

Read before code:
- Applicable root/scoped project instruction files.
- docs/claude-code-briefs/round-15/00-coordination.md in full: feature availability, money/chat rules, ownership, default-site parity and Git boundaries are binding.
- docs/claude-code-briefs/round-15/session-prompts.md.
- Each owned workstream brief in full before starting it.
- backend/CLAUDE.md; verify current source where historical summaries differ.

First inspect staged, unstaged, untracked work and existing worktrees. Preserve them, the current root branch, the private handoff and all ignored backups. Do not reset/clean/switch the root checkout to start. Fetch origin without pruning, verify rounds 7–14 are merged, and pin the full origin/main SHA as B0. The design was checked at 8219e0497cb12a7be48b80915601811e361726bf; inspect later changes instead of assuming it is still current.

The authoritative Round 15 pack is docs/claude-code-briefs/round-15/ on origin/codex/round-15-briefs. If these files are absent from this checkout or B0, read them with git show origin/codex/round-15-briefs:docs/claude-code-briefs/round-15/<filename>. You may copy the eight planning files into an ignored .claude/round-15-evidence/planning/ snapshot for reading; record the planning commit SHA. Read-only copies in the original root may also be used after comparison to that ref. Do not switch the shared root or base implementation on the planning branch merely to obtain the briefs. Do not publish duplicate brief copies on the five implementation branches.

Create or carefully reuse one dedicated worktree per stream under the project's ignored .claude/worktrees/r15-* directories. All must physically remain on /mnt/disco-grande. ALL FIVE branches start at B0 and ALL FIVE PRs target main:
1. 05-admin-features.md -> codex/r15-admin-features
2. 04-site-links.md -> codex/r15-site-links
3. 01-app-links.md -> codex/r15-app-links
4. 02-billing-screens.md -> codex/r15-billing-screens
5. 03-chat-history.md -> codex/r15-chat-history

02 and 03 use existing routes/shell and do not edit shared navigation; no stacked PR bases. You may delegate bounded streams to subagents in their own worktrees. Keep each file's sole owner explicit, use feature-specific API/mock helpers, and coordinate any necessary shared hunk before editing it.

Capture one auditable common B0 baseline per surface, with commands, environment, counts and failures; share that evidence rather than rerunning every full baseline five times. Follow each stream's opening move and post its 3–5 bullet plan before code. Commit reviewable steps using 'area: summary'.

Non-negotiable scope:
- Only new backend behavior: 05's read-only ADMIN-only GET /v1/admin/features, fixed redacted DTO/auth/tests/docs. No other backend behavior, migration, default change, runtime switch mutation or provider probe.
- Admin feature status reports configuration, not user eligibility/provider health/site availability. No toggle controls. Customer navigation must reuse existing per-user discovery, never this admin endpoint.
- Links lead to existing permitted pages or legitimate sign-in/onboarding/empty states. Preserve account, age, terms, role, model, content and feature gates; discard stale cross-account probes.
- Reuse current Studio/recipes/batches/MCP/BYOK/community controls. Do not invent routing/editor, receipt, card-removal, complete usage-history, conversation rename/restore/hard-delete or idempotency APIs.
- Integer cents only for money arithmetic. Explicit unchecked card-save consent. Enabling/updating enabled auto-recharge needs a review/confirmation stating threshold and recharge amount and off-session meaning. Keep draft wording in one marked source constant. No financial write from render/navigation/effects/URL parameters/automatic retry.
- Drop providerCostCents through a frontend field allowlist before view/export state; never render it. Usage summaries mean recent loaded rows, not complete month/lifetime totals.
- New chat is ephemeral until an ADULT explicitly saves that conversation. Children keep ephemeral chat with no history calls, preserving the parental notice. Save failure never removes an answer or repeats paid generation. Serialize writes; no automatic create/append retry or exactly-once promise after a lost response. No transcripts in browser storage, logs or URLs.
- PUBLIC_SITE_DISCOVERY_ENABLED defaults false and gates the new marketing shell/home/create/integrations pages. Reuse gallery/community content gates. The complete default output path set and legacy bytes stay unchanged except the brief's named docs outputs; prove parity with deterministic inputs, including CSS/JS/assets. Do not publish test fixtures or set production build/backend flags.
- No real secrets, usable keys, private identities/allowlist data, handoff contents or backup files in public commits, PR descriptions, evidence or logs. Use synthetic test accounts/screenshots and keep raw evidence ignored.
- Explain and justify any new runtime dependency before adding it; prefer existing/native tools.

Checks:
Backend: start/check backing services once with 'cd backend && docker compose up -d'; run 'mvn -B verify'. Serialize integration/full runs because compose ports/names are shared. If Docker is unavailable, record the exact limit, continue independent checks, and require real PR CI before calling backend green.
App: 'npm ci', 'npm run lint', 'npm run build', 'npm run test:e2e'. Give worktrees unique E2E_PORT values. 01 runs full E2E after shell changes; others run focused owned flows locally; final full PR CI remains mandatory.
Marketing: 'npm ci', 'npm run lint', 'npm run build', 'npm test', plus default/enabled output and internal-link checks. npm test is a required local check even if CI does not run it.
Evidence: visible/hidden states, desktop/mobile, keyboard/focus, sign-in return paths and representative light/dark screenshots. Record base/head SHA and test counts. Never use real card/provider operations to validate these screens.

Push only the five codex feature branches and open one PR per stream against main. Never push main, merge, enable auto-merge, bypass required checks or deploy. Include scope, B0/head SHA, baseline/test evidence, safe screenshots, limitations and eventual integration order in every PR description. The final PR CI run must pass backend (mvn verify), app (build), app (e2e), and marketing (build), plus every additional required check, on the latest head; queued/running/skipped/failed is not green. Fix root causes without skipping, disabling or weakening tests/audits. Continue independent work while investigating a blocker.

Use safe defaults for bounded choices and document operator decisions. Pause only the dependent requirement if it needs a backend/contract/legal-scope expansion, a secret/activation decision, or an unavailable external prerequisite. Do not pause the whole round merely because wording or layout needs judgment, and do not perform unauthorized production changes to get a green result.

Finish with a report listing all five branches, PR URLs, verified head SHAs/CI links, parent and final test counts, acceptance/evidence, anything unmet and why, plus operator decisions and eventual integration order (05/04, then 01, then 02/03). Main stays untouched. If a required criterion or check is unresolved, state partial completion; do not claim the round is finished.
```
