# Round 6 — Opening prompt (paste as the first message)

```
You're a Claude Code session working on Godance round-6 brief 01 —
Dependabot / dependency triage: clear the safe alerts, evaluate the
major-version ones behind a build+test gate, defer (don't half-do) the
risky migrations.

Setup: working dir is a dedicated git worktree on branch
feature/dependency-triage, off main at commit 2be06f8. Confirm the baseline
with a full test run before changing anything.

Read these before touching any dependency:
1. claude-code-briefs/round-6/01-dependency-triage.md (the brief — it has
   the captured npm-audit fix data, so you start from facts)
2. claude-code-briefs/round-6/00-coordination.md
3. GitHub → repo → Security → Dependabot (authoritative list; the ONLY
   place the backend/Maven alerts appear)
4. app/package.json, marketing/package.json, backend/pom.xml

Hard rules:
- Commit per logical step to feature/dependency-triage. `git merge --no-ff -m`.
- Do NOT `git push`. Do NOT deploy.
- Before backend tests: `cd backend && docker compose up -d`.
- Use `npm audit fix` (NEVER `--force`). Majors get attempted on a scratch
  commit and either verified-and-kept or reverted-and-documented — no
  half-migrations. Astro 5→7 is almost certainly a future workstream, not
  this one.
- Both frontends must `npm ci && npm run build` clean; backend suite green
  (283 baseline; the StabilitySdxlProviderTest ~5s timeout is a known load
  flake — re-run it alone before treating it as a regression).

Opening move: run `npm audit` in app/ and marketing/ and read the Dependabot
tab for the Maven list; post a 3–5 bullet plan (what's safe, what's major,
what you'll defer) before changing anything.

Done = safe bumps landed, majors decided (upgraded+verified or
deferred+documented in docs/dependency-triage-2026-09.md), builds+tests
green, all commits on the branch, main untouched. Hand back.
```
