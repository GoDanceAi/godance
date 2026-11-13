# Prompt galleries: OpenNana and its competitors

**Date:** 2026-11-13, updated the same day with the operator's choice of
audience · **Context:** written after the round 9 briefs, to inform what
comes next. Rounds 7–9 are designed; on `main` only the round 8 recipe
schema has landed.

**Question:** what do OpenNana and the strongest prompt-gallery products, in
China and elsewhere, tell us about the Godance gallery, Studio and API?

## How this was put together

- Web search, plus the competitors' public GitHub repositories, read directly.
- Licences were checked by reading each repository's `LICENSE` file.
- The prompt statistics in §4 were measured on the README files of four lists
  on 2026-11-13. The method and its limits are given there.
- opennana.com, youmind.com, meigen.ai and evolink.ai could not be opened from
  the research environment. Facts about those sites come from search-result
  text, third-party directories and the operators' own GitHub repositories.
  Counts on live sites change daily; treat them as orders of magnitude.

## 1. Summary

**Who Godance is for** (operator decision, 2026-11-13): *aspiring builders*.

- Experienced developers can assemble providers themselves, with more
  freedom and options than an aggregator gives them.
- General consumers will not learn a tool this capable and are better served
  by consumer assistants.
- Aspiring builders sit between the two. Most are not coders or game
  designers. They see that AI lets them make things they could not make a
  few years ago. They will end up generating video in batches through an
  API, but only after they have searched for prompts that work and refined
  them by trial and error dozens of times.

Not targeting experts or consumers does not mean not serving them. The
features they want come in later rounds, built on the same pieces (§5.12).

What the research says about serving aspiring builders:

1. **The prompt is a commodity.** Tens of thousands of prompts for the newest
   models are free, many under CC0 or CC BY. They are copied between lists,
   and the same prompts reach every gallery. Godance's round 8 sources are two
   of these lists, one maintained by YouMind and one by EvoLink.
2. **Nobody carries a user from a tuned prompt to a batch run.** OpenNana
   helps people find a prompt. YouMind and MeiGen help them try it and adapt
   it. fal, kie.ai and EvoLink serve the batch end, and non-coders reach them
   through third-party n8n templates. In between, users copy text by hand.
   A Godance recipe (prompt, variables, model, parameters, cost) is the right
   object to carry them through.
3. **About a quarter of popular prompts need an input.** In the four lists
   measured, 14–32% of image prompts and 31% of Seedance prompts need an
   uploaded image or clip. The round 8 import has to set these aside, because
   the API accepts text only. Inputs matter twice over for this audience:
   - **Adapting:** "my product", "my character".
   - **Cheap iteration:** the standard workflow is to settle the frame in GPT
     Image 2, then animate it with Seedance 2.0.
4. **Their developer is often an AI assistant.** It writes standard OpenAI
   SDK code from memory. That code breaks on Godance's image route today,
   because every image model answers `202` with a job. For this audience it
   matters more than for experts that AI assistants can use Godance as-is:
   standard response shapes, AI-readable docs, and MCP.
5. **Measure first, and measure graduation.** Track how many Studio users go
   on to generate through an API key, and how many run a batch. Round 8
   already notes that nothing records whether a first run started from a
   recipe.

## 2. OpenNana

| | |
|---|---|
| What | A prompt gallery at `opennana.com/awesome-prompt-gallery`, Chinese-first, branded "松果先森 Nana 提示词图库" |
| Models | GPT Image 2, Nano Banana 2 and Pro, Seedance 2.0; filters also list ChatGPT and Grok |
| Size | The site showed about 24,556 prompts when indexed. Chinese articles from early 2026 cite 1,215 Nano Banana and 110 Seedance prompts, so it has grown quickly. |
| Features | Search, filters by model and tag (电影感, 写实, 人像…), copy prompt, English and Chinese prompt text, example image or video. Seedance pages carry a "去AI生视频" button; where it leads was not verified. |
| Generation | None found. It is a reference site. |
| Origin | Built from the GitHub list `songguoxs/gpt4o-image-prompts` (3.9k stars, 565 forks). The author describes the site as running on Cloudflare's free tier. |
| Data | A JSON API at `api.opennana.com`: list, search, detail, random, models and stats. A third-party project scrapes it to self-host the gallery. |
| Community | Off-site: a WeChat official account and a Telegram group. Popular on Zhihu, CSDN and Chinese X. |
| Licence | **None.** The repository has no licence file. Under the round 8 rules it can never be a source. |

