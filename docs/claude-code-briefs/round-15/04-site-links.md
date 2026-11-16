# 04 — Site discovery, links and documentation

## Outcome

Provide a launch-ready public path from discovering a workflow to using the existing app or developer tools. Preserve the all-off marketing build. Gallery/community require real content and their existing gates; this work does not publish invented examples or activate production capabilities.

Read `00-coordination.md`, `session-prompts.md`, `marketing/src/components/{Nav,Footer}.astro`, `pages/{index,pricing}.astro`, `pages/docs/index.astro`, `lib/{gallery-config,gallery-recipes,community}.ts`, the gallery/community loaders and tests, `marketing/README.md`, `.github/workflows/deploy-frontends.yml`, and round 8/13/14 launch requirements.

## One opt-in discovery flag

Add `PUBLIC_SITE_DISCOVERY_ENABLED`, a public build-time boolean. Missing/empty is false; explicit true/false is accepted; malformed values fail with an actionable configuration error. Do not add a backend default or change existing backend flags.

When false, no new shell/home/product pages are emitted. Existing gallery/community behavior stays exactly as on B0. When true:

- Desktop and mobile share accessible destination groups: Create, Explore (only available Gallery/Comparisons/Community plus Models), Developers (Docs, MCP, n8n, SDK compatibility/self-hosting), Pricing.
- Refresh the homepage around explore → create → compare → save a recipe → batch/code/assistant. Give creators and developers clear choices. Correct async-only and unsupported-audio claims in this opt-in homepage.
- Add **two** substantive product pages: `/create` explains Studio, recipes, history/comparison and batching; `/integrations` explains API, generated code/assistant prompts, n8n and MCP. Use clear text and interface diagrams; no fabricated testimonials, example authors, media outcomes or screenshots presented as real results.
- Core app CTAs use the configured `PUBLIC_APP_URL` and actual existing destinations. Preserve sign-in return behavior. Compare guidance starts from History selection; no made-up job IDs or nonexistent routing editor.
- Remove the misleading enterprise-only BYOK claim from the opt-in pricing copy and direct readers to the conditional documentation. The new product pages omit Inputs, BYOK and user routing promotions; describe those features only in documentation, conditionally on operator/account enablement. Do not invent readiness claims.

Do not multiply product pages in this round. Focused Studio/recipe/batch pages can follow evidence of demand.

## Existing gates remain binding

Use `loadGallery().on` for gallery navigation and the existing runThis rule for Run this. The collection currently requires media base, removal contact and nonempty valid content; licensing/NOTICE and real-media approval are also launch prerequisites even where not part of the runtime boolean.

Use `loadCommunity().on` for community navigation: backend/build enablement plus readable approved public content. Empty responses/errors produce no community pages or links. Comparison links require actual emitted pairs; the default minimum shared runs still applies.

The discovery flag cannot bypass any of these gates. Do not publish QA gallery fixtures, approve community submissions, change moderation, manufacture missing content, or make real paid generations.

## Documentation available independently

With discovery off, accurate developer documentation may still change. Allowed default-build exceptions are exactly:

- `/docs/index.html` for corrected contents navigation and current API/workflow coverage;
- `/llms.txt` and `/llms-full.txt` for matching factual corrections;
- documentation-only new routes and their exact sitemap additions, if justified and listed before implementation (prefer updating the existing docs page).

Add accessible contents links and concise coverage for existing Studio/Recipes/Batches entry points, MCP/n8n, BYOK, community publishing/profile/follow routes, user routing API and the repository self-hosting guide. Distinguish available contracts from deployment activation. Do not claim audio, embeddings, video BYOK or unsupported video operations are live.

Home, pricing, footer and mobile shell changes are discovery-on only. Do not silently broaden the default exception list to make parity pass.

## Build/deployment wiring

Marketing is built in GitHub Actions and Wrangler uploads the resulting dist. Cloudflare dashboard environment values alone do not configure that earlier build.

Map repository variables into the marketing build step for the new flag and existing necessary public gallery/community values, preserving current empty/off defaults. Keep `GALLERY_CONTENT_DIR` as a local/test override. No secret values, private paths or admin credentials. Document exactly which build variables an operator must set; do not set production variables or deploy.

Own `deploy-frontends.yml` marketing wiring only; avoid unrelated CI changes. Add local checks if CI does not execute marketing Node tests. Prefer existing tools/native disclosure navigation over new runtime dependencies.

## Default-output proof

Build B0 and final with the same Node 22/npm lockfile, locale/timezone/year and deterministic model-catalog response (the same local fixture server or the same enforced offline fallback). All optional build/repository variables are unset in this case.

Compare complete sorted output path sets and every emitted legacy byte, including shared CSS/JS, hash filenames, redirects, headers, robots and old sitemap entries. No new non-documentation assets may appear in an off build. Only the named documentation additions/changes and their exact new-doc sitemap entries may differ. Do not hide changes by stripping arbitrary timestamps, formatting, asset hashes or all CSS. Record the raw build diff and explain the precise allowed changes.

Tailwind scans even disabled component markup; new utilities can change shared CSS and every old page. Isolate opt-in assets/components and reuse existing utilities where possible. Conditional HTML alone is not proof of default parity.

## Acceptance and evidence

1. Discovery-off output matches the default contract. The report enumerates every differing path and checks it against the allowlist.
2. Discovery-on yields `/create` and `/integrations`, truthful homepage/pricing, metadata/canonical/sitemap entries, and working desktop/mobile navigation in light/dark themes.
3. Keyboard/focus/screen-reader behavior is usable; every visible internal link targets an emitted route. No mobile horizontal page overflow or disappeared navigation.
4. Test gallery off/empty/available and its existing validation failures: malformed gallery content/configuration must still fail the build. Separately test community off/empty/available/API-failed, where API failure omits community output. Test comparison available/unavailable and Run this separately disabled/enabled. No dead links, fixture publication or weakening of existing validation.
5. App CTAs use `PUBLIC_APP_URL`, documented routes and safe sign-in return destinations. No generation/payment begins from a marketing click alone.
6. Public docs contents reach the added subjects and match existing backend contracts, including unsupported/conditional capabilities.
7. Run marketing lint/build/Node tests in default and explicit enabled configurations, plus browser inspection. Include representative desktop/mobile light/dark screenshots and the build-output diff.
8. Deliver an operator activation checklist: approve real content/media/licence/contact, confirm backend/account availability, configure build variables, verify signed-out/signed-in handoffs and smoke checks, then enable the discovery build after operator merge/deploy. Do not perform activation in this session.

Opening move: capture the deterministic B0 all-off output manifest and list the exact doc exceptions, then post the plan and flag/route truth table. Write a failing on/off navigation/page test before editing the shell.
