# Brief 01 — OAuth Sign-in + API-Key Security & Spending Limits

**Branch:** `feature/auth-security` · **Migration: V9** · Read `../00-COORDINATION.md` (round 1) and `./00-COORDINATION.md` (round 2) first. Work in your own worktree; commit as you go.

## Mission

Add social sign-in alongside the existing email/password flow, and harden API-key security and spending controls (outcome-brief Features #5 and #8).

## Scope

**OAuth2 sign-in (#7):** add Google, Apple, and X.com via Spring Security OAuth2 Client, alongside — not replacing — email/password. After a successful OAuth login, mint the same JWT the email flow issues. Migration **V9** adds `oauth_identities(id, user_id FK, provider, subject, created_at, UNIQUE(provider, subject))`. On login, link to an existing user by verified email, else create one. Email/password and existing JWTs must keep working unchanged.

**API-key IP allowlist (#5):** optional per-key CIDR allowlist — **V9** adds `api_keys.ip_allowlist` (text/array). Enforce in the API-key auth filter; reject with 403 when the client IP is outside the allowlist. Use the repo's existing null-safe `clientIp()` helper (it handles `X-Forwarded-For` behind Caddy) — do not reintroduce the NPE that was fixed in prod.

**Spending limits (#5):** per-key and per-user daily + monthly caps — soft (warn email at 80%) and hard (return 402 at 100%), resetting each period. Check what already exists from outcome-brief Feature #5 and fill the gaps rather than duplicating. Wire to the existing credit/usage path; do not rewrite `CreditLedgerService` core.

## Operator inputs required (document in PR body, supply via env)

OAuth client id + secret + redirect URI for each of Google, Apple (Services ID + signing key → client-secret JWT), and X.com. Reference them as `${GOOGLE_OAUTH_CLIENT_ID:...}` etc. with dev placeholders; real values go in `.env.prod`.

## Tests

OAuth login via a mocked provider issues a valid JWT and creates/links the user; IP allowlist allows on-list and 403s off-list; soft cap fires the warning, hard cap returns 402 and resets next period. `mvn -q test` green on branch.

## Out of scope

Frontend login buttons (→ brief 04), other modalities, payment rails. No `@Data` on entities; records for DTOs.
