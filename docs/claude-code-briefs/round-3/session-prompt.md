# Opening prompt — Claude Code session for task #19

Copy-paste everything below into the new Claude Code session as the first message.

---

You're a Claude Code session working on Godance task #19 — **building a DB-backed model catalog with a daily Quartz pricing-refresh job that stages every change for human approval, never auto-applying to live billing.**

## Your setup

- Working directory: a dedicated git worktree at `~/1-Production/7.5-Token-Resale/Prod 7.5 Token Resale/gd-pricing` (or wherever the operator created it).
- Branch: `feature/dynamic-pricing`, off `main`, currently at commit `e10be63` with **202 tests green**.
- This is a single-instance workstream; the operator is not running other instances in parallel against this repo.

## Read these before writing any code, in this order

1. `../Prod 7.5 Token Resale/claude-code-briefs/round-3/dynamic-pricing-catalog.md` — your task brief. **Read it fully.**
2. `../Prod 7.5 Token Resale/claude-code-briefs/00-COORDINATION.md` and `../Prod 7.5 Token Resale/claude-code-briefs/round-2/00-COORDINATION.md` — engineering conventions (ModelInfo / microcents / Provider enum / regional allowlist / WireMock cassettes / RFC 7807 / reactive end-to-end) and the isolation lesson.
3. In the backend repo (`backend/src/main/java/ai/godance/`):
   - `gateway/ModelCatalog.java`, `gateway/CatalogContributor.java`, `gateway/catalog/{Text,Image,Video}Catalog.java` — what you're replacing as the runtime source.
   - `web/ModelsController.java` — the `/v1/models` shape you must keep byte-identical.
   - `service/AutoRechargeCronJob.java` + `config/QuartzJobsConfig.java` — the Quartz pattern to mirror.

## Hard rules — non-negotiable

- **Commit to `feature/dynamic-pricing` as you go.** Leaving work uncommitted is a defect. Use `git merge --no-ff -m "…"` (with `-m`) anywhere you integrate — never rely on an editor opening.
- **Do NOT `git push`. Do NOT deploy.** The operator handles both, later.
- **Before every `mvn clean test`**, bring up the dev stack from the backend dir:
  ```
  cd backend
  docker compose up -d        # postgres :5433, redis :6380, minio :9000
  docker compose ps           # confirm all three healthy
  ```
  Without MinIO on :9000, `@SpringBootTest` context boot fails (`S3ObjectStorageClient.start()` does a `headBucket` at `@PostConstruct`) and ~80 tests fail with `Connect to localhost:9000 refused`. If `docker` isn't on PATH: `export PATH="/Applications/Docker.app/Contents/Resources/bin:$PATH"`.
- **New Flyway migrations start at V10** (V9 is the latest committed; V10 was reserved-but-unused, so it's free).
- **The daily refresh job must NEVER auto-write a live billable price.** Every change goes through the staging/approval lane in `model_change_proposal`.
- **Don't touch any adapter, the `Provider` enum, the regional allowlist, the `CreditLedgerService` core, or `BillingProperties` markup tiers.**

## Your opening move

Don't start coding the schema yet. **First, probe the two feeds** so you build the parser against real shapes, not assumptions:

```
curl -s https://openrouter.ai/api/v1/models | jq '.data[0]' > /tmp/openrouter-sample.json
curl -s https://raw.githubusercontent.com/BerriAI/litellm/main/model_prices_and_context_window.json | jq '. | to_entries[0:3]' > /tmp/litellm-sample.json
```

Inspect both. Note any surprises (units, missing fields, naming). Then design your `ObservedPrice` parser and your model-id alias map around what you actually saw. Save short fixtures of those responses as WireMock cassettes in `backend/src/test/resources/` for the feed-parsing tests.

## Definition of done

`cd backend && docker compose up -d && mvn clean test` green on `feature/dynamic-pricing`, with all your commits on the branch (and `main` untouched). Hand back. The operator will merge to local `main` and decide on push/deploy later.

Begin by confirming you've read the brief and the conventions docs, and summarize back to me in 3–5 bullets what you intend to build before you start.
