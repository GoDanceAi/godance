# Round 14 — Coordination

**Drafted:** 2026-11-13 · **Parent:** `main` with round 13 merged
**Workstreams:**
- 01 Published recipes, profiles and follows (backend)
- 02 Community pages (app, marketing)
- 03 Bring your own key (backend, app)
- 04 Routing policies: round 4's Phases 2 and 3 (backend)
- 05 Self-hosting guide (docs, packaging)

Context: Godance is built for aspiring builders, and it serves everyone.
Rounds 10–13 built what the target needs. This round adds what consumers
and experts ask for, each on top of something those rounds made. See
`docs/research/2026-11-prompt-gallery-competitors.md` §5.12.

- **Consumers, and learners.** Users publish their own recipes; others see
  them, run them, follow their authors, and see what a recipe was made from
  and how often it is run. This builds on round 10's recipes, round 11's
  inputs, and the gallery's content standard.
- **Experts:**
  - bring-your-own-key, built on the gateway, the ledger and per-key caps
  - user routing policies, the next phases of round 4's design, which also
    give batches fallbacks
  - a supported way to self-host the Apache 2.0 repository

**Revisit before starting.** This round was drafted before rounds 10–13 were
built. Before opening it, check each brief's "Read first" list against what
those rounds actually produced, and adjust names and paths.

**Prerequisites.** Round 13 on `main`.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-publish   -b feature/published-recipes main
git worktree add ../gd-community -b feature/community-pages main
git worktree add ../gd-byok      -b feature/byok main
git worktree add ../gd-routing   -b feature/routing-policies main
git worktree add ../gd-selfhost  -b feature/self-hosting main
```

## Reserved resources

| Resource | 01 publish | 02 community | 03 BYOK | 04 routing | 05 self-host |
|---|---|---|---|---|---|
| Flyway | **V39** | none | **V40** | **V41** | none |
| Backend | new `service/Publish*`, `service/Moderation*`, `service/Profile*`, `web/Public*`, admin moderation routes | none | new `service/ProviderCredential*`, a credential seam in `gateway/`, adapters (one by one), `CostCalculator` (BYOK fee) | `routing/`, `domain/UsageRecord` (two columns), `service/ChatCompletionService` (the hint), batch fallback in `service/Batch*` | none |
| App | none | new public, profile, following and moderation pages; recipe page (Publish) | Settings (provider keys) | none | none |
| Marketing | none | `src/pages/community/**`, `src/pages/u/**`, `src/components/community/**`, `src/lib/community.ts`; `.github/workflows/deploy-frontends.yml` (a daily schedule) | none | none | none |
| Docs | `docs/gallery-content-standard.md` (community section) | none | none | none | new `docs/self-hosting.md`, `deploy/self-host/**` |
| Config | `godance.community.*` | none | `godance.byok.*` | `godance.routing.user-policies.*` | none |

Check V39–V41 are still free; rounds 12 and 13 may have used V37 or V38.

## Shared contract

### Publishing and profiles (01 builds; 02 renders)

```
PUT    /v1/profile        { "handle": "mina", "display_name": "Mina", "link": "https://…" }   (session)
POST   /v1/recipes/{id}/publish   { "version": 3, "licence": "CC-BY-4.0", "job_ids": ["…"] }  → 202 (pending review)
DELETE /v1/recipes/{id}/publish   → 204 (unpublish)
POST   /v1/follows/{handle}  · DELETE /v1/follows/{handle}  · GET /v1/follows/feed?limit=&before=
POST   /v1/reports   { "kind": "published_recipe", "id": "…", "reason": "…" }

GET /v1/public/recipes?limit=&before=&tag=     (no auth)
GET /v1/public/recipes/{public_id}             (no auth)
GET /v1/public/profiles/{handle}               (no auth)
```

- **What a published recipe exposes:**
  - title
  - the chosen version's template, variables and input slots, with no
    user files
  - model and params
  - the chosen jobs' outputs, as copies made public on approval
  - `made_from`: a gallery slug or another published recipe
  - counts: `runs`, `runners`, `followers` of the author
  - the licence
  - the author's handle
- **Moderation.** Publishing is pre-moderated: nothing is public until a
  moderator approves it. A report re-opens review.
- **Accounts.** Only `ADULT` accounts with a verified email may set a
  profile or publish.
- **Running a published recipe.** Generation requests accept
  `"recipe": { "published_id": "…", "variables": {…} }`. Jobs carry
  `published_recipe` (its public id) in their origin, round 10's record.

### Bring your own key (03)

```
PUT    /v1/provider-credentials/{provider}   { "api_key": "…", "fallback_to_platform": false }  (session)
GET    /v1/provider-credentials              → [{ "provider": "OPENAI", "last4": "…", "status": "valid", "fallback_to_platform": false }]
DELETE /v1/provider-credentials/{provider}
```

- A request to a model whose provider has the user's credential goes out
  with the user's key.
- The ledger charges the BYOK fee only.
- **The regional allowlist still decides.** A credential never makes a
  forbidden provider reachable.

### Routing policies (04)

The routes and the policy shape are as round 4's design document defines
them for Phase 3: `/v1/routing/policies` and the `X-Godance-Route` hint.
The one addition here: a policy may carry `fallback` rules for image and
video jobs, used only by batches that opt in with `"fallbacks": true`.

## Shared-file conflict watch

- **Round 10's origin record:** 01 adds `published_recipe`.
- **`config/SecurityConfig.java`:** 01 adds the public routes (`permitAll`
  on `/v1/public/**` only); 03 and 04 add session or key rules.
- **Usage and cost:** `domain/UsageRecord.java` (03 adds a BYOK marker; 04
  adds `routed_policy` and `routed_rule`) and `gateway/CostCalculator.java`
  (03).
- **`service/Batch*`:** 04 only.
- **`app/src/lib/api.ts`, `app/e2e/mock-api.ts`:** 02 and 03.

## Hard rules (all sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- Run the full suite on the parent commit and record the count first.
- `app/` and `marketing/` checks stay green.
- **No plaintext secrets at rest** (`backend/CLAUDE.md` hard constraint 2).
  Provider credentials go through `KeyVault`.
- **Single-tenant** (hard constraint 3): profiles are users, with no
  organisations or teams.
- **The regional allowlist stays the single chokepoint** in
  `ProviderGateway.dispatch()`. Nothing in this round pre-filters around it
  or routes past it.
- **Nothing user-made is public without moderation.**
- Billing changes carry a test in the same change. Money in integer cents.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

1. **01** before **02**. 02 develops against mocks.
2. **03, 04 and 05** at any time, in any order.

## Operator decisions

- **Community:**
  - pre-moderation (the default here) or post-moderation once volume grows
  - who moderates: the `SUPPORT` role by default
  - the licences a user may choose for published prompts and outputs
  - whether community recipes may use input slots with people, which
    follows round 11's stages
- **Creator rewards** (research §5.11) are not in this round. They wait for
  round 9's referral results and for handling of card disputes.
- **BYOK:**
  - the fee: `godance.byok.fee-basis-points` applied to the list price,
    suggested 500 (5%)
  - which providers to support first
  - whether each provider's terms allow keys to be used through a third
    party; check before enabling each provider
- **Routing:**
  - whether user policies are open to everyone or to accounts that ask
  - the compliance opt-out wording from round 4's design
- **Self-hosting:**
  - the support offered (none, community, paid)
  - the trademark policy for the name "Godance" in self-hosted copies
  - whether a self-hosted copy may keep the name
