# Routing Orchestrator (Phase 1, steps 1–3): Completion Report

**Status:** Ready to merge into `main`
**Branch:** `feature/routing-orchestrator` (3 build commits from 2026-06-24 + merge-prep)
**Brief:** [`routing-orchestrator-brief.md`](./routing-orchestrator-brief.md)

---

## What was delivered

| Step | Deliverable | Files |
|---|---|---|
| 1 | `ai.godance.routing` package: records, first-match-wins `RuleBasedRoutingEngine`, `HeuristicTaskClassifier`, `RoutingPresets`, `RoutingPolicyRegistry`, `RouteResolver` | `backend/src/main/java/ai/godance/routing/` |
| 2 | `RouteResolver` wired into `ChatCompletionService.execute()` / `executeStreaming()`; estimate + pre-debit against the first concrete candidate; one INFO `Routing decision: requestId=… virtualId=… rule=… candidates=…` line per routed call | `service/ChatCompletionService.java` |
| 3 | `godance-fast` / `godance-auto` / `godance-quality` advertised in `GET /v1/models` after the unchanged catalog (`owned_by: "godance"`, `godance: {virtual: true, modality}`) | `web/ModelsController.java` |

### Preset bindings

All targets are `ACTIVE` rows in the V11 `model_catalog` seed.

- **`godance-fast`**: `deepseek-v4-flash` → `glm-4.7-flash`, default `deepseek-v4-flash`. This is the cheapest blended input+output price (140/280 microcents per 1k). Both models are China-hosted, so the cheap tier never trips the Alicloud allowlist.
- **`godance-auto`**: rules are checked in order and the first match wins.
  - `hard-task` (CODE / REASONING / LONG_CONTEXT): `gpt-4o` → `claude-sonnet-4-6` → `deepseek-v4-pro`
  - `short-chat` (≤ 1500 estimated input tokens): `deepseek-v4-flash` → `glm-4.7-flash`
  - `default-mid`: `qwen-3.6` → `deepseek-v4-flash`
  - Default model: `deepseek-v4-flash`.
- **`godance-quality`**: `gpt-5.5` → `claude-opus-4-7` → `deepseek-v4-pro`, default `gpt-5.5`.

No existing `/v1/models` test needed changing. None of them asserts an exact `data[]` length.

---

## Merge prep (2026-10-21)

`main` (27 commits: rounds 5–6, Spring Boot 3.4.13, Testcontainers 2, the
MinIO GHCR mirror) was merged in with `git merge --no-ff` (`38d1ca7`).
There were no conflicts. Three bugs were then fixed:

1. **Virtual `/v1/models` entries have no `pricing` block.** The app's
   `ChatPage.textPrice()` read `m.godance.pricing.input_microcents_per_1k`
   unguarded. It threw inside the `/v1/models` handler, so the chat presets
   silently stayed on `FALLBACK_PRESETS`. The marketing catalog would have
   listed the virtual ids with blank prices.
   **Fix:** both consumers now skip `godance.virtual === true` entries. The
   backend response is unchanged. The app's `ModelListResponse` type is now a
   `ModelEntry | VirtualModelEntry` union with an `isVirtualModel()` guard,
   so any future consumer gets a type error if it skips the check. A
   `pricing` block was rejected: a routed call is billed at the concrete
   model that serves it, so any single price would be wrong. For example,
   `godance-auto` costs `deepseek-v4-flash` rates for chat but `gpt-4o` rates
   for code.
2. **`fallbackUsed` was true for every routed attempt.** `execute()`
   compared each attempt to `request.model()`, which is the virtual id and
   never an attempt. It now compares to the first resolved candidate. For
   direct calls that is still `request.model()`, so direct calls are
   unchanged.
3. **A fully deprecated preset returned a 500.** `ModelCatalog.find()` only
   sees `ACTIVE` rows. If an admin deprecated every target of a preset,
   including its default, `RuleBasedRoutingEngine` threw
   `IllegalStateException`. It now logs a WARN and throws a 503
   `ResponseStatusException`, which renders as `application/problem+json`
   with detail `model <id> is temporarily unavailable`. The throw happens
   before pre-debit, so nothing is charged.

New and changed tests:

- `RuleBasedRoutingEngineTest.resolve_givenNoTargetOrDefaultInCatalog_throwsServiceUnavailable`
  replaces the old `IllegalStateException` test.
- Two new tests in `RoutingChatCompletionIntegrationTest`:
  `…_virtualFast_givenAllTargetsDeprecated_returns503Problem` and
  `…_virtualFast_streaming_givenAllTargetsDeprecated_returns503Problem`.
- New `fallbackUsed` assertions: `false` when the routed first choice serves,
  and `true` when the region test falls through to `deepseek-v4-pro`.

---

## Known limitation: streaming has no fall-through

`executeStreaming()` dispatches only `attemptModels.get(0)`. There is no
attempt loop, no `reorderByHealth()`, and the static fallback chain is never
used. A region-forbidden first candidate returns `dispatch()`'s 503, and an
upstream failure errors the stream. Both get a full refund, but the request
is not retried on the next candidate the way `execute()` retries it.

Direct streaming calls already behaved this way before routing. Virtual ids
hit it more often because they depend on their candidate lists. Under
`region=alicloud`, a streamed `godance-quality` call (and a streamed
`godance-auto` code, reasoning or long-context call) leads with an OpenAI
model and gets a 503. The same request without streaming is served by
`deepseek-v4-pro`.

This is not fixed here. A fix needs a fall-through that is safe before the
first byte is sent, and that is a separate change. The limitation is also
documented in the `executeStreaming()` Javadoc.

---

### Related: error body for SSE-only clients (not changed)

Every error raised before the stream starts (402, 404, and now this 503)
keeps the right status code. A client that sends `Accept: application/json`
(the OpenAI SDKs, for example) gets an `application/problem+json` body. A
client that accepts only `text/event-stream` (the app's `streamChat`) gets
the same problem JSON wrapped in an SSE `data:` frame instead. The app
checks only the status code, so it is not affected.

---

## Verification

- `cd backend && docker compose up -d && mvn clean test`: **313 tests, 0
  failures**. The merge commit alone was 311 (289 + 22). The engine test
  was replaced one for one, and the two 503 integration tests are new.
- The new assertions were run against the unfixed merge commit first. They
  failed as expected: `fallbackUsed` was true on the first-choice serve, and
  both 503 tests got `500 INTERNAL_SERVER_ERROR`. With the fixes they pass.
- `cd app && npm ci && npm run build`: green (`tsc -b` + Vite).
- `cd marketing && npm ci && npm run build` against a live local backend:
  green. `/models` and `/pricing` list the live catalog with no `godance-*`
  rows. A control build with the old `catalog.ts` rendered
  `godance-auto · godance · Text · — per 1M · — per 1M` on `/pricing`.
- The MinIO image (`ghcr.io/godanceai/minio`) is a private GHCR mirror. If
  you can't pull it, build MinIO `RELEASE.2024-12-13T22-19-12Z` from source
  and tag it locally as both `:RELEASE.2024-12-13T22-19-12Z` (Testcontainers)
  and `:mirror-2026-10-04` (`docker compose`).
