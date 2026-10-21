# Godance — Parallel Claude Code, Round 2 (Coordination)

Engineering conventions are unchanged from `../00-COORDINATION.md` — **read that first** (ModelInfo/microcents, reactive Mono/Flux, RFC 7807, WireMock cassettes, regional allowlist, secrets via env placeholders, OSS hygiene). This file covers the second batch and fixes the isolation mistake from round 1.

## Hard rule learned from round 1 — TRUE ISOLATION (non-negotiable)

Round 1 failed isolation: all instances shared one working directory and committed nothing, so every workstream's changes intermingled, uncommitted, on a single branch. Do not repeat this.

- Each instance runs in its **own git worktree**: `git worktree add ../gd-<name> -b feature/<name> origin/main`.
- **Commit to your own branch as you go.** Leaving work uncommitted at the end is a defect, not a handoff.
- Never point two instances at the same checkout.
- Use `git merge --no-ff -m "…"` (with `-m`) when integrating — never rely on an editor opening.

## Instances & branches

| Brief | Branch | Tasks | Migration |
|---|---|---|---|
| 01 auth-security-limits | `feature/auth-security` | #7 OAuth (Google/Apple/X) + #5 API-key IP allowlist & spending caps | **V9** |
| 02 status-observability | `feature/observability` | #6 status/health instrumentation + #17 Quartz-in-tests polish | **V10** |
| 03 billing-webhook-verify | `feature/billing-verify` | #9 Stripe webhook + auto-recharge prod verification | none |
| 04 frontend-consolidation | `feature/frontend` | #16 model picker, pricing/docs, USDT top-up, status UI, OAuth buttons | none |

**Merge order: 01 → 02 → 03 → 04.** Frontend merges last because it consumes the new endpoints (status fields, OAuth routes).

## Migrations

Latest on `main` is **V8**. **V9 = auth-security** (oauth identities + `api_keys.ip_allowlist`). **V10 = observability** (`usage_records.latency_ms` + `outcome`). No other instance adds a migration.

## Shared-file watch

- `config/SecurityConfig.java` + auth/api-key files → **auth-security only**.
- `usage_records` schema + gateway dispatch timing → **observability only**.
- `application.yml` → auth adds the `spring.security.oauth2` + OAuth client blocks (additive/namespaced).
- All frontend files → **frontend only** (and it merges last).
- The Quartz-in-tests change (#17) edits the **test profile**, which every instance's tests use — observability should land it early on its branch; it only helps the others.

## Definition of done (per instance)

`mvn -q test` green on your branch, **work committed on your branch**, no edits outside your declared file set, secrets as `${ENV_VAR:dev-default}` placeholders only. Operator-supplied credentials (OAuth client IDs/secrets) documented in your PR body, never committed.
