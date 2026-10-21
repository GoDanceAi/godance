# Round 6 — Coordination

**Date opened:** 2026-09-03 · **Parent:** `main` @ `2be06f8`
(OAuth hardening + git_sha, deployed & verified live)
**Workstream:** 01 Dependency / Dependabot triage (single agent)

Context: GitHub reports **40 Dependabot alerts** (18 high, 16 moderate, 6 low)
on the private repo. This round clears the safe ones and evaluates the
major-version ones behind a build+test gate. Fix data below was captured
from `npm audit` on 2026-09-03, so the agent starts from facts, not a scan
from scratch.

## Worktree

```bash
cd godance-oss
git worktree add ../gd-deps -b feature/dependency-triage main
```

## Hard rules

- Commit in logical chunks to `feature/dependency-triage`; `git merge --no-ff -m`.
- **No `git push`. No deploy.** Operator merges after review.
- Before backend tests: `cd backend && docker compose up -d` (MinIO+Redis+PG).
- Baseline: `mvn clean test` green on `main` @ `2be06f8` — **283 tests**
  (note: `StabilitySdxlProviderTest.generate_decodesArtifactBase64_intoBytes`
  is a known ~5s blocking-read flake under load; re-run in isolation before
  calling it a regression).
- Frontends: `npm ci && npm run build` must stay green in BOTH `app/` and
  `marketing/`.
- Secrets: none involved; still run the pre-push sweep out of habit.

## Scope boundaries

Touches `app/`, `marketing/`, `backend/pom.xml` and lockfiles only. Do NOT
touch application source logic, `backend/deploy/`, `.github/workflows/`, or
`routing/`. No functional/behavioral changes — dependency versions + any
import-path adjustments a bump strictly requires.
