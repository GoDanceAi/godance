# Round 13 — Opening prompts (paste as the first message of each session)

Start only after round 12 is merged to `main`. All three can run at the
same time.

## Session A — MCP server

```
You're a Claude Code session working on Godance round-13 brief 01 — MCP
server: expose Godance's existing API to MCP clients over Streamable HTTP
behind an API key, so an agent can find a recipe, estimate it, run it and
follow the job without spending more than it was told.

Setup: working dir is a dedicated git worktree on branch feature/mcp-server,
off main with round 12 merged. Confirm the backend test count with a full
run before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-13/01-mcp-server.md (the brief)
2. docs/claude-code-briefs/round-13/00-coordination.md (endpoint, auth and
   tool names are binding; read the operator decision on hard constraint 4)
3. backend/CLAUDE.md
4. ApiKeyAuthFilter, SpendingLimitFilter, SecurityConfig, and the services
   behind each tool (recipes, estimates, images and edits, video, batches,
   input files, jobs)
5. The MCP specification (Streamable HTTP, tools); the MCP Java SDK and
   Spring AI's MCP server starter, checked against Boot 4.1 and Jackson 3

Hard rules:
- Commit to feature/mcp-server as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then
  `mvn -B verify`.
- Godance runs no agent and makes no tool calls of its own.
- Every tool goes through the same services as REST: validation, ledger,
  caps, regional allowlist. No tool manages keys, billing or accounts.
- max_cost_cents is never exceeded.
- One dependency at most, flagged with version and reason.

Opening move: run the baseline. Post your library choice with Boot 4.1
evidence. Write the failing test that an MCP client initialises with an API
key and lists exactly the contract's tools. Then summarize your build plan
in 3–5 bullets before writing code.

Done = mvn -B verify green (baseline + yours), every acceptance case covered
by a test, a real MCP client session in the summary, all commits on your
branch, main untouched. Hand back.
```

## Session B — Open recipe list

```
You're a Claude Code session working on Godance round-13 brief 02 — Open
recipe list: generate a bilingual open list of published recipes, with
exact model, parameters and cost per run, and publish it to its own GitHub
repository from CI (off until the operator configures it).

Setup: working dir is a dedicated git worktree on branch
feature/open-recipe-list, off main with round 12 merged. Confirm
`npm ci && npm run lint && npm run build && npm test` is green in marketing/
before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-13/02-open-recipe-list.md (the brief)
2. docs/claude-code-briefs/round-13/00-coordination.md
3. The gallery schema and reader, gallery-config.ts, the recipe page's
   credit lines, the removal list
4. .github/workflows/ci.yml and deploy-frontends.yml

Hard rules:
- Commit to feature/open-recipe-list as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Only approved, published recipes; attribution never weaker than the
  recipe page; no media copied.
- The workflow does nothing when its variable or secret is unset, never
  force-pushes, and never runs for forks.
- No new dependency.

Opening move: run the baseline. Find the code that builds the recipe page's
credit lines and plan to reuse it. Write the failing test for a CC BY
fixture entry. Then summarize your build plan in 3–5 bullets before writing
code.

Done = marketing lint, build and test green, generated READMEs and the
workflow skip log in the summary, all commits on your branch, main
untouched. Hand back.
```

## Session C — Comparisons and model launches

```
You're a Claude Code session working on Godance round-13 brief 03 —
Comparisons and model launches: a rerun stage that adds runs on other
models to published recipes under a cap, comparison pages from real runs,
the 48-hour launch runbook, and a hand-off that picks a run the visitor can
use.

Setup: working dir is a dedicated git worktree on branch feature/comparisons,
off main with round 12 merged. Confirm marketing and app checks are green
before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-13/03-comparisons-and-model-launches.md
2. docs/claude-code-briefs/round-13/00-coordination.md (runs in the JSON
   is binding)
3. marketing/scripts/gallery/** (generate, budget guard, review, publish)
4. The gallery schema, pages, model pages and helpers
5. The app's recipe hand-off

Hard rules:
- Commit to feature/comparisons as you go. Do NOT merge to main. Do NOT push
  or open a pull request unless asked. main auto-deploys.
- Every paid stage is a dry run by default and needs --max-spend-cents.
- Nothing is published unreviewed; existing runs and reviews never change.
- No winner is declared on any page.

Opening move: run the baselines. Write the failing test that a dry-run
rerun plans one job per recipe and spends nothing. Then summarize your
build plan in 3–5 bullets before writing code.

Done = marketing and app checks green, every acceptance case covered, a
dry-run plan and screenshots in the summary, all commits on your branch,
main untouched. Hand back.
```