**What it shows.** One person, a GitHub list and a free CDN built one of the
most-cited Chinese prompt sites in about a year. It did so by keeping up with
model launches, offering both languages and making entries easy to copy. In
the README sample, 143 of 145 entries are Nano Banana Pro. That matters for
Godance: the Alicloud region cannot serve Google or OpenAI models (§5.9).

## 3. The field, and the top three

| Kind | Examples | Why it is or is not in the top three |
|---|---|---|
| Gallery only | **OpenNana**, aipromptnav, Promptfill, bananaprompts.org, nanobananaprompts.ai, awesomebananaprompts.com | OpenNana is the baseline. The rest are smaller SEO sites. |
| Gallery with generation | **YouMind**, **MeiGen**, OpenArt, Krea | YouMind is the largest. MeiGen is closest to what Godance is building. |
| API seller with galleries | **EvoLink**, Atlas Cloud, WaveSpeed, kie.ai | EvoLink is the clearest case, and the most like Godance. |
| The model maker's own app | 即梦 Jimeng and Dreamina (ByteDance) | Native Seedance with an inspiration feed. It sets what users expect ("see it, make the same"), but no third party can match first-party access. |
| Older Stable Diffusion and Midjourney communities | Civitai, PromptHero, Lexica | Their creator economy is useful background for referrals (§5.11). They are not where people look for prompts for the newest models. |

Each of the three chosen is the strongest of its kind for GPT Image 2,
Nano Banana and Seedance.

### 3.1 YouMind: the largest (global)

- **Product.** An AI workspace: sources and notes become articles, slides,
  images and video. Freemium subscription. The prompt library brings people
  in; "every prompt on this page runs directly inside YouMind".
- **Size.** 15,848 Nano Banana Pro prompts and 6,684 Seedance prompts (README
  statistics on 2026-11-13). The site claims 30,000+ image prompts overall.
- **Distribution.** Open lists on GitHub under `YouMind-OpenLab`. The Nano
  Banana Pro list has 13.8k stars, is licensed **CC BY 4.0**, and has READMEs
  in 16 languages. A GitHub Actions badge indicates the README is regenerated
  automatically. Every entry ends in "Try it now →" linking to
  `youmind.com/…?id=<n>`. Site pages also have stable slugs, such as
  `/video-prompts/<slug>-<id>`.
- **Structure.** 41 categories in three groups: use case (10), style (16) and
  subject (15). Each entry has a description, prompt, images, author and
  original X post, date and language. Prompts with variables also work as
  Raycast snippets. Removal requests go through a GitHub issue.
- **What it shows.** Updating the list automatically, by structured category
  and in many languages, wins on volume. A "try it" link on every entry turns
  a free list into a sign-up funnel.

### 3.2 MeiGen: the closest product shape (Chinese and international)

- **Product.** A prompt gallery covering GPT Image 2, Nano Banana 2,
  Seedance 2.0, Veo 3.1 and Midjourney. Any prompt can be run in one click
  on several models. Directories list GPT Image 2, Nano Banana 2,
  Seedream 5.0 Lite, Midjourney V8.1, Niji 7 and Z Image Turbo. Other
  features:
  - **Reference remix:** upload an image and it extracts the composition and
    tone.
  - **Prompt enhancement.**
  - **Credits:** non-refundable once used. Directories report a small daily
    free allowance.
  - **An API.**
- **Agents.** `jau123/MeiGen-AI-Design-MCP` has about 1.8k stars. It is a
  remote MCP server at `meigen.ai/api/mcp` (Streamable HTTP, OAuth):
  - Searching for inspiration and looking up models cost nothing; generating
    uses purchased credits.
  - It adds "skills" for product shots, posters, background removal and
    upscaling, plus interactive gallery views in MCP hosts.
  - It has been put forward for the awesome-claude-code list.
