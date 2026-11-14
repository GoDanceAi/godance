# Brief 04 — Routing policies (round 4's Phases 2 and 3)

**Branch:** `feature/routing-policies` (worktree `../gd-routing`) off `main` with round 13 merged
**Reserved migration:** V41
**Baseline:** full backend suite green on parent; record the count first

## Mission (one line)

Record which policy and rule served each routed request. Then let a user
write their own routing policies through `/v1/routing/policies` and the
`X-Godance-Route` hint, as round 4's design specifies. Add opt-in fallback
rules so a batch survives one provider being down.

## Why now

Round 4 built Phase 1: virtual model ids (`godance-fast`, `godance-auto`,
`godance-quality`) resolved by rules-based presets held as Spring beans. It
deferred:

- **Phase 2:** `routed_policy` and `routed_rule` on usage records, and a
  margin view
- **Phase 3:** user-authored policies, `RoutingPolicyEntity`, the
  `/v1/routing/policies` API, its validator, `X-Godance-Route`, and the
  compliance opt-out

Experienced developers want control over routing. Aspiring builders running
a 500-row batch want it not to fail because one provider is having a bad
hour. Both are served by the same policies.

## Read first

1. This brief and `00-coordination.md` (V41 is yours)
2. `backend/CLAUDE.md`
3. Round 4:
   - `docs/claude-code-briefs/round-4/routing-orchestrator-brief.md`
   - `routing-orchestrator-COMPLETE.md`
   - **the routing design and roadmap documents it names.** They are held by
     the operator, not in this repository. Ask for them before starting.
     They define the Phase 3 policy shape, validator rules, invariants and
     compliance opt-out, and they are the specification for this brief.
4. `routing/` (the engine, presets, registry, rules and conditions),
   `service/ChatCompletionService.java` (`attemptModels`,
   `reorderByHealth`), `gateway/ProviderGateway.java`, `domain/UsageRecord.java`
5. The admin console's usage summary, for the margin view's style
6. Round 12's batch code: how a row's job is created and how a failure is
   recorded

## Scope

### Phase 2 (V41, part one)

- **Columns.** `usage_records.routed_policy` and `routed_rule`, nullable,
  set when a request was routed through a virtual id. Replace round 4's INFO
  log line with these columns, or keep it as well; say which.
- **Margin view.** An admin route, `@AdminRoles({FINANCE, ADMIN})`, giving
  per policy and rule over a date range:
  - requests
  - what callers paid
  - what the served models cost
  - the spread

  Add a small table to the admin Overview page in the app, if the admin
  app's files are free. Otherwise hand the endpoint over in your summary.

### Phase 3 (V41, part two)

- **Build it as the design specifies.** `RoutingPolicyEntity` per user, the
  `/v1/routing/policies` API (session or API key), the validator, the
  `X-Godance-Route` hint, and the compliance opt-out.
- **Who can write policies.** `godance.routing.user-policies.enabled`,
  default false. When true, set `allow-list` to restrict it to listed
  accounts, or leave it empty for everyone.
- **Hold round 4's invariants:**
  - routing only proposes candidates
  - `ProviderGateway.dispatch()` stays the single region chokepoint
  - `reorderByHealth()` still runs after routing
  - billing is unchanged
  - a direct model call is unchanged
  
  Keep round 4's regression tests and add the user-policy cases.

### Fallbacks for media jobs

A policy may include `fallback` rules for image or video models:
- **Shape.** A primary model and an ordered list of alternatives of the
  same modality, each with conditions on the failure (provider error,
  timeout, health below a threshold). Never content refusals.
- **Who uses them.** Only round 12 batches created with `"fallbacks": true`
  use them. Single jobs and the Studio do not.
- **When a row's job fails on a covered condition,** the row is retried
  once on the next alternative, through the normal job path:
  - new pre-debit
  - the failed job refunded as today
  - the row records both jobs, and which model served
- **A content refusal is never retried** on another model.
- **The batch page and results CSV** show the serving model per row. Hand
  the app change over in your summary if the app's batch files are not
  yours.

## Hard rules

- Edits only in `routing/**`, `domain/UsageRecord.java` and its usage,
  `service/ChatCompletionService.java` (the hint only), the batch service
  (fallbacks only), new `web/Routing*` and admin routes, migration V41, and
  `application.yml`.
- **Every round 4 invariant holds.** A direct model call is unchanged.
- **With user policies disabled and no batch opting in,** behaviour is
  identical to the parent commit.
- Billing changes carry tests. Money in integer cents.
- Tests are named `method_givenX_expectsY`, use Testcontainers, and use
  WireMock cassettes.

## Opening move

Run the baseline. Confirm you have the operator's design documents, and post
the Phase 3 policy shape and validator rules you will build to, quoted from
them. Write the failing test that a routed chat request records
`routed_policy` and `routed_rule`. Then post a 3–5 bullet plan before
writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests, with all round 4 routing
  tests still green.
- **Tests cover:**
  - the Phase 2 columns
  - the margin view's sums
  - policy create, read, update and delete with the validator's refusals
  - the hint
  - the compliance opt-out
  - a region-forbidden candidate falling through
  - user policies disabled
  - a batch fallback on provider error with the refund and new pre-debit
  - no fallback on a content refusal
  - a batch without `fallbacks` unchanged
- A curl transcript of the policy API and a routed request in the final
  summary.
- All work committed on `feature/routing-policies`; `main` untouched.

## Out of scope (do not start)

- Round 4's Phases 4 and 5 (the evaluation harness, ensembles, a learned
  router).
- Fallbacks for single jobs.
- A policy editor in the app.
