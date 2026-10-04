# Round 5 — Coordination

**Date opened:** 2026-07-02 · **Parent:** `main` @ `c9d87b1` (P0 ops round)
**Workstreams:** 01 Admin Panel Phase A · 02 Email Sending (parallel, independent)

## Worktree assignments (one per agent — never share a checkout)

```bash
cd godance-oss
git worktree add ../gd-admin-panel -b feature/admin-panel main
git worktree add ../gd-email       -b feature/email-sending main
```

## Reserved resources

| Resource | 01 admin-panel | 02 email |
|---|---|---|
| Flyway migration numbers | **V12** (if schema needed) | **V13** (expect none — do NOT take V12) |
| Backend packages | `web/Admin*`, `service/admin/` | `service/mail/` |
| Frontend | `app/src/pages/admin/**`, router + nav touch | none |
| Env template vars | none new | `RESEND_API_KEY`, `EMAIL_FROM` |

Shared-file conflict watch: both may touch `.env.prod.template` — 02 owns it
this round; 01 must not edit it. Neither touches `backend/deploy/`,
`marketing/`, or anything in `routing/` (round-4 branch pending merge).

## Hard rules (both sessions)

- Commit to your own branch as you go. `git merge --no-ff -m "..."` only.
- **No `git push`. No deploy.** Operator merges after verification rituals.
- Before `mvn clean test`: `cd backend && docker compose up -d` (MinIO+Redis+PG).
- Baseline: full suite green on `main` @ `c9d87b1` (~234 tests). Your branch
  must end green with your new tests added.
- Secrets only as `${ENV_VAR:visibly_fake_dummy}` placeholders.

## Merge order

Independent — either merges first. After each merge the other rebases/merges
main into its branch before its own merge (same as round 2).