- **What it shows.** Browsing, then running with the user's own image, then
  paying, is a complete loop. Adding MCP means the gallery also reaches people
  inside their AI assistants, not only people who come to the site.

### 3.3 EvoLink: the closest business to Godance (global)

- **Product.** An API seller: "one API key for 170+ models" across text,
  image, video and music. It markets on price, with a "lowest price
  guarantee" and its own comparison tables. For example, it lists Seedance 2.0
  at 720p for $0.198 a second on EvoLink against $0.3034 on fal. Those figures
  are EvoLink's own and were not checked.
- **Distribution.**
  - `Evolink-AI/awesome-gpt-image-2-API-Prompts` has 17.4k stars, is
    licensed **CC0**, and has READMEs in 11 languages. Every output image
    links to `evolink.ai/gpt-image-2-prompts` with UTM tags.
  - The site has prompt galleries, "collections" that compare model families,
    a cookbook and `llms-full.txt`.
  - It publishes "coming soon, get early access" pages for models before
    their API opens (Seedance 2.0 and 2.5), to catch searches early.
- **What it shows.** A developer API can get consumer-scale reach from a free
  open list. Model-launch pages and comparison pages are the SEO units, not
  single prompts. EvoLink is also Godance's planned first image source.

### 3.4 Side by side

| | OpenNana | YouMind | MeiGen | EvoLink | Godance after round 9 (as designed) |
|---|---|---|---|---|---|
| Gallery size | ~24.5k | 15.8k NBP, 6.7k Seedance, 30k+ claimed | thousands; its MCP ships ~1.4k | 462 cases in README | first batch, capped at 6000 ¢ of spend |
| Run in one click | no | yes, in its workspace | yes, several models | API and playground | "Run this" into the Studio (round 8) |
| Input image or clip | n/a | yes | yes (reference remix) | API | **no** |
| Cost shown per prompt | no | no | credits | per model, not per prompt | **yes** (`cost_cents`) |
| Exact API parameters | no | no | no | per model | **yes** (`params`) |
| Same prompt on several models | no | no | user picks | comparisons by model family | **possible** (`runs[]`), not yet planned |
| Open list on GitHub | yes, no licence | yes, CC BY | MCP only | yes, CC0 | no |
| Agents (MCP) | no | no | yes | no | no |
| Languages | zh, en | 16 | zh, en | 11 | en, zh |
| Paid through | none found | subscription | credits | API credits | API credits, card or USDT |

## 4. What the lists contain (measured)

Entries were taken from each README. Each prompt was matched against
English and Chinese phrases that mean it needs an input, such as "uploaded
photo", "reference image", "identity reference", 参考图, 上传 or Seedance's
`@Image1`.

A sample of matches checked by hand was almost all correct once loose terms
like "selfie" were dropped. These are estimates from the README samples, not
counts of the whole sites.

| List (README sample) | Entries | Need an input | Have template variables | JSON-shaped | Median length |
|---|---|---|---|---|---|
| EvoLink GPT Image 2 (CC0) | 462 | 21% | 20% | 3% | 1,044 chars |
| OpenNana / songguoxs (no licence) | 145 | 32% | 20% | 37% | 1,749 chars |
| YouMind Nano Banana Pro (CC BY) | 129 | 14% | 47% | 12% | 937 chars |
| YouMind Seedance 2 (CC BY) | 106 | 31% | n/a | n/a | n/a |

- **Inputs are mostly people.** Of the image prompts that need an input, a
  person's face or identity is the subject in roughly 50–70%. A product or
  object alone is rare. This is the "put me in this scene" use: the most viral
  and the most sensitive.
- **Variables are common.** Both global lists use
  `{argument name="…" default="…"}`. The round 8 import resolves them to their
  defaults, which throws away the template.
- **Prompts are long and structured.** Many are over 1,000 characters, and a
  third of OpenNana's are JSON. The backend has no length cap on `prompt`,
  which is right; the Studio's prompt box should be built for this length.
- **Models move fast.** The YouMind Seedance list already has Seedance 2.5
  prompts. Competitors advertise GPT Image 2.5, Nano Banana 2 and 2.1, and
  Seedance 2.5. The Godance catalog's price notes were verified on
  2026-05-21.

