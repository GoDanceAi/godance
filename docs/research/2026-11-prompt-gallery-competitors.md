# Prompt galleries: OpenNana and its competitors

**Date:** 2026-11-13 · **Context:** written after the round 9 briefs, to inform
what comes next. Rounds 7–9 are designed; on `main` only the round 8 recipe
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

1. **The prompt is a commodity.** Tens of thousands of prompts for the newest
   models are free, many under CC0 or CC BY. They are copied between lists, and
   the same prompts reach every gallery. Godance's round 8 sources are two of
   these lists, one maintained by YouMind and one by EvoLink.
2. **The run is not a commodity.** No competitor publishes, for each prompt,
   the exact API parameters, the cost, and outputs from several models. A
   multi-model API with a ledger can do that, and this is where the Godance
   gallery can stand apart. Without it, recipe pages copy their sources.
3. **About a quarter of popular prompts need an input.** In the four lists
   measured, 14–32% of image prompts and 31% of Seedance prompts need an
   uploaded image or clip. The round 8 import has to set these aside, because
   the API accepts text only. Most of them are about a person's likeness.
4. **This niche spreads through GitHub lists.** The lists that feed these
   galleries have 3.9k–17.4k stars, and every entry links back to the
   operator's site. MeiGen also puts its gallery inside ChatGPT, Claude and
   Cursor through a remote MCP server.
5. **The first gap after round 9 is measurement.** Round 8 notes it: nothing
   records whether a first run started from a recipe. Everything below
   depends on knowing that.

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
Godance: the Alicloud region cannot serve Google or OpenAI models (§5.6).

## 3. The field, and the top three

| Kind | Examples | Why it is or is not in the top three |
|---|---|---|
| Gallery only | **OpenNana**, aipromptnav, Promptfill, bananaprompts.org, nanobananaprompts.ai, awesomebananaprompts.com | OpenNana is the baseline. The rest are smaller SEO sites. |
| Gallery with generation | **YouMind**, **MeiGen**, OpenArt, Krea | YouMind is the largest. MeiGen is closest to what Godance is building. |
| API seller with galleries | **EvoLink**, Atlas Cloud, WaveSpeed, kie.ai | EvoLink is the clearest case, and the most like Godance. |
| The model maker's own app | 即梦 Jimeng and Dreamina (ByteDance) | Native Seedance with an inspiration feed. It sets what users expect ("see it, make the same"), but no third party can match first-party access. |
| Older Stable Diffusion and Midjourney communities | Civitai, PromptHero, Lexica | Their creator economy is useful background for referrals (§5.8). They are not where people look for prompts for the newest models. |

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

Each item gives the evidence, then what to change.

### 5.1 Measure the funnel first

Round 8 says: "Not covered by any brief yet: measuring how many first runs
start from a recipe." None of the competitors' choices can be judged for
Godance without this.

- When the Studio loads `?example=<slug>`, the job it submits should carry
  where it came from (`studio`, `recipe:<slug>` or `api`). This could be a
  small nullable column, or a field in the request the Studio sets.
- Record the sign-up's first-touch source (UTM and recipe slug) the way round
  9 stores a referral code. Report recipe → sign-up → first run → first
  top-up.
- This is cheap and should come first.

### 5.2 Make the run the product, not the prompt

The same prompts appear on youmind.com and evolink.ai, which have far more
backlinks. A Godance recipe page offers the reader something new only through
what Godance adds: its own outputs, the parameters, the cost, and other
models.

- **Developer tab on every recipe.** Generate `curl`, an OpenAI SDK call and a
  Java `WebClient` call from `run.params`, with the cost and a link to the API
  docs. Developers want this, and consumer galleries do not offer it.
- **The same prompt on several models.** The schema already allows several
  runs. Re-run the best recipes on `gpt-image-2`, `nano-banana`,
  `seedream-5.0`, `qwen-image-2.0` and `flux-2-pro`. Publish comparison pages
  ("GPT Image 2 vs Nano Banana Pro: 20 prompts, outputs and costs"). Only a
  multi-model API can make these cheaply: about 20 × 5 × 6 ¢ ≈ 6 USD for an
  image set.
- **A routine for model launches.** When a model enters the catalog, re-run
  the top recipes on it within 48 hours and publish a comparison. That is
  when search interest is highest. EvoLink's early-access pages show there is
  demand to capture.
- **Stay small and checked.** Do not try to match 24k entries. Review before
  publishing is the right call; volume is the incumbents' game.

### 5.3 Keep templates as templates

20–47% of entries have variables. Promptfill, a Chinese tool listed among
the places Chinese users go for prompts, does nothing but fill in prompt
templates.

- Keep the template next to the resolved text. For example, a
  `prompt.template` with `{{name}}` markers alongside the existing
  `prompt.variables`. This edits the schema module, which round 8 froze, so
  it belongs to a later round.
- The Studio shows one field per variable, filled with the defaults. "Run
  this" stays one click; the user can change the product name, brand or
  language. This suits e-commerce and marketing work: e-commerce, ad and UI
  mockup cases are 101 of the 462 in the GPT Image 2 list.

### 5.4 Accept input images and clips

This is the largest capability gap: 21–32% of image prompts and 31% of
Seedance prompts are currently set aside. Every competitor that generates
accepts inputs.

