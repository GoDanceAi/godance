# Brief — Routing Orchestrator (Phase 1, steps 1–3): virtual-model endpoint

**Task #20** · **Branch:** `feature/routing-orchestrator` (off `godance-oss` `main`) · Single workstream — own your worktree and **commit to your branch as you go**.

## Read first

In this order, before writing any code:

1. **This brief.**
2. `../../Godance-RoutingEngine-Design-2026-06-24.md` — the concrete design this brief implements. **Read it fully**; it has the exact records, the engine logic, and §11 "invariants." This brief is the build order; that doc is the spec.
3. `../../Godance-Routing-Roadmap-2026-06-24.md` §"Phase 1" — the gate you're satisfying and what's deliberately deferred.
4. `../00-COORDINATION.md` and `../round-2/00-COORDINATION.md` — engineering conventions (ModelInfo / microcents / Provider enum / regional allowlist / WireMock cassettes / RFC 7807 / reactive end-to-end) + the isolation lesson (commit to your branch; `git merge --no-ff -m "…"`; do **not** push or deploy).
5. In the backend repo (`backend/src/main/java/ai/godance/`):
   - `service/ChatCompletionService.java` — `execute()` and `executeStreaming()`; the **one** file you modify in the request path. Study how `attemptModels` is built, how pre-debit/estimate/`reconcile` work, and `reorderByHealth()`.
   - `gateway/ModelCatalog.java` (`ModelInfo`, `find()`, `all()`), `gateway/ProviderGateway.java` (`dispatch()` — the region chokepoint), `config/GatewayProperties.java` (`fallbacksFor()`).
   - `web/ModelsController.java` — the `/v1/models` shape you extend.
   - `gateway/ChatDtos.java` — `ChatRequest`/`ChatMessage`.

## Local prerequisites (every session, before `mvn test`)

`@SpringBootTest` boots the full context, which `headBucket`s MinIO at startup — without the dev stack ~80 tests fail with `Connect to localhost:9000 refused`.

```bash
cd backend
docker compose up -d            # postgres :5433, redis :6380, minio :9000
docker compose ps               # confirm all three healthy
mvn clean test                  # baseline must be green BEFORE you start — note the count
```

If `docker` isn't on PATH: `export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"`.

## Why this build exists

Today Godance is pass-through resale — the caller names the model, we charge ~5% over cost. This adds a **rules-based orchestrator**: the caller asks for a *virtual model* (`godance-fast` / `godance-auto` / `godance-quality`), and we resolve it to the best/cheapest concrete model for the job, capturing the spread between what they pay for the virtual id and what the served model actually cost. This is the first rung up the value ladder. It is **deterministic rules**, not a learned router — that's intentional; the rules aren't the proprietary part.

## Decisions already made (build to these, don't relitigate)