## 5. What this means for Godance

### 5.1 The journey, and who covers it

| Step | What the user does | Who covers it today | Godance after round 9 (as designed) |
|---|---|---|---|
| Find | Looks for a prompt that gets an outcome: a product video, a thumbnail, a short scene | OpenNana, YouMind, MeiGen, posts on X | Gallery (round 8) |
| Adapt | Swaps in their own product, character or wording | YouMind; MeiGen with reference remix | "Run this" fills the Studio. No inputs, and variables are resolved away |
| Iterate | Dozens of attempts, comparing wording, models and cost | MeiGen and YouMind, mostly by hand | History (round 7) and saved results (round 9). Attempts are not grouped or compared |
| Lock in | Keeps the setup that works | Nobody, beyond copying text | Nothing |
| Batch | Runs it over 20–500 rows | fal, kie.ai and EvoLink APIs, through n8n templates where each sheet row is a job | API only, and the image route breaks standard SDK code |
| Automate | Schedules runs and publishes the results | n8n, Make, or scripts the user's AI assistant writes | Signed webhooks exist; no templates |

The gap is between lock-in and batch. One idea guides everything below:
**one object all the way through.**

1. A gallery recipe becomes the user's own recipe.
2. Their attempts hang off it.
3. The version that works runs in batch.
4. The same batch is available as code, as an n8n template and through MCP.

### 5.2 Measure graduation first

Round 8 notes the gap: "Not covered by any brief yet: measuring how many
first runs start from a recipe."

- **Record where each job came from:** `studio`, `recipe:<slug>`,
  `my-recipe:<id>` or `api`. A small nullable column, set by the Studio,
  is enough.
- **Record each sign-up's first-touch source** (UTM tags and recipe slug), the
  way round 9 stores a referral code.
- **Report the funnel:** recipe → sign-up → first Studio run → first top-up →
  first generation with an API key (*graduation*) → first batch.

This is cheap. Without it, none of the choices below can be judged.

### 5.3 Make recipes the user's own (adapt and lock in)

- **Keep templates as templates.** 20–47% of entries have variables. Both
  global lists use `{argument name="…" default="…"}`, and Promptfill, a
  Chinese tool, does nothing but fill in templates. Round 8 resolves the
  variables to their defaults. Keep the template next to the resolved text,
  for example a `prompt.template` with `{{name}}` markers alongside the
  existing `prompt.variables`. This edits the schema module, which round 8
  froze, so it belongs to a later round.
- **Studio fields.** The Studio shows one field per variable, filled with the
  defaults. Variables are also what a batch varies (§5.6).
- **My recipes.** A user can copy a gallery recipe, or save the current
  Studio setup, as their own. A recipe holds the template, the variable
  values, the model, the parameters, and later its inputs. Editing it makes a
  new version. Recipes are private by default.
- **Jobs record the recipe and version they ran.** Round 9's saved
  generations keep the outputs; a recipe keeps the setup that made them.

### 5.4 Make iteration cheap and visible

- **Show attempts together.** Group a recipe's attempts side by side. For
  each one, show what changed (a diff of the prompt, the model, the
  parameters) and its cost, with a running total.
- **Show the cost before every run.** Round 7's estimates endpoint already
  gives the exact amount that will be held.
- **Draft before the final run.** For video, settle the frame as an image
  first, then animate it (needs §5.5). Where a model allows it, draft shorter
  or at a lower resolution. The GPT Image 2 → Seedance 2.0 workflow is now
  standard, in both Chinese and English tutorials.
- **Why it matters, at round 8's rough prices** (about 6 ¢ an image, 16 ¢ a
  second of Seedance):
  - 30 attempts at a 10-second clip cost about 48 USD.
  - 30 stills, then 3 clips from the chosen frame, cost about 6.60 USD.
- **Optional and cheap: suggest variations.** A low-cost text model proposes
  versions of a prompt to try, as MeiGen's prompt enhancement does.

### 5.5 Accept input images and clips

This is the largest capability gap: 21–32% of image prompts and 31% of
Seedance prompts are currently set aside. Every competitor that generates
accepts inputs, and the image-first video workflow depends on them.

