# Round 13 — Coordination

**Drafted:** 2026-11-13 · **Parent:** `main` with round 12 merged
**Workstreams:** 01 MCP server (backend) · 02 Open recipe list
(marketing, CI) · 03 Comparisons and model launches (marketing, app)

Context: rounds 10–12 built the path from a found prompt to a batch, and
round 10's funnel now measures it. This round takes Godance to where people
find prompts and where their assistants work:
- an MCP server, so Claude, ChatGPT, Cursor and other MCP clients can find
  a recipe, estimate it and run it
- an open recipe list on GitHub, generated from the gallery, as the lists
  with 3.9k–17.4k stars are
- comparison pages that only a multi-model API can make cheaply, with a
  routine for the days after a model launch, when search interest peaks

See `docs/research/2026-11-prompt-gallery-competitors.md` §5.7 and §5.8.

**Prerequisites.** Round 12 on `main`: batches, capped keys, OpenAPI and
`llms-full.txt`.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-mcp         -b feature/mcp-server main
git worktree add ../gd-open-list   -b feature/open-recipe-list main
git worktree add ../gd-comparisons -b feature/comparisons main
```

## Reserved resources

| Resource | 01 MCP | 02 open list | 03 comparisons |
|---|---|---|---|
| Flyway | none expected (**V38** if forced) | none | none |
| Backend | new `mcp/` package, `pom.xml` (one dependency, flagged), `SecurityConfig` (one rule) | none | none |
| App | API keys page (one line: MCP setup) | none | the Studio's recipe hand-off (choosing a run) |
| Marketing | `docs/index.astro` (one section), `public/llms*.txt` (one line each) | new `scripts/open-list/**`, `package.json` (one script entry) | `scripts/gallery/**` (a `rerun` stage), `src/lib/gallery-schema.js` (per-run `approved_on`), `src/pages/gallery/compare/**`, `src/components/gallery/**`, `src/lib/gallery-*.ts` helpers |
| Other | none | new `.github/workflows/open-list.yml` | new `docs/gallery-model-launch.md` |

## Shared contract

### MCP (01)

- **Endpoint.** Streamable HTTP at `https://api.godance.ai/mcp`.
- **Auth.** `Authorization: Bearer <API key>`. The key's caps, scopes and
  image-response setting apply as on any request.
- **Tools.** Names are binding, because client configurations will refer
  to them:

| Tool | Does |
|---|---|
| `search_recipes` | Search gallery recipes and the caller's own recipes |
| `get_recipe` | One recipe: template, variables, input slots, runs, costs |
| `list_models` | Models with capabilities and prices, for a modality |
| `estimate_cost` | Exact pre-debit for a request, a recipe run or a batch |
| `generate_image` | Text to image, or an edit with inputs |
| `generate_video` | Text to video, or with first frame and reference images |
| `run_recipe` | Run one of the caller's recipes with values and inputs |
| `start_batch` | Start a recipe batch from rows |
| `get_job` | Status, outputs and cost of a job |
| `get_batch` | Status and counts of a batch |
| `upload_input` | Upload an image (base64) as an input file |

- **`max_cost_cents`.** Every tool that spends takes it. When the estimate
  is above it, the tool refuses without charging and returns the estimate.
- **Results** carry signed URLs, never file bytes.

### Gallery JSON (03)

`/gallery/<slug>.json` gains `runs`: every run's `model`, `params` and
`cost_cents`, in order. `run` stays, as the first run.

## Shared-file conflict watch

- `marketing/src/components/gallery/**` — 03 only; 02 reads content, not
  components.
- `marketing/public/llms.txt` — 01 adds one line.
- `marketing/src/pages/docs/index.astro` — 01 only this round.

## Hard rules (all sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- Before backend tests: `cd backend && docker compose up -d`, then `mvn -B verify`.
- `app/` and `marketing/` checks stay green.
- **No paid API call without the operator's go-ahead and a spending cap.**
  Every paid stage defaults to a dry run.
- **The open list publishes only approved, published recipes,** with
  attribution as the gallery shows it.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

Independent. Any order.

## Operator decisions

- **Hard constraint 4** in `backend/CLAUDE.md` says "No agent orchestration
  in v1 … no tool-calling, no agent runtime".
  - 01 reads it as: Godance must not run an agent. An MCP server only
    exposes Godance's existing API as tools for agents running elsewhere,
    and Godance itself makes no tool calls.
  - Confirm that reading before 01 merges. If confirmed, add one line to
    `backend/CLAUDE.md` saying so; CLAUDE.md is the operator's to edit.
- **OAuth for MCP.** Needed for ChatGPT and Claude.ai connectors, as opposed
  to configuration files. It is out of scope here.
- **Listing the MCP server** in the MCP registry and in the awesome-lists
  where MCP servers are collected.
- **The open list's repository:**
  - its name and owner, and the deploy key or token for CI
  - the licence for Godance's own text in it
- **The spending cap for the first comparison set.** Suggested: 1000
  cents, for 20 recipes on 5 image models.
- **Whether every Chinese recipe must carry a run on a China-hosted model.**
  03 builds the option.
- **Catalog naming.** A `nano-banana-pro` alias, and adding Nano Banana 2.
  These are catalog changes for a later backend round or the admin
  console.
