# Opening prompt — Claude Code session for task #20

Copy-paste everything below into the new Claude Code session as the first message.

---

You're a Claude Code session working on Godance task #20 — **adding a rules-based routing orchestrator: a single OpenAI-compatible endpoint where the caller asks for a *virtual model* (`godance-fast` / `godance-auto` / `godance-quality`) and we resolve it to the best/cheapest concrete model, capturing the margin spread.** This is Phase 1, steps 1–3 only.

## Your setup

- Working directory: a dedicated git worktree at `~/1-Production/7.5-Token-Resale/Prod 7.5 Token Resale/gd-routing` (or wherever the operator created it).
- Branch: `feature/routing-orchestrator`, off `godance-oss` `main`. **Confirm the baseline `mvn clean test` is green and note the count before writing any code.**
- This is a single-instance workstream; the operator is not running other instances in parallel against this repo.

## Read these before writing any code, in this order

1. `../../Godance-RoutingEngine-Design-2026-06-24.md` — the concrete design (exact records, engine logic, and §11 invariants). **Read it fully.** This is your spec.
2. `../../Godance-Routing-Roadmap-2026-06-24.md` §"Phase 1" — the gate you're satisfying and what's deferred.
3. `claude-code-briefs/round-4/routing-orchestrator-brief.md` — your task brief. **Read it fully**; it's the build order.
4. `claude-code-briefs/00-COORDINATION.md` and `claude-code-briefs/round-2/00-COORDINATION.md` — engineering conventions (ModelInfo / microcents / Provider enum / regional allowlist / WireMock cassettes / RFC 7807 / reactive end-to-end) and the isolation lesson.
5. In the backend repo (`backend/src/main/java/ai/godance/`):
   - `service/ChatCompletionService.java` — `execute()` + `executeStreaming()`; the **one** request-path file you modify. Study how `attemptModels` is built, pre-debit/estimate/`reconcile`, and `reorderByHealth()`.
   - `gateway/ModelCatalog.java` (`ModelInfo`, `find()`, `all()`), `gateway/ProviderGateway.java` (`dispatch()` — the region chokepoint), `config/GatewayProperties.java` (`fallbacksFor()`).
   - `web/ModelsController.java` — the `/v1/models` shape you extend.
   - `gateway/ChatDtos.java` — `ChatRequest` / `ChatMessage`.

## Before every `mvn clean test`, bring up the dev stack

```
cd backend
docker compose up -d        # postgres :5433, redis :6380, minio :9000
docker compose ps           # confirm all three healthy
```

Without it, ~80 `@SpringBootTest` tests fail with `Connect to localhost:9000 refused` (MinIO `headBucket` at startup). If `docker` isn't on PATH: `export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"`.

## Hard rules — non-negotiable

- **Routing only proposes candidates.** `ProviderGateway.dispatch()` stays the **single** region-allowlist chokepoint; `reorderByHealth()` still runs after routing; pre-debit/reconcile is unchanged. **Never** pre-filter on region or call a provider directly from the routing layer. (Design §11.)
- **Direct (non-virtual) model calls must stay byte-for-byte identical.** Lock it with a regression test.
- **Resolve the route before the cost estimate**, then estimate/pre-debit on the **first concrete candidate** — a virtual id isn't in the catalog and would 404 the old way.
- **Phase 1 scope only.** No `routed_policy`/`routed_rule` columns (Phase 2), no user-policy API or DB table (Phase 3), no eval harness/ensemble/credits (Phase 4–5), no adapter/billing/frontend/`Provider`-enum changes. For margin attribution this phase, log one INFO line keyed by `requestId` (`requestId → virtualId → candidates`) — that joins to the existing `UsageRecord` for the spread.
- **Bind preset model names to ids that actually exist as `ACTIVE` rows** in the catalog (verify against `GET /v1/models`); the names in the design doc are placeholders. Point the cheap tier at the cheapest existing catalog model — do not self-host anything (that's Phase 1b).
- **Commit to `feature/routing-orchestrator` as you go.** Leaving work uncommitted is a defect. Use `git merge --no-ff -m "…"` (with `-m`) anywhere you integrate — never rely on an editor opening.
- **Do NOT `git push`. Do NOT deploy.** The operator handles both, later.

## Done means

`mvn clean test` green on `feature/routing-orchestrator`; `GET /v1/models` lists the three virtual ids plus the unchanged catalog; streaming + non-streaming calls to `godance-auto` return OpenAI-compatible responses from a concrete model with the routing decision logged; a region-forbidden top candidate falls through `dispatch()` rather than leaking past the allowlist; direct-call regression green. All commits on the branch, **no push, no deploy.** Hand back with a one-paragraph summary of the preset model bindings you chose and any `/v1/models` test you had to adjust.