- **Rules-based, first-match-wins** policy evaluation. No ML, no learned router this phase.
- **Routing only proposes candidates.** Everything that protects us today stays underneath, untouched: `ProviderGateway.dispatch()` remains the **single** region-allowlist chokepoint, `reorderByHealth()` still runs after routing, pre-debit/reconcile billing is unchanged. Routing must **never** pre-filter on region or call a provider directly.
- **Presets are Spring beans** this phase (`RoutingPresets`). User-authored policies, the DB table, and the policy API are **Phase 3 — out of scope here.**
- Catalog is DB-backed (the `model_catalog` table from task #19) but you read it **only** through the existing `ModelCatalog.find()` / `.all()` Java API. Do not query the DB directly; do not touch any adapter.

## What to build

### Step 1 — `ai.godance.routing` package (types + engine + presets)

Create the package exactly as specified in design doc §3–5. Records: `RoutingPolicy`, `RoutingRule`, `RoutingCondition`, `RoutingLimits` (with `DEFAULTS`), `RoutingContext`, `ResolvedRoute`, `RoutingDecision`, and enum `TaskType`. Components:

- **`RuleBasedRoutingEngine implements RoutingEngine`** (design §4.2) — first matching rule wins; fall through to `defaultModel`; `applyLimitsAndValidate` drops unknown/wrong-modality/provider-excluded candidates, dedupes, truncates to `maxCandidates`. Throw `IllegalStateException` only if even the default is invalid (a misconfigured policy, caught by a test — never reachable from user input this phase).
- **`HeuristicTaskClassifier implements TaskClassifier`** (design §4.3) — cheap, deterministic keyword/length heuristic → `TaskType`. Keep it dumb and fast; it's an interface so a learned classifier can replace it later.
- **`RoutingPresets`** (design §5.1) — `godance-fast`, `godance-auto`, `godance-quality`. **Bind the target model names to ids that actually exist as `ACTIVE` rows in `model_catalog`** (verify against `GET /v1/models` on your baseline; the names in the design doc are placeholders). The cheap-tier default should be the cheapest viable catalog model.
- **`RoutingPolicyRegistry`** (design §5) — holds presets in a `ConcurrentHashMap` keyed by virtual id; `find(modelId)` and `all()`. Leave the DB-load line commented with a `// Phase 3` marker.
- **`RouteResolver`** (design §4.1) — the single entry point the service calls. Returns `[request.model()] + gatewayProps.fallbacksFor(request.model())` unchanged when `request.model()` is **not** a registered virtual id (this is what keeps direct calls byte-for-byte identical), and the engine's decision + the chosen primary's static fallback chain when it is.

### Step 2 — wire `RouteResolver` into `ChatCompletionService`

Design doc §2. In **both** `execute()` and `executeStreaming()`:

1. Compute `estimatedInputTokens` first (already a static method).
2. Call `routeResolver.resolve(request, estimatedInputTokens, gateway.region())` → ordered concrete `attemptModels`.
3. **Estimate + pre-debit against the first concrete candidate** (`catalog.find(attemptModels.get(0))`), not `request.model()` — a virtual id is not in the catalog and would 404 the old way.
4. Leave the existing loop, `reorderByHealth(attemptModels)`, `dispatch()`, reconcile, and refund paths **exactly as they are.**

**Margin attribution without a schema change (this phase's exit gate):** at the point you resolve a route, emit one INFO log keyed by `requestId`: `requestId → virtualId → orderedCandidates`. Combined with the existing `UsageRecord` (which already carries `requestId`, `costCents`, `providerCostCents`, and the served `modelName`), that line lets us compute the spread per virtual id by joining on `requestId`. The dedicated `routed_policy`/`routed_rule` columns are **Phase 2 — do not add them here.**

### Step 3 — advertise presets in `/v1/models`

`ModelsController.list()` iterates `registry.all()` alongside `catalog.all()`, emitting an entry per virtual id (id = `godance-auto` etc., `owned_by` = `"godance"`, and a `godance` block marking it `"virtual": true` with the policy's modality). **Existing catalog entries must stay byte-for-byte identical** — the virtual ids are purely additive. (See test-ripple note below.)

## Conventions & guardrails (recap)

Reactive `Mono`/`Flux` end-to-end; integer microcents untouched; RFC 7807 for any error; the regional allowlist and `dispatch()` are untouched; no new `@ConfigurationProperties` needed this phase (presets are beans). A typo'd / unknown model in a preset is **dropped with a warn, never a 500.**

## Tests (target ~12–16 new)

Unit:
- Engine: first-match-wins ordering; catch-all default; `maxCandidates` truncation; unknown model dropped; wrong-modality dropped; `excludedProviders` honored; dedupe.
- Classifier: code block / `def ` / `function ` → `CODE`; long input → `LONG_CONTEXT`; plain → `CHAT`.
- `RouteResolver`: a **direct (real catalog) model** returns `[model] + fallbacksFor(model)` unchanged (lock this — it's the no-regression guarantee); a virtual id returns engine output + the primary's fallback chain appended.

Integration (`@SpringBootTest`, mirror `ChatCompletionIntegrationTest`):
- `model: "godance-auto"` resolves and returns an OpenAI-compatible response served by a concrete catalog model.
- A virtual policy whose top candidate is region-forbidden (e.g. an OpenAI model under `region=ALICLOUD`) **falls through** via `dispatch()`'s 503 to the next candidate — proving routing did not bypass the allowlist.
- **Regression: a direct `model: "<real-catalog-id>"` call is byte-for-byte identical** to baseline behavior (response shape + billing).
- `GET /v1/models` includes `godance-fast/auto/quality` **and** every previously-present catalog entry unchanged.

## Acceptance criteria

`cd backend && docker compose up -d && mvn clean test` green on branch.
- `GET /v1/models` lists the three virtual ids plus the unchanged catalog.
- A chat completion (streaming **and** non-streaming) to `godance-auto` returns an OpenAI-compatible response from a concrete model, with the routing decision logged against the `requestId`.
- A direct model call is provably unchanged (regression test green).
- A region-forbidden top candidate falls through rather than erroring or leaking past the allowlist.

## Out of scope (do not build — later phases)

- `routed_policy` / `routed_rule` columns on `UsageRecord` and the margin dashboard (**Phase 2**).
- User-authored policies, `RoutingPolicyEntity`, the `/v1/routing/policies` API, the validator, `X-Godance-Route` hint wiring, compliance opt-out (**Phase 3**).
- The eval harness (**Phase 4**); ensemble / `godance-ultra` / `RoutingMode`, credit decoupling, learned router/classifier (**Phase 5**).
- Self-hosting a cheap open-weight default model (**Phase 1b**, separate workstream — just point the cheap-tier preset at the cheapest model already in the catalog for now).
- Any billing change, any adapter change, any frontend change, any change to the regional-routing policy or the `Provider` enum.

## Test-ripple heads-up

If any existing `/v1/models` test asserts an **exact list length or full content** of `data[]`, your additive virtual ids will ripple it. Prefer updating that assertion to check the catalog subset is unchanged **and** the three virtual ids are present, rather than weakening it. Don't alter existing per-model assertions.

## When you're done

`mvn clean test` green on `feature/routing-orchestrator`, all commits on that branch (not `main`), **no push, no deploy.** Stop and hand back — the operator merges to `main` locally and decides on push/deploy. Leave a one-paragraph summary of the preset model bindings you chose and any `/v1/models` test you had to adjust.
