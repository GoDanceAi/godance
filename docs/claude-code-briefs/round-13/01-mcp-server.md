# Brief 01 — MCP server

**Branch:** `feature/mcp-server` (worktree `../gd-mcp`) off `main` with round 12 merged
**Reserved migration:** none expected; V38 if forced
**Baseline:** full backend suite green on parent; record the count first

## Mission (one line)

Expose Godance's existing API to MCP clients over Streamable HTTP, behind an
API key. An agent in Claude, Cursor or another MCP client can then find a
recipe, see what it costs, run it, and follow the job, without spending
more than it was told it may.

## Why now

The people Godance is built for increasingly work inside an AI assistant.
MeiGen shows the pattern: its remote MCP server puts its gallery and
generation inside ChatGPT, Claude and Cursor, and has about 1.8k stars.

Godance already has everything an agent needs:
- recipes
- estimates
- generation with inputs
- batches
- per-key caps

The MCP server is a thin, safe layer over them.

## Read first

1. This brief and `00-coordination.md` (the endpoint, auth and tool names
   are binding)
2. `backend/CLAUDE.md`, especially hard constraint 4, and the operator
   decision about it in `00-coordination.md`
3. `security/ApiKeyAuthFilter.java`, `security/SpendingLimitFilter.java`,
   `config/SecurityConfig.java`
4. The services behind each tool:
   - recipes (round 10)
   - estimates (round 7, extended in rounds 11 and 12)
   - image generation and edits (rounds 7 and 11)
   - video with inputs (round 11)
   - batches (round 12)
   - input files (round 11)
   - jobs
5. `marketing/src/lib/gallery-config.ts` and `/gallery/index.json`, for
   gallery search
6. The MCP specification for Streamable HTTP, tools, structured output and
   errors
7. Candidates for the server side:
   - the official MCP Java SDK's WebFlux transport
   - Spring AI's MCP server starter

   Check each against Spring Boot 4.1 and Jackson 3 before choosing.

## Scope

### 1. The server

- **Mounting.** Mount at `/mcp` on the existing WebFlux application, with
  Streamable HTTP. Add one rule in `SecurityConfig`: API keys only, so a
  browser session cannot call it, and it is never public.
- **Dependency.** Add one dependency at most, and flag it in the plan with
  its version and why. If neither candidate works with Boot 4.1 and Jackson
  3, implement the Streamable HTTP subset this needs directly, and say so.
- **Requests run as the key.**
  - Every tool call runs as the key's user, through the same services the
    REST API uses: same validation, same errors, same ledger, same caps,
    same regional allowlist.
  - No tool reaches a repository directly.
  - Jobs a tool creates carry `channel: "API"`, as a key's do.
- **Rate limit** per key with the existing limiter.

### 2. Tools

As listed in the contract. For each:
- **Description.** Written for a model to read: what it does, what it
  costs, and when to use it. Spending tools say to call `estimate_cost`
  first.
- **Input schema.** JSON Schema with Godance's real field names, matching
  the REST request.
- **Output.** Structured output, plus a short text summary.

Specifics:
- **`search_recipes`:**
  - gallery recipes from the published `/gallery/index.json` (cached for a
    few minutes)
  - the caller's own recipes
  - with a query and an optional modality
  - each result marked `gallery` or `mine`
- **`generate_image` and `generate_video`:**
  - When the key is set for synchronous images, `generate_image` waits as
    round 10 defined.
  - Otherwise both return the job id with a hint to call `get_job`. Never
    block longer than the REST route would.
- **`upload_input`.** Base64 in, an input file out, under round 11's policy
  and limits. Refuse more than the REST limit.
- **`max_cost_cents`** as in the contract. A refusal returns the estimate
  and the reason, and no charge.
- **Errors** come back as MCP tool errors carrying the REST `code`, so the
  agent can act on it. For example, `inputs_terms_required` tells it the
  user must accept the terms in the app.

### 3. Setup help

- **API keys page:** one line under each key with a "Use with MCP" link that
  opens a short panel showing the configuration snippets for Claude Code,
  Cursor and a generic client. Snippets show `$GODANCE_API_KEY`, never the
  key.
- **Docs.** A "Use from an AI assistant (MCP)" section in
  `marketing/src/pages/docs/index.astro`: the endpoint, auth, the tools, and
  the safety rules (caps, `max_cost_cents`).
- **`llms.txt` and `llms-full.txt`:** one line each, pointing to the
  section.

## Hard rules

- Edits only in `backend/src/**` (a new `mcp` package and one
  `SecurityConfig` rule), `pom.xml` (one flagged dependency),
  `app/src/pages/ApiKeysPage.tsx` with its e2e, and the docs files named
  above.
- **Godance runs no agent and makes no tool calls of its own.** It answers
  tool calls from clients.
- **No tool can do what the key's REST access cannot.** In particular, none
  manages keys, billing, accounts or webhook secrets.
- **A spending tool never charges more than `max_cost_cents`** when it is
  given. Test it.
- Tests are named `method_givenX_expectsY` and use Testcontainers.

## Opening move

Run the baseline. Post which server library you chose, with its version and
Boot 4.1 compatibility evidence, or why you are implementing the subset
yourself. Write the failing test: an MCP client initialises against `/mcp`
with an API key, lists the tools, and gets exactly the contract's names.
Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `mvn -B verify` green: baseline plus new tests.
- **Tests cover:**
  - initialise and list tools
  - a session cookie refused
  - each tool's happy path against WireMock providers
  - `max_cost_cents` refusing above the estimate with no ledger row
  - a key cap pausing or refusing as REST does
  - the regional allowlist applying
  - input policy errors surfacing their codes
  - rate limiting
- In the final summary:
  - a session from a real MCP client (Claude Code or the MCP Inspector)
    against the local stack: search, estimate, generate, poll
  - the tool list as an agent sees it
- All work committed on `feature/mcp-server`; `main` untouched.

## Out of scope (do not start)

- OAuth for MCP.
- MCP resources and prompts beyond tools.
- Interactive UI in MCP hosts.
- A local stdio package.
- Chat completion as a tool.