- **API.** Add `POST /v1/images/edits` in OpenAI's multipart shape (`image`,
  or several `image[]`, plus optional `mask`, `prompt`, `model`, `n`,
  `size`). In WebFlux that is
  `@PostMapping(consumes = MULTIPART_FORM_DATA_VALUE)` with
  `@RequestPart("image") Flux<FilePart>`. For video, add reference and
  first-frame images where the adapter supports them (Seedance 2.0, Sora 2,
  Kling 3.0, Veo 3.1).
- **Storage and retention.** Inputs go to the existing object storage under
  the job, and are deleted with it through `DataRetentionService`. Saved jobs
  from round 9 keep them.
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

### 5.5 Go where this niche spreads: GitHub and agents

- **An open list Godance maintains.** Publish approved recipes as a separate
  repository whose README is generated from `marketing/src/content/gallery`
  by CI, in English and Chinese. Each entry gives the model, parameters, cost
  and a "Run this" link with UTM tags. The pitch none of the 3.9k–17.4k-star
  lists can make: *every example was generated through the API with these
  exact parameters, for this price.* Attribution and licences carry over from
  the recipe's `source`.
- **An MCP server.** Tools such as `search_recipes`, `list_models`,
  `estimate_cost` (the round 7 estimates endpoint), `generate_image`,
  `generate_video` and `get_job`, over the API Godance already has.
  - The Spring AI MCP server starter has a WebFlux transport; check that it
    works with Boot 4.1 before choosing it.
  - Start with API-key authentication.
  - Let `SpendingLimitService` cap what an agent can spend, and have the
    tool descriptions ask for an estimate before generating.

### 5.6 Models and regions

- **Naming.** Catalog id `nano-banana` maps to `gemini-3-pro-image-preview`,
  which the market calls *Nano Banana Pro*, while "Nano Banana" usually means
  the earlier Flash model. Add a `nano-banana-pro` alias, and consider adding
  the cheaper Nano Banana 2, which OpenNana and MeiGen feature. Check the
  upstream id and price first.
- **Freshness.** Check GPT Image 2.5 and Seedance 2.5 against the providers.
  Gallery traffic follows the newest model.
- **Chinese audience.**
  - OpenNana's audience mostly uses Nano Banana Pro. The Alicloud region's
    allowlist excludes Google and OpenAI, and those providers' own terms
    limit where their users can be. These are separate questions; the
    operator should answer both.
  - In practice: every recipe shown in Chinese should carry at least one run
    on a China-hosted model (`seedream-5.0`, `qwen-image-2.0`,
    `seedance-2.0`, `kling-3.0`). "Run this" should pick a run whose model is
    listed in `/v1/models` for the user.

### 5.7 Sources and licences

- OpenNana's list has no licence, so it is out, correctly.
- The CC0 and CC BY lists collect posts written by X users. A list's licence
  covers the compiler's own work; it cannot grant rights the individual
  authors never gave. Round 8 already credits each author, keeps the original
  post URL, offers a removal contact and keeps a removal list. The content
  standard should also say so: a list licence is necessary but not
  sufficient.
- **A cleaner source over time:** creators submit recipes directly and grant a
  licence. OpenNana has a `submit.html` page, and YouMind accepts pull
  requests.

### 5.8 Referrals and creators

- Civitai gave resource creators 25% of generation spend and is now
  reworking that scheme. YouMind and EvoLink credit authors but do not pay
  them.
- Once round 9's referral has proved itself, a recipe can act as a referral
  link: a top-up that starts from a creator's recipe credits the creator. It
  would reuse the `REFERRAL` ledger source, idempotency keys and monthly cap.
- Not yet: card disputes are not handled, so paying creators would make the
  exposure round 9 already notes larger.

### 5.9 Positioning

`marketing/public/llms.txt` says Godance is "an OpenAI-compatible developer
API, not an end-user product". Rounds 7–9 add a Studio, a gallery, signup
credit, referrals and saved generations.

- The competitors that last pick one main side: EvoLink is a developer API
  with content marketing; YouMind is a consumer workspace. MeiGen serves both
  through its API and MCP.
- The research favours developer-first for Godance: reproducible recipes,
  code, cost and MCP. The Studio is where people try a model before they
  integrate it.
- Either way, update `llms.txt` and the home page when the Studio ships, so
  answer engines describe the product correctly.

## 6. A possible order after round 9

| Round | Work | Why then |
|---|---|---|
| 10 | (a) Where jobs and sign-ups came from (§5.1) · (b) developer tab on recipe pages (§5.2) · (c) templates in the schema and Studio fields (§5.3) · (d) a fixed set of categories in three groups, like YouMind's, validated by the schema | Small and mostly in marketing and the app. (a) needs one migration at most. Builds on round 8's data. |
| 11 | Inputs: `/v1/images/edits`, video reference images, Studio upload, recipe `inputs` slots, the staged policy (§5.4) | The largest content gap. Needs an operator policy decision first. |
| 12 | Open list repository generated by CI · MCP server · routine for model launches · comparison pages (§5.2, §5.5) | Distribution, once (a) can measure it. |
| Operator, any time | Catalog naming and freshness, China-hosted runs for Chinese recipes, `llms.txt` positioning (§5.6, §5.9) | No brief needed. |

## 7. Decisions for the operator

- Developer-first or consumer-first for the gallery (§5.9).
- Policy for input images: which stages, which account ages, and what
  confirmation users give (§5.4).
- Whether to start a separate public repository for the recipe list (§5.5).
- Whether Chinese recipes must carry a run on a China-hosted model (§5.6).
- Whether a recipe can earn its creator a referral-style reward, and when
  (§5.8).

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