- **API.** Add `POST /v1/images/edits` in OpenAI's multipart shape (`image`,
  or several `image[]`, plus optional `mask`, `prompt`, `model`, `n`,
  `size`). In WebFlux that is
  `@PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)` with
  `@RequestPart("image") Flux<FilePart>`. For video, add reference and
  first-frame images where the adapter supports them (Seedance 2.0, Sora 2,
  Kling 3.0, Veo 3.1).
- **Studio.** An upload slot, "Animate this" on an image result, and "describe
  this image as a prompt" through a vision-capable text model.
- **Storage and retention.** Inputs go to the existing object storage under
  the job, and are deleted with it through `DataRetentionService`. Saved jobs
  from round 9, and recipes that use an input, keep it.
- **Recipes.** Add an `inputs` list with slots ("one product photo on a plain
  background") and a sample input Godance owns. List images are never
  copied, as round 8 already requires.
- **Safety comes first, and it is the operator's decision.** Most input
  prompts are about a real person's likeness, and the gallery standard bans
  recognisable real people. A staged release:
  1. Product and object references.
  2. AI-generated sample people in the gallery.
  3. A user's own photo, for `ADULT` accounts only, after the user confirms
     the photo is of themselves or that they have consent, with provider
     moderation, and never shown publicly.

### 5.6 Batch, then hand over

- **Batch in the Studio.** Run a recipe version over a sheet: paste it or
  upload a CSV, with one column per variable and later one per input file.
  - Show the total estimate and hold it. Spend holds already exist (V18).
  - Run with a bounded number of jobs at once and show progress.
  - Offer a zip of the outputs and a CSV of the results.
  - Run it on the server, so closing the tab does not stop it. Quartz and
    the job sweep already exist.
- **Every recipe and batch offers the next step:**
  - **"Copy for your AI assistant":** a ready-written prompt holding the
    recipe's JSON and a link to the AI-readable docs (§5.7). It asks the
    assistant for a script that reads the user's sheet. This audience's
    developer is often an AI, and this audience already knows how to write
    prompts.
  - **Code:** `curl`, the Python OpenAI SDK and a Java `WebClient`, generated
    from the recipe, with the cost.
  - **An n8n template:** Google Sheets row → Godance job → signed webhook →
    Drive. Templates in n8n's library are how non-coders find kie.ai and fal
    today. Godance's async jobs and signed webhooks fit that pattern
    already. A community node can come later.
  - **A new API key with a daily cap, in one click.** Per-key daily and
    monthly caps already exist in `SpendingLimitService`. Show them here,
    where the fear of a runaway script is greatest.
- **Later, if there is demand:** OpenAI's `/v1/batches` shape. Not first.

### 5.7 Make Godance usable by AI assistants as-is

- **Images.**
  - Every image model is flagged async, so standard OpenAI SDK code that
    reads `data[0].url` fails.
  - Option: when a request does not ask for async, wait for the job up to a
    limit (for example 120 seconds, within the SDK's default timeout), then
    answer in OpenAI's shape. Past the limit, answer `202` as today. In
    WebFlux this is a non-blocking wait on the job's completion, not a held
    thread.
  - This changes what existing image callers receive, so it is an operator
    decision (§7).
- **Video.**
  - OpenAI's own video API is `/v1/videos`, used by `client.videos.create`,
    then retrieve and download. Godance serves `/v1/videos/generations` and
    `/v1/jobs/{id}`.
  - Serving OpenAI's shape as well would let code that an assistant writes
    work unchanged. Check OpenAI's current shape before designing it.
- **Docs written for AI.** An `llms-full.txt` with complete request and
  response examples for images, video and jobs; an OpenAPI file; and a page
  on asking an AI assistant to use Godance.
  `docs/openai-dropin-verification.md` checks chat only; add images and video.
- **An MCP server.**
  - Tools such as `search_recipes`, `list_models`, `estimate_cost` (the round
    7 endpoint), `generate_image`, `generate_video`, `get_job` and, once
    batches exist, `run_recipe_batch`.
  - MeiGen shows this reaches people inside ChatGPT, Claude and Cursor.
  - The Spring AI MCP server starter has a WebFlux transport; check that it
    works with Boot 4.1.
  - Start with API-key authentication, and let per-key caps limit what an
    agent can spend.

### 5.8 Be where people look for prompts

- **Make the run the product, not the prompt.**
  - The same prompts appear on youmind.com and evolink.ai, which have far
    more backlinks.
  - A Godance recipe page offers something new only through what Godance
    adds: its own outputs, the parameters, the cost, and other models.
- **The same prompt on several models.**
  - The schema already allows several runs. Re-run the best recipes on
    `gpt-image-2`, `nano-banana`, `seedream-5.0`, `qwen-image-2.0` and
    `flux-2-pro`.
  - Publish comparison pages, such as "GPT Image 2 vs Nano Banana Pro: 20
    prompts, outputs and costs". They help people choose a model before they
    spend.
  - An image set costs about 20 × 5 × 6 ¢ ≈ 6 USD.
- **A routine for model launches.** When a model enters the catalog, re-run
  the top recipes on it within 48 hours and publish a comparison. That is
  when search interest is highest.
- **An open list Godance maintains.**
  - A separate repository whose README is generated from
    `marketing/src/content/gallery` by CI, in English and Chinese.
  - Each entry gives the model, parameters, cost and a "Run this" link with
    UTM tags.
  - The pitch none of the 3.9k–17.4k-star lists can make: *every example was
    generated through the API with these exact parameters, for this price.*
- **Stay small and checked.** Do not try to match 24k entries.

### 5.9 Models and regions

- **Naming.** Catalog id `nano-banana` maps to `gemini-3-pro-image-preview`,
  which the market calls *Nano Banana Pro*, while "Nano Banana" usually means
  the earlier Flash model. Add a `nano-banana-pro` alias, and consider adding
  the cheaper Nano Banana 2, which OpenNana and MeiGen feature. Check the
  upstream id and price first.
- **Freshness.** Check GPT Image 2.5 and Seedance 2.5 against the providers.
  Gallery traffic follows the newest model.
- **Chinese audience.**
  - Aspiring builders are a large group in China: e-commerce sellers,
    short-drama makers, people with AI side businesses. OpenNana's audience
    is this group, and it mostly uses Nano Banana Pro.
  - The Alicloud region's allowlist excludes Google and OpenAI, and those
    providers' own terms limit where their users can be. These are separate
    questions; the operator should answer both.
  - In practice: every recipe shown in Chinese should carry at least one run
    on a China-hosted model (`seedream-5.0`, `qwen-image-2.0`,
    `seedance-2.0`, `kling-3.0`). "Run this" should pick a run whose model
    is listed in `/v1/models` for the user.

### 5.10 Sources and licences

- OpenNana's list has no licence, so it is out, correctly.
- The CC0 and CC BY lists collect posts written by X users. A list's licence
  covers the compiler's own work; it cannot grant rights the individual
  authors never gave. Round 8 already credits each author, keeps the original
  post URL, offers a removal contact and keeps a removal list. The content
  standard should also say so: a list licence is necessary but not
  sufficient.
- **A cleaner source over time:** users publish their own recipes (§5.3) and
  grant a licence when they do. OpenNana has a `submit.html` page, and
  YouMind accepts pull requests.

### 5.11 Referrals and creators

- Civitai gave resource creators 25% of generation spend and is now
  reworking that scheme. YouMind and EvoLink credit authors but do not pay
  them.
- Once round 9's referral has proved itself, a published recipe can act as
  a referral link: a top-up that starts from it credits its author. It would
  reuse the `REFERRAL` ledger source, idempotency keys and monthly cap.
- Not yet: card disputes are not handled, so paying creators would make the
  exposure round 9 already notes larger.

### 5.12 Consumers and experts: served later, on the same pieces

Godance targets aspiring builders and serves everyone. What consumers and
experts want comes after rounds 10–13, because each item builds on
something those rounds make. Several also help aspiring builders.

**Social features (consumers, and learners)**
- **Builds on:**
  - published recipes (§5.10)
  - creator rewards (§5.11)
  - saved generations (round 9)
- **Shape:**
  - public profiles with published recipes
  - "made from" lineage, as with Civitai's remix
  - follows
  - a count of how often a recipe was run, which is a more honest signal
    than likes alone
  - likes and comments after that
- **Why it helps the target too:** seeing how someone else tuned a recipe is
  how aspiring builders learn.
- **Needs first:**
  - moderation and reporting for user content
  - the content standard extended from the curated gallery to user posts
  - a rule for whether user content is all-ages, as the gallery is
  - the real-person rule
  - a removal process
- **Earliest:** after round 13, once users can publish recipes.

**Bring your own key (experts)**
- **Builds on:**
  - the provider gateway
  - usage rows in the ledger, which would record zero provider cost plus
    Godance's fee
  - per-key caps
- **Needs first:**
  - Store users' provider keys encrypted, and never log or return them.
  - Keep the regional allowlist binding: a user's own key must not route
    around it. The allowlist stays in code and fails closed.
  - Check each provider's terms on keys used through a third party.
- **Pricing** is an operator decision: a fee per request, or a flat plan.
- **Earliest:** after batch (round 12), because BYOK users want batch and MCP
  too.

**Custom routing policies (experts)**
- **Already designed.** Round 4's routing design puts user-authored
  policies in Phase 3: `RoutingPolicyEntity`, `/v1/routing/policies` and the
  `X-Godance-Route` hint. Phase 2 (`routed_policy` and `routed_rule` on usage
  records) comes before it.
- **Why it helps the target too:** a 500-row batch should not fail because
  one provider is down. Fallback rules such as "this model, else that one"
  give batches reliability.
- **Earliest:** continue the round 4 phases once batches run at volume.

**Supported self-hosting (experts and teams)**
- **Builds on:** the repository, which is already Apache 2.0. Anyone can run
  it today, but nothing supports doing so.
- **Shape:**
  - an install guide
  - packaging beyond the development compose file
  - a configuration reference
  - upgrade notes for Flyway migrations
- **Needs first:**
  - Decide what a self-hosted copy does about billing. The credit ledger is
    central to the code.
  - The regional allowlist stays in force.
- **Who it serves:** teams with data-residency needs, including deployments
  in China under the Alicloud allowlist.
- **Earliest:** any time for documentation. A support commitment is the real
  cost.
- **Not the same as round 4's Phase 1b,** which hosts a cheap open-weight
  model inside Godance.

**One thing stays a no:** publishing curated gallery content unreviewed.
Round 8 makes this a hard rule. User posts are a separate case, governed by
moderation as above.

### 5.13 Positioning wording

`marketing/public/llms.txt` says Godance is "an OpenAI-compatible developer
API, not an end-user product" that "targets engineering teams". Rewrite it
and the home page when the Studio ships. For example: *built for people
building with AI, whether or not they are engineers yet: find a recipe, make
it yours in the Studio, then run it at scale through the same API.*

## 6. A possible order after round 9

Each round can run as parallel workstreams, as rounds 7–9 do. These rounds
are drafted as session briefs in `docs/claude-code-briefs/round-10/` to
`round-14/`.

| Round | Workstreams | Why then |
|---|---|---|
| 10 · Adapt and iterate | (a) Where jobs and sign-ups came from, and the graduation report (§5.2) · (b) templates, Studio fields, my recipes with versions, attempts compared with costs (§5.3, §5.4) · (c) synchronous image responses in OpenAI's shape (§5.7), backend only | Builds on rounds 7–9. (a) needs one migration at most. (c) removes the first wall that code written by an AI assistant hits. |
| 11 · Your own inputs | `/v1/images/edits`, reference and first-frame images for video, Studio upload and "Animate this", recipe `inputs` slots, the staged policy (§5.5) | The largest content gap, and the key to cheap video iteration. Needs an operator policy decision first. |
| 12 · Batch and hand-over | Batch runs from a sheet, "Copy for your AI assistant", generated code, an n8n template, a capped key in one click (§5.6). OpenAI's video shape, `llms-full.txt`, OpenAPI (§5.7) | Graduation is measured from round 10, so its effect shows. |
| 13 · Reach | MCP server, open recipe list, model-launch routine, comparison pages (§5.7, §5.8) | Distribution, once the funnel converts. |
| 14 and later · Consumers and experts | Social features on published recipes · bring your own key · round 4 routing Phases 2–3 (custom policies, fallbacks for batches) · supported self-hosting (§5.12) | Each builds on rounds 10–13. The order among them is the operator's call. |
| Operator, any time | Catalog naming and freshness, China-hosted runs for Chinese recipes, `llms.txt` and home page wording (§5.9, §5.13), self-hosting documentation (§5.12) | No brief needed. |

## 7. Decisions for the operator

- ~~Developer-first or consumer-first.~~ Decided: aspiring builders (§1).
- **Policy for input images:** which stages, which account ages, and what
  confirmation users give (§5.5).
- **Whether image requests answer synchronously by default.** This changes
  the response for existing callers who expect `202`. Alternatively, keep
  `202` for existing API keys and make new keys synchronous (§5.7).
- **Limits for batches:** maximum rows, jobs at once, and whether the whole
  estimate is held up front (§5.6).
- **n8n templates under the Godance name,** and later a community node (§5.6).
- **Whether to start a separate public repository** for the recipe list
  (§5.8).
- **Whether Chinese recipes must carry a run** on a China-hosted model (§5.9).
- **Whether a published recipe can earn its author** a referral-style reward,
  and when (§5.11).
- **The order of the later rounds** for consumers and experts, and for bring
  your own key: how it is priced (§5.12).
- **For self-hosting:** what a self-hosted copy does about billing, and what
  support is offered (§5.12).

## Sources

- OpenNana gallery: https://opennana.com/awesome-prompt-gallery
- OpenNana source list: https://github.com/songguoxs/gpt4o-image-prompts
- Third-party OpenNana scraper (API endpoints): https://github.com/wuxiumu/ChiguaFun
- Chinese roundup of prompt sources: https://cloud.tencent.com/developer/article/2649367
- YouMind Nano Banana Pro list: https://github.com/YouMind-OpenLab/awesome-nano-banana-pro-prompts
- YouMind Seedance list: https://github.com/YouMind-OpenLab/awesome-seedance-2-prompts
- YouMind prompt library: https://youmind.com/prompts
- YouMind Seedance prompts: https://youmind.com/en-US/seedance-2-0-prompts
- MeiGen MCP: https://github.com/jau123/MeiGen-AI-Design-MCP
- MeiGen directory listing: https://www.tudingai.com/sites/4641.html
- EvoLink GPT Image 2 list: https://github.com/Evolink-AI/awesome-gpt-image-2-API-Prompts
- EvoLink model catalog: https://evolink.ai/models
- EvoLink lowest price guarantee: https://evolink.ai/lowest-price-guarantee
- EvoLink Seedance collection: https://evolink.ai/collections/seedance
- Civitai release notes (Creator Program changes): https://releasebot.io/updates/civitai
- Civitai creator compensation plan (June 2026 draft): https://hackmd.io/@civitai/r1F6wi-7Me
- OpenArt pricing overview: https://www.scopeful.org/tools/openart
- Jimeng (即梦): https://jimeng.jianying.com/
- n8n template, Seedance 2 via kie.ai: https://n8n.io/workflows/17438-generate-seedance-2-ai-videos-from-form-input-and-email-links-with-gmail
- n8n template, Seedance 1.5 Pro via kie.ai: https://n8n.io/workflows/12250-generate-video-from-an-image-with-bytedance-seedance-15-pro-via-kieai/
- n8n template, Google Sheets rows to videos via fal: https://n8n.io/workflows/5034-create-ai-generated-videos-4x-cheaper-than-veo3-with-google-sheets-and-falai/
- GPT Image 2 + Seedance 2.0 workflow (DEV): https://dev.to/evan-dong/gpt-image-2-seedance-20-a-practical-workflow-from-static-visuals-to-publishable-shorts-4p02
- GPT Image 2 + Seedance 2.0 storyboard-grid ad workflow: https://mer.vin/2026/06/gpt-image-2-seedance-2-0-4k-cinematic-ad-workflow-with-a-5x3-storyboard-grid/
- Chinese write-up of the same workflow (36Kr): https://eu.36kr.com/zh/p/3811398798237187
