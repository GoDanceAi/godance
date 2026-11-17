# Godance rounds 9–15 test cases

Nov 16, 2026 · @Mr. Tiger

Run these 259 cases on staging that includes rounds 9–15. Round 15 was merged on Nov 16, 2026, in this order: [admin #65](https://github.com/GoDanceAi/godance/pull/65), [site #67](https://github.com/GoDanceAi/godance/pull/67), [navigation #68](https://github.com/GoDanceAi/godance/pull/68), [billing #66](https://github.com/GoDanceAi/godance/pull/66), [chat #69](https://github.com/GoDanceAi/godance/pull/69). Integration reference: `22b4f47bb89cf91c6325e5237c8c7199ccea4c33`. Each row is one check with steps and an expected result: set Result as you go and put the bug link in Notes. Repository review and automated CI do not count as running these staging cases; all results remain **Not run** until performed.

## How to run

Record the staging build before running: `GET $API/healthz` shows the API commit; also record the app and marketing build commits, which may differ after frontend-only changes. Confirm each surface contains its relevant Round 15 changes, and record the switches used. `$API`, `$APP` and `$SITE` stand for the staging API, app and marketing site. Priority: **P0** blocks release (money, safety, security), **P1** is core behaviour, **P2** is edge or polish. Cases marked *dev-assisted* need a developer for time travel, request fault injection, fixture inspection or log access.

Keep production unchanged. Its public rollout variables `PUBLIC_SITE_DISCOVERY_ENABLED`, `COMMUNITY_ENABLED` and `GALLERY_RUN_THIS` remain `false`; the test matrix below is for isolated staging/local builds. Round 15 adds no backend activation switch. It adds navigation and UI over existing APIs, plus the read-only ADMIN endpoint. Do not interpret a configured-on admin status as user eligibility, approved content, provider health or a marketing deployment.

### Test accounts

| Account | Setup | Used for |
| --- | --- | --- |
| A1 | Adult, verified, 20 USD credit | Most cases; referrer |
| A2 | New adult sign-up during the run | Referee, first touch, fresh-account cases |
| A3 | Under-16 account with parental consent | Age refusals; ephemeral chat with no saved-history calls |
| A4 | Second adult with credit; different recipes, bookmarks and saved chats from A1 | Ownership and account-switch checks |
| A5 | Account awaiting parental consent; an unknown-age fixture for chat | Waiting screen; no saved-history controls or calls |
| Staff | Active, verified accounts with SUPPORT, FINANCE and ADMIN roles and 2FA on; dev-assisted inactive/unverified ADMIN and staff-without-2FA fixtures | Admin funnel, moderation, margin view; ADMIN-only feature status and denial states |

API keys on A1: `$KEY_ASYNC` (image response async), `$KEY_SYNC` (explicitly set `image_response: "sync"`), `$KEY_CAP` (daily cap 100 cents).

### Switches to set on staging

Use only staging configuration changes needed by a case; record and restore every non-default setting. Backend runtime settings and marketing build variables are separate. MCP is configured on by default; do not treat every status as default-off.

| Setting | Ships as | Set for testing |
| --- | --- | --- |
| `PROMO_REFERRAL_REFERRER_REWARD_CENTS`, `PROMO_REFERRAL_REFEREE_REWARD_CENTS` | 0 | 100 each (round 9) |
| `RETENTION_SAVED_LIMIT` | 500 | 2 for the limit case only |
| `IMAGES_NEW_KEY_RESPONSE`, `IMAGES_SYNC_WAIT` | async, 90s | keep async; 90s except 5s for R10-IMG-05, then restore 90s |
| `INPUTS_ENABLED`, `INPUTS_ALLOW_PEOPLE`, `INPUTS_CHECK_MODEL` | false, false, empty | true, false, a vision model the region allows (round 11 on) |
| `BATCHES_MAX_ROWS`, `BATCHES_MAX_UNFINISHED` | 500, 2 | defaults |
| `PUBLIC_SITE_DISCOVERY_ENABLED` (marketing build only) | false when unset/empty | off build first; separate explicit true build for R15-SIT; malformed-value local build case |
| Gallery: `GALLERY_MEDIA_BASE_URL`, `GALLERY_REMOVAL_CONTACT`, `GALLERY_OUTPUT_LICENCE`, `GALLERY_RUN_THIS` | empty, empty, empty, false | staging media base, removal contact and output licence; Run this true |
| Open list: repo variable `OPEN_LIST_REPO`, secret `OPEN_LIST_DEPLOY_KEY` | unset | set only for R13-LST-05 |
| Community: `COMMUNITY_ENABLED` (API runtime and marketing build) | false in both | true in both for publishing and public pages |
| BYOK: `BYOK_PROVIDERS` (`godance.byok.providers`) | empty | one supported provider allowed in the staging region, such as `OPENAI` |
| Routing: `ROUTING_USER_POLICIES_ENABLED`, `ROUTING_USER_POLICIES_ALLOW_LIST` | false, empty (all accounts once enabled) | true; empty or the test accounts’ email addresses |
| MCP: bound property `godance.mcp.enabled` | true | read actual staging configuration; developer may set false in an isolated alternate-configuration test; no new `MCP_ENABLED` environment wiring |

For dedicated staging builds, supply `VITE_API_URL=$API` and `VITE_GALLERY_URL=$SITE` to the app, and `PUBLIC_API_BASE=$API` and `PUBLIC_APP_URL=$APP` to marketing. Otherwise gallery/public-index reads can target the production site by default. The production GitHub workflow maps public marketing repository variables in `.github/workflows/deploy-frontends.yml`; use that wiring as a reference for the separate staging build, without changing production variables. Cloudflare dashboard values alone do not configure an earlier GitHub build. Keep local fixture paths such as `GALLERY_CONTENT_DIR` local; never publish QA content as a real gallery or approve submissions just to satisfy discovery checks.

`PUBLIC_SITE_DISCOVERY_ENABLED` gates the new marketing shell/home and `/create` + `/integrations`; it does not turn on Gallery, Community, comparisons, Run this, or backend capabilities. Community can be published with discovery off when its own runtime/build gates and approved content permit it. App Following additionally needs account permission; its empty-state Community CTA requires a readable public index. Hosting-provided CORS matters independently of discovery: test the actual staging host, not just emitted `_headers` rules.

### Test data

- Images: a product PNG on a plain background, a JPEG with GPS EXIF, a WebP, a GIF renamed to `.png`, a file over 20 MB, an image over 40 megapixels, and a photo of a consenting staff member.
- Video frames: one 1280x720 and one 720x1280 still.
- CSV files for batches: 3 rows; quoted fields with commas and line breaks; a UTF-8 byte-order mark; 501 rows; an `.xlsx` workbook.
- Payments: Stripe test cards, NOWPayments sandbox.
- Round 15: synthetic recent usage with a distinctive internal `providerCostCents`, saved-card/no-card and auto-recharge failure fixtures, completed/interrupted chat answers, multiple saved conversations, and a 255-character unbroken chat title. Inject slow, denied, failed and lost-response cases through a staging-only test harness/proxy; do not alter production or log transcripts/credentials.
- Site builds: all optional variables unset for deterministic legacy parity; separate discovery-on builds with Gallery/Community off, empty, available and failed. Use the same controlled catalog response, Node/npm/lockfile, locale, timezone and year for both sides of the parity comparison.

### Tools

curl; the OpenAI Python SDK (`pip install openai`); MCP Inspector or Claude Code as an MCP client; n8n in Docker with a Google Sheet; `exiftool`; a desktop browser plus mobile emulation, in light and dark, including 320px width; keyboard/screen reader and network inspection. Use a staging-only request harness for fault injection and the existing build/parity evidence for developer-assisted comparisons.

Round 15 cases use synthetic model responses and sandbox/stubbed payment/configuration writes. They do not require a real charge, saved live card or paid generation. The older generation/payment cases require a separately agreed staging budget and sandbox setup. A checkout return URL is never evidence that payment succeeded.

### Recording results

Set **Result** on each row. For Fail, put the bug link and what you saw in Notes. For Blocked, name the unavailable build, fixture, host, role, sandbox or developer prerequisite. Record account, build and configuration for each variant; capture only synthetic/redacted screenshots. For request-safety checks, record method/path/count and outcome without tokens or chat text. Run affected Round 15 UI on desktop/mobile and light/dark, including keyboard/focus and sign-in return paths.

## Round 9: referral and saved generations

Referral credits both sides once, on the invited user's first qualifying top-up; saved generations are kept past the 90-day deletion. Set the referral rewards to 100 cents each after R9-REF-01.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R9-REF-01 | P0 | Off by default | With both rewards at 0, A1 opens `$APP`; `GET $API/v1/referrals/me`; open `/referral` directly | `enabled: false` and no other fields; no Invite nav entry; direct route returns to Dashboard after discovery resolves; a claim is refused | Not run |  |
| R9-REF-02 | P1 | Link and terms | Rewards on; A1 opens `/referral` | Link `$APP/signup?ref=<8-char code>`, Copy works, both rewards, minimum top-up, terms; counts at 0 | Not run |  |
| R9-REF-03 | P0 | Claim after password sign-up | Open A1's link in a private window; sign up A2 with a password; verify email | Claim sent once after sign-in; Billing shows A2's pending reward and the qualifying top-up | Not run |  |
| R9-REF-04 | P1 | Claim after social sign-in | Open the link; sign up with Google | Claimed after the OAuth round trip | Not run |  |
| R9-REF-05 | P1 | Claim in another tab | Open the link, then complete sign-up from the email in a new tab | Claimed once; stored code cleared | Not run |  |
| R9-REF-06 | P0 | Reward on first qualifying top-up | A2 tops up 10 USD with a Stripe test card | A1 and A2 each get exactly 100 cents, ledger source `REFERRAL`; A1's page shows rewarded 1 | Not run |  |
| R9-REF-07 | P0 | No double reward | Resend that Stripe webhook from the Stripe dashboard | Balances and ledger unchanged | Not run |  |
| R9-REF-08 | P1 | Below the minimum | New referee tops up 5 USD, then 10 USD | Nothing on 5 USD; rewarded once on 10 USD | Not run |  |
| R9-REF-09 | P1 | USDT does not qualify | Referee tops up with NOWPayments sandbox | No reward (default sources = card only) | Not run |  |
| R9-REF-10 | P1 | Claim refusals | Claim own code; an unknown code; from an account older than 168 h; after a paid top-up; from A3 | `referral_not_eligible` or `referral_code_unknown`, problem+json, no ledger row | Not run |  |
| R9-REF-11 | P1 | Claim rate limit | Six claims in an hour from one account | The sixth answers 429 | Not run |  |
| R9-REF-12 | P0 | Referrer privacy | Compare A1's page and API response after two sign-ups | Counts only, no name, email or id | Not run |  |
| R9-REF-13 | P1 | Session only | Call both referral routes with `$KEY_ASYNC` | 403 | Not run |  |
| R9-REF-14 | P2 | Recharge and consent do not reward | Referee's first top-up is an auto-recharge; a parent pays a consent payment | No reward either way | Not run | dev-assisted |
| R9-REF-15 | P2 | Monthly limit | Set the limit to 1; two referees qualify | Second referee credited, referrer not | Not run |  |
| R9-SAV-01 | P0 | Save from three places | Save a finished job from a History card, the job page and the Studio result | Saved state shows; job reads `saved: true`, `mediaExpiresAt: null`, "kept" instead of a date | Not run |  |
| R9-SAV-02 | P1 | Un-save | Un-save a job under 90 days old | Deletion date returns; no confirmation needed | Not run |  |
| R9-SAV-03 | P1 | Saved view | History, Saved filter; `GET $API/v1/jobs?saved=true` and `GET $API/v1/jobs/saved` | Only saved jobs, newest first, paging works; count against the limit shown | Not run |  |
| R9-SAV-04 | P1 | Cannot save failed or purged | Look for the control on a failed job; `PUT /v1/jobs/{id}/saved` on it | No control; 409 `job_not_saveable` | Not run |  |
| R9-SAV-05 | P1 | Limit reached | Limit 2; save a third | 409 `saved_limit_reached`; app says so and links the saved view | Not run |  |
| R9-SAV-06 | P2 | Idempotent | Save twice; un-save a job that is not saved | 204 each, nothing changes | Not run |  |
| R9-SAV-07 | P0 | Another user's job | A4 saves and un-saves A1's job id | 404 both | Not run |  |
| R9-SAV-08 | P0 | Survives the 90-day purge | Age a saved job past 90 days; run the nightly purge | Files, prompt and outputs intact | Not run | dev-assisted |
| R9-SAV-09 | P1 | Un-save an old job | Un-save a job past 90 days | Confirmation warns of deletion; purged at the next run | Not run | dev-assisted |
| R9-SAV-10 | P0 | Account closure | Close an account that has saved jobs | Saved jobs and their files deleted | Not run |  |

## Round 10: adapt and iterate

Jobs and sign-ups record where they came from, gallery recipes keep their variables, users keep their own recipes, and an API key can get image results in OpenAI's shape.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R10-ORG-01 | P0 | Channel set by the server | Make an image job in the Studio, then one with `$KEY_ASYNC`; read both jobs | `channel: "STUDIO"` and `"API"`; a `channel` field sent in the request is ignored | Not run |  |
| R10-ORG-02 | P1 | Gallery recipe from the Studio | Open `/create/image?example=<slug>`, submit; then edit the prompt and submit again | First job has `galleryRecipe: <slug>`; the edited one has none | Not run |  |
| R10-ORG-03 | P1 | Bad gallery\_recipe | API request with `"gallery_recipe": "Bad Slug!"` | 400 `invalid_gallery_recipe`, no ledger row | Not run |  |
| R10-ORG-04 | P1 | Pasted recipe example | Run the curl from a gallery recipe page | Job records that slug | Not run |  |
| R10-ORG-05 | P1 | First touch | Open `$APP/?utm_source=x&utm_campaign=t1` signed out; sign up fresh A2 and at least four other fresh accounts through that URL. Select a reporting range covering these sign-ups, with x among its top 10 campaign sources; keep A2 for R10-ORG-06 | One `POST /v1/attribution/first-touch` per account; stored values cleared; funnel shows at least 5 sign-ups under `utm:x`, including A2 | Not run |  |
| R10-ORG-06 | P1 | First touch is write-once | Visit again with `utm_source=y` and sign in as A2 | Source stays `x` | Not run |  |
| R10-ORG-07 | P1 | First touch after social sign-in | Repeat R10-ORG-05 with Google sign-up | Claimed after the OAuth round trip | Not run |  |
| R10-ORG-08 | P1 | Admin funnel | Staff opens Admin, Overview, funnel, picks a range | Cohort table by ISO week, source table, top recipes, percentages beside counts | Not run |  |
| R10-ORG-09 | P0 | Funnel privacy | Inspect `GET $API/v1/admin/funnel` | Counts only; no user id, email or name; buckets under 5 users left out | Not run |  |
| R10-ORG-10 | P1 | Funnel access | Call the funnel as USER, as each staff role, and with an API key | USER and API key 403; SUPPORT, FINANCE, ADMIN 200 | Not run |  |
| R10-TPL-01 | P1 | Template on a recipe page | Open a gallery recipe that has variables on `$SITE` | Template with variable chips, defaults table, Copy (resolved) and Copy template | Not run |  |
| R10-TPL-02 | P1 | Template in JSON | `GET $SITE/gallery/<slug>.json` and `/gallery/index.json` | `prompt.template` and `prompt.variables` present; index has `has_variables: true` | Not run |  |
| R10-TPL-03 | P1 | Variables in the Studio | Run this on a template recipe; change a variable | One field per variable with defaults; preview updates; submitted prompt uses the new value | Not run |  |
| R10-TPL-04 | P2 | Chinese template | Switch the recipe to Chinese in the Studio | Chinese defaults used; text marked `zh-Hans` | Not run |  |
| R10-REC-01 | P0 | Save as recipe | From a Studio result, Save as recipe | Appears in `/recipes` with version 1, model and params | Not run |  |
| R10-REC-02 | P1 | New version only on change | Open the recipe, save as new version with no change, then with a change | First is a no-op; second makes version 2 | Not run |  |
| R10-REC-03 | P1 | Text into a variable | In the template editor select a word and make it a variable | `{{name}}` in the template, the word as default | Not run |  |
| R10-REC-04 | P0 | Run a recipe | Run with a changed variable | Job prompt is the rendered text; job shows `recipe` id, version and values; price same as the plain prompt | Not run |  |
| R10-REC-05 | P1 | Attempts and spend | Run the recipe three times | Recipe page lists the attempts with cost each; total equals the sum of the jobs' costs | Not run |  |
| R10-REC-06 | P1 | Compare | History, Select, pick 3 jobs (one purged), Compare | `/compare` shows outputs, model, params, values, prompt differences, cost; the purged one says its files were deleted and keeps its cost | Not run |  |
| R10-REC-07 | P1 | Template off | Edit the prompt text directly on a template recipe | Confirmation; fields disappear; plain prompt submitted | Not run |  |
| R10-REC-08 | P1 | Recipe request errors | API: `prompt` with `recipe`; an unknown variable; a video recipe on the image route; A4's recipe | 400 `prompt_and_recipe`, `recipe_variable_unknown`, `recipe_modality_mismatch`; 404 `recipe_not_found`; no ledger row | Not run |  |
| R10-REC-09 | P0 | Recipes are private | A4 reads, lists, renames and deletes A1's recipe | 404 each; A1's recipe unchanged | Not run |  |
| R10-REC-10 | P1 | Delete a recipe | Delete a recipe with attempts | Recipe gone; its jobs still readable, unlinked | Not run |  |
| R10-REC-11 | P2 | Recipe limit | Set the limit to 1; create a second recipe | 409 `recipe_limit_reached` | Not run |  |
| R10-REC-12 | P2 | Pre-fill order | Open the Studio with `?job=`, `?recipe=` and `?example=` together, then drop each | `job` wins, then `recipe`, then `example` | Not run |  |
| R10-REC-13 | P2 | Navigation on mobile | Open Menu in mobile emulation; follow each permitted group and destination | Same core destinations as desktop, labelled groups and current-page state; no horizontal page overflow; see R15-NAV for keyboard/gate checks | Not run |  |
| R10-IMG-01 | P0 | Async by default | New key with no setting; image request | 202 with `job_id` and `poll_url` | Not run |  |
| R10-IMG-02 | P0 | OpenAI SDK with a sync key | `client.images.generate(model="gpt-image-2", prompt=...)` with `base_url=$API/v1` and `$KEY_SYNC`; read `data[0]` | 200 with OpenAI's body (`url` or `b64_json`) plus `job_id`, `cost_cents` | Not run |  |
| R10-IMG-03 | P1 | b64\_json | Same with `response_format="b64_json"` | Base64 image decodes; above 25 MB falls back to URLs | Not run |  |
| R10-IMG-04 | P1 | Overrides | Sync key with `async: true`; then with header `Prefer: respond-async` | 202 both | Not run |  |
| R10-IMG-05 | P1 | Wait times out | Set `IMAGES_SYNC_WAIT=5s`; request a slow model with `$KEY_SYNC`; restore `IMAGES_SYNC_WAIT=90s` after the case | 202 with `Retry-After: 5`; job finishes and is pollable | Not run |  |
| R10-IMG-06 | P0 | Failure refunds | Sync request that the provider fails (bad size, or a test model that fails) | 502 `generation_failed`; full refund in the ledger | Not run |  |
| R10-IMG-07 | P1 | Per-key setting | API keys page: change a key's image response; `PATCH /v1/keys/{id}` with an API key | Setting saved and shown; PATCH with an API key is 403 | Not run |  |

## Round 11: your own inputs

Users can bring their own images into image and video generation, under a policy that fails closed. Run R11-INP-01 and R11-STU-01 with `INPUTS_ENABLED=false` first, then switch inputs on for the rest.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R11-INP-01 | P0 | Off by default | Inputs disabled; `POST $API/v1/files` with an image; open Settings and the Studio | 403 `inputs_disabled`; no Inputs section, no upload slots | Not run |  |
| R11-INP-02 | P0 | Terms first | Inputs on; A1 uploads before accepting, then accepts in Settings, Inputs | 403 `inputs_terms_required`, then upload works; `/v1/auth/me` shows `inputs.accepted: true` | Not run |  |
| R11-INP-03 | P0 | Adults only | A3 tries to upload | 403 `inputs_not_allowed_for_account`; app says inputs are not available, no control | Not run |  |
| R11-INP-04 | P0 | Upload a product photo | Upload the product PNG | 201 with id, bytes, `content_type`, width, height, `contains_person: false`, `expires_at` set | Not run |  |
| R11-INP-05 | P0 | Location data stripped | Upload the GPS JPEG; download `/v1/files/{id}/content`; run `exiftool` on it | No GPS, EXIF or XMP; image still opens | Not run |  |
| R11-INP-06 | P1 | Type from content | Upload the GIF renamed `.png` | 415 `input_type_unsupported` | Not run |  |
| R11-INP-07 | P1 | Size limits | Upload the 20 MB+ file and the 40 MP+ image | 413 `input_too_large`; the large image refused | Not run |  |
| R11-INP-08 | P0 | People refused | `allow-people` false; upload the staff photo | 422 `input_person_not_allowed` | Not run |  |
| R11-INP-09 | P0 | Check model missing fails closed | Clear `INPUTS_CHECK_MODEL`, people still refused; upload | 503 `input_check_unavailable` | Not run |  |
| R11-INP-10 | P0 | Ownership | A4 reads, downloads and deletes A1's file, and uses it in a generation | 404 `file_not_found` each; no ledger row | Not run |  |
| R11-INP-11 | P1 | Unused files expire | Upload, use nothing; wait past `INPUTS_UNUSED_TTL_HOURS` and the cleanup | File gone; a used file has `expires_at: null` | Not run | dev-assisted |
| R11-INP-12 | P1 | Delete a used file | Delete a file a job used; read the job | Job's input shows with `url: null` | Not run |  |
| R11-INP-13 | P1 | Upload limits | 61 uploads in an hour; 201 stored files | 429; then `input_limit_reached` | Not run |  |
| R11-CHAT-01 | P1 | Image in chat | `/v1/chat/completions` with a data-URL image part to a vision model | Answer describes the image; usage counts image tokens; charge matches usage | Not run |  |
| R11-CHAT-02 | P0 | URL fetching is safe | Image part with a public `https` URL; then `http://169.254.169.254/` and a `10.x` address | Public one works; private ones refused (`invalid_image_url` or `image_fetch_failed`), no charge | Not run |  |
| R11-CHAT-03 | P1 | Own file in chat | Image part with `$API/v1/files/{id}/content`; then A4's file id | Own file works; A4's is 404 | Not run |  |
| R11-CHAT-04 | P1 | Model without vision | Image part to a text-only model | 400 `model_does_not_accept_images`, no charge | Not run |  |
| R11-CHAT-05 | P0 | Stateless chat contract unchanged | Call string-content chat with streaming on/off; use ephemeral app Chat | Generation contract and costs unchanged; adult explicit saving is separate and does not replay generation; child chat remains ephemeral | Not run |  |
| R11-EDIT-01 | P0 | OpenAI SDK edit | `client.images.edit(model=..., image=open("product.png","rb"), prompt=...)` | Job or result returned per the key's setting; the job lists one `image` input | Not run |  |
| R11-EDIT-02 | P1 | JSON edit | `POST /v1/images/edits` JSON with `images: [{file_id}]`, then with `{job_output: {job_id, index}}` | Both run; inputs recorded | Not run |  |
| R11-EDIT-03 | P1 | Unsupported inputs | Edit on a model without input images; a mask on a model without masks | 400 `input_not_supported` before any charge | Not run |  |
| R11-EDIT-04 | P0 | Input price | `POST /v1/estimates` with `input_images: 2`; run that edit | Estimate equals the pre-debit; ledger matches | Not run |  |
| R11-EDIT-05 | P0 | Policy applies to multipart | Multipart edit with the staff photo, people refused | 422 `input_person_not_allowed`, no charge | Not run |  |
| R11-VID-01 | P1 | Animate an image output | `/v1/videos/generations` on Seedance with `first_frame: {job_output: ...}` | Video starts on that frame | Not run |  |
| R11-VID-02 | P1 | Other video inputs | `last_frame` and `reference_images` on supporting models; the same on one that lacks them | Supported ones run; others 400 `input_not_supported` | Not run |  |
| R11-VID-03 | P1 | Aspect mismatch | 720x1280 first frame with a 1280x720 size | 400 `input_aspect_mismatch` naming both, no charge | Not run |  |
| R11-VID-04 | P0 | Foreign or purged output | `first_frame` from A4's job; from a purged job | 404; refused; no charge | Not run |  |
| R11-STU-01 | P0 | Studio unchanged when off | Inputs disabled; compare Studio pages with the previous release | No slots or new controls | Not run |  |
| R11-STU-02 | P1 | Terms dialog | Inputs on, terms not accepted; add an image in the Studio | Terms dialog with Accept; upload continues after | Not run |  |
| R11-STU-03 | P1 | Slots follow the model | Switch between image models and video models | Image slots up to the model's max, mask slot only where supported; video first, last and reference slots as supported | Not run |  |
| R11-STU-04 | P0 | Animate this | Click Animate this on an image result, in History and on the job page | Video Studio opens with that first frame; nothing generates until Generate | Not run |  |
| R11-STU-05 | P1 | Describe as prompt | Describe as prompt on an input | Estimated cost shown first; replaces the prompt only after confirmation; charged as chat | Not run |  |
| R11-STU-06 | P1 | Recipe with slots | Save as recipe with an input; open it; run with the slot empty | Slot saved with name and hint, no file stored; empty slot gives `recipe_input_missing` | Not run |  |
| R11-STU-07 | P1 | Gallery sample input | Run this on a gallery recipe with inputs; submit without replacing the sample | Slot shows "Sample"; sample uploaded as the user's file on submit | Not run |  |
| R11-STU-08 | P1 | Inputs shown on jobs | Open History, the job page and Compare for an edit job | Inputs shown beside outputs; a deleted input says so | Not run |  |
| R11-STU-09 | P2 | Upload errors | Trigger each upload error from the Studio | Each code has its own clear message | Not run |  |
| R11-GAL-01 | P1 | Input recipe page | Open a gallery recipe with inputs on `$SITE` | Sample beside output with the slot hint; "Run this with your own" | Not run |  |
| R11-GAL-02 | P1 | Input recipe JSON and filter | `/gallery/<slug>.json`; index; filter "Uses your image" | `inputs` with absolute `sample_url`; `has_inputs: true`; filter lists them | Not run |  |
| R11-GAL-03 | P1 | How it was made | Read the recipe's cost section and API call | Sample's own model and cost, then the run's; the call shows upload then edit | Not run |  |
| R11-GAL-04 | P0 | No person recipes | Browse all published input recipes | No sample shows a person | Not run |  |

## Round 12: batch and hand-over

A recipe runs over many rows as ordinary jobs, from the API or the app, and users get capped keys, generated code, an AI-assistant prompt and n8n templates. OpenAI's video routes and an OpenAPI file are live.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R12-BAT-01 | P0 | Estimate a batch | `POST $API/v1/recipe-batches/estimate` with 3 rows | `rows`, `total_cents` = sum of single-row estimates, `balance_cents`, `enough` | Not run |  |
| R12-BAT-02 | P0 | Run a batch | Create the 3-row batch; wait for it to finish | 3 jobs with `channel: "BATCH"` and `batch` id and row; ledger debits sum to `spent_cents`; no other charge | Not run |  |
| R12-BAT-03 | P1 | Bad row refused whole | Create a batch whose row 7 has an unknown variable | 400 naming row 7; no batch, no ledger row | Not run |  |
| R12-BAT-04 | P1 | Too many rows | 501 rows | 400 `batch_too_large` | Not run |  |
| R12-BAT-05 | P1 | Concurrency | `max_concurrency: 1`, 4 rows; watch `/rows` | Never more than one row `RUNNING` | Not run |  |
| R12-BAT-06 | P0 | Pause on credit, then resume | Start a batch costing more than the balance; top up; resume | Pauses with `insufficient_credit`; resumes where it stopped; no row submitted twice | Not run |  |
| R12-BAT-07 | P0 | Pause on a key cap | Batch through `$KEY_CAP` that passes 100 cents | Pauses with `spending_cap`; raise the cap, resume finishes | Not run |  |
| R12-BAT-08 | P1 | Cancel | Cancel a running batch | Pending rows `SKIPPED`; running rows finish | Not run |  |
| R12-BAT-09 | P1 | A failed row | One row the provider fails | That row `FAILED` and refunded; others carry on | Not run |  |
| R12-BAT-10 | P1 | Batch webhook | Batch with `webhook_url` (a request bin) | One signed `recipe_batch.completed` call; no per-job webhooks | Not run |  |
| R12-BAT-11 | P1 | Results | Download `results.csv` and `results.zip` | CSV: index, status, one column per variable, job id, cost, error, output URLs; zip files named by row; over the size cap: 413 `results_too_large` | Not run |  |
| R12-BAT-12 | P1 | Limits and errors | Third unfinished batch; resume a running batch; A4 reads A1's batch | 409 `batch_limit_reached`; 409 `batch_not_resumable`; 404 `batch_not_found` | Not run |  |
| R12-BAT-13 | P2 | Rows with images | Recipe with an input slot; each row gives a `file_id` | Each job uses its row's image; files kept until the batch ends | Not run |  |
| R12-BUI-01 | P1 | Paste a table | Recipe page, Run as batch; paste 3 tab-separated rows | Columns map to variables by header; missing variables use defaults and say so; first 3 prompts previewed | Not run |  |
| R12-BUI-02 | P1 | CSV parsing | Choose the CSV with quoted commas, line breaks and a byte-order mark; then the `.xlsx` | Parsed correctly; the workbook is refused with a reason | Not run |  |
| R12-BUI-03 | P1 | Images per row | Drop several images; match by file-name column, then by order | Matched as chosen; rows missing an image flagged | Not run |  |
| R12-BUI-04 | P0 | Nothing starts before Start | Fill the page with too little credit, then enough | Estimate shown; Billing link when not enough; no job until Start | Not run |  |
| R12-BUI-05 | P1 | Follow a batch | Open the batch page while it runs | Progress updates every few seconds and stops at the end; rows table and grid; a row opens its job | Not run |  |
| R12-BUI-06 | P1 | Paused and cancelled in the app | Trigger a credit pause and a cap pause; cancel another | Each says why with the right link and Resume; cancel asks first | Not run |  |
| R12-BUI-07 | P2 | Large paste | Paste 500 rows | Page stays responsive; preview and upload work | Not run |  |
| R12-HND-01 | P0 | Key with a daily cap | Create a key with daily cap 100 cents; spend 100 cents with it | Next request 402; keys page shows spent today against the cap | Not run |  |
| R12-HND-02 | P1 | Edit caps and expiry | PATCH caps, set one to null; try an expiry in the past; create a short-lived key with a future `expires_at`, wait until it expires, then use it | Caps saved, null clears; past expiry refused; expired key 401 with `detail: "API key has expired"` | Not run |  |
| R12-HND-03 | P0 | Key routes are session only | Create or patch a key with an API key | 403 | Not run |  |
| R12-HND-04 | P1 | Run at scale panel | Open it on a recipe page, a Studio result and a batch | Four tabs: assistant prompt, code, n8n, batch; prompt holds the recipe JSON, API base and doc links; no key anywhere | Not run |  |
| R12-HND-05 | P0 | Generated code runs | Copy the curl and Python for sync image, async image, video, a recipe and an edit; set `GODANCE_API_KEY`; run | Each runs as shown and returns a result | Not run |  |
| R12-HND-06 | P1 | Key for this | "Create a key for this" in the panel | Named after the recipe, daily cap 5.00 USD by default, shown once | Not run |  |
| R12-HND-07 | P1 | n8n templates | Import both templates from `integrations/n8n/` into n8n; add a Header Auth credential "Godance API"; run on a 3-row sheet | Jobs run; URL and cost written back per row; a failing row writes its error and the rest continue | Not run |  |
| R12-HND-08 | P1 | Gallery Run at scale block | Open a recipe page on `$SITE` signed out | Prompt, code and n8n shown; sign-up links carry the UTM tags; no key creation | Not run |  |
| R12-OAV-01 | P0 | OpenAI SDK video | `client.videos.create_and_poll(model="sora-2", prompt=..., seconds="8")`, then `download_content(...)` | Video downloads and plays | Not run |  |
| R12-OAV-02 | P1 | Status mapping | Retrieve a video while queued, running, done and failed | `queued`, `in_progress`, `completed`, `failed`; `cost_cents` present | Not run |  |
| R12-OAV-03 | P1 | input\_reference | Multipart `POST /v1/videos` with `input_reference`, inputs on, then off | Runs as a first frame; with inputs off, `inputs_disabled` | Not run |  |
| R12-OAV-04 | P0 | Owner only and delete | A4 retrieves, downloads and deletes A1's video; A1 deletes their own | 404 `video_not_found` for A4; A1's files purged, no refund | Not run |  |
| R12-OAV-05 | P1 | Not supported, said clearly | remix, edits, extensions, characters; `variant=spritesheet` | Clear codes such as `video_remix_not_supported`, `variant_not_supported`; no charge | Not run |  |
| R12-OAV-06 | P1 | OpenAPI file | `GET $API/v1/openapi.yaml` without auth; run it through an OpenAPI 3.1 validator | Public, valid, includes batches, files, edits, videos and key caps | Not run |  |
| R12-OAV-07 | P1 | Docs for AI assistants | Run five examples from `$SITE/llms-full.txt`; check `llms.txt` | Examples work; `llms.txt` links `llms-full.txt` and the OpenAPI file | Not run |  |
| R12-OAV-08 | P2 | Old video routes unchanged | `/v1/videos/generations` and `/v1/jobs/{id}` | Same behaviour as before | Not run |  |

## Round 13: reach

Godance's API is available as MCP tools behind an API key, the gallery is published as an open list from CI, and model comparisons are built from real runs. Comparison and rerun cases are dry runs only: no paid call.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R13-MCP-01 | P0 | Connect and list tools | MCP Inspector or Claude Code to `$API/mcp` (Streamable HTTP) with `Authorization: Bearer $KEY_ASYNC` | Exactly 11 tools: `search_recipes`, `get_recipe`, `list_models`, `estimate_cost`, `generate_image`, `generate_video`, `run_recipe`, `start_batch`, `get_job`, `get_batch`, `upload_input` | Not run |  |
| R13-MCP-02 | P0 | Key required | Connect with no key, and from a signed-in browser session | Both refused | Not run |  |
| R13-MCP-03 | P0 | No admin powers | Read every tool's description and schema | None manages keys, billing, accounts or webhook secrets | Not run |  |
| R13-MCP-04 | P1 | Search and read | `search_recipes` with a query; `get_recipe` on a result | Gallery and own recipes, each marked; recipe has template, variables, slots, runs, costs | Not run |  |
| R13-MCP-05 | P1 | Estimate matches REST | `estimate_cost` and `POST /v1/estimates` for the same request | Same cents | Not run |  |
| R13-MCP-06 | P0 | max\_cost\_cents | `generate_image` with `max_cost_cents` 1 below the estimate | Refused with the estimate; ledger unchanged | Not run |  |
| R13-MCP-07 | P1 | Generate and poll | `generate_image` with `$KEY_SYNC`, then `$KEY_ASYNC` plus `get_job`; `generate_video` | Sync waits for the result; async returns a job id; results carry signed URLs, never bytes | Not run |  |
| R13-MCP-08 | P1 | Recipes and batches | `run_recipe`; `start_batch` with `max_cost_cents`; `get_batch` | Runs; the batch never spends past its ceiling (a row that would fails before charge) | Not run |  |
| R13-MCP-09 | P1 | Errors reach the agent | `upload_input` before accepting the input terms | Tool error carrying `inputs_terms_required` | Not run |  |
| R13-MCP-10 | P0 | Caps and region apply | Spend past `$KEY_CAP` through MCP; on the Alicloud region call a Google model | 402 like REST; 503, nothing spent | Not run |  |
| R13-MCP-11 | P1 | Setup panel | API keys page, Use with MCP | Snippets for Claude Code, Cursor and a generic client, showing `$GODANCE_API_KEY`, never the key | Not run |  |
| R13-LST-01 | P1 | Generate the list | `npm run open-list -- --out /tmp/ol` in `marketing/` with the gallery on | `README.md`, `README_zh.md`, `data/recipes.json`, `LICENSE.md`, `CONTRIBUTING.md` | Not run |  |
| R13-LST-02 | P0 | Attribution | Read a CC BY entry in both READMEs | Author with link, original post, source list and licence, what changed, as on the recipe page | Not run |  |
| R13-LST-03 | P1 | Stable and safe | Run twice and diff; remove a recipe and rerun; switch the gallery off | Only the date line differs; removed recipe gone; refused when off | Not run |  |
| R13-LST-04 | P1 | Links tagged | Check Run this and View recipe links | `utm_source=github&utm_medium=open-list&utm_campaign=<slug>` | Not run |  |
| R13-LST-05 | P1 | CI publishing | Run the `open-list` workflow with the variable and secret unset, then set | Unset: succeeds doing nothing. Set: one commit naming recipes added and removed; never a force push | Not run |  |
| R13-CMP-01 | P1 | Comparison pages | Open `$SITE/gallery/compare` and a pair page | Pairs with at least `GALLERY_COMPARE_MIN` shared recipes; side-by-side outputs with costs; summary table; no winner claimed | Not run |  |
| R13-CMP-02 | P1 | Below the threshold | A pair with fewer shared recipes | No page and no link | Not run |  |
| R13-CMP-03 | P1 | Run this picks a usable run | Recipe whose first run's model is not in `/v1/models` but the second's is; then one with none | Studio uses the second run's model; otherwise the default, with a note | Not run |  |
| R13-CMP-04 | P1 | Rerun is a dry run | `rerun --models <id> --slugs <slug> --max-spend-cents 100` without `--apply`; repeat without `--max-spend-cents` | With cap: plan printed with estimated cost, nothing spent. Without cap: exit code 2, cap required, nothing spent | Not run |  |

## Round 14: consumers and experts

Users publish recipes after moderation, experts bring their own provider keys and routing policies, and the repository includes a self-hosting guide. All five workstreams are on `main`: publishing (#53), community pages (#56), BYOK (#54), routing policies and batch fallbacks (#59), and self-hosting (#55). Include all 28 rows below, including R14-RTE. Community, BYOK and user routing policies still need the staging configuration above; merged code does not establish that a deployment has enabled them.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R14-PUB-01 | P0 | Who may have a profile | Set a handle as A1; as A3; as an unverified adult; try a reserved word | A1 succeeds; the others are refused with the rule shown | Not run |  |
| R14-PUB-02 | P0 | Pending is not public | A1 publishes a recipe version with two example jobs | 202; absent from `GET $API/v1/public/recipes` and the site | Not run |  |
| R14-PUB-03 | P0 | Moderation | SUPPORT approves one item and rejects another with a reason | Approved one is public with copied examples; author sees the rejection reason | Not run |  |
| R14-PUB-04 | P0 | Public fields only | Read a public recipe and profile with no auth | No user id, email, source job id or input file; only listed fields | Not run |  |
| R14-PUB-05 | P1 | Examples outlive their jobs | Purge or un-save a source job of a public recipe | Examples still shown | Not run | dev-assisted |
| R14-PUB-06 | P1 | Unpublish | Author unpublishes | Gone from public routes at once; example copies deleted | Not run |  |
| R14-PUB-07 | P1 | Reports | A4 reports a public recipe; reports many times | Item back in the queue, still public until removed; reports rate-limited | Not run |  |
| R14-PUB-08 | P1 | Run a published recipe | A4 runs it with `recipe: {published_id, variables}` | Job records the published recipe; `runs` and `runners` go up; the author's own runs do not count | Not run |  |
| R14-PUB-09 | P1 | Follows | A4 follows A1, opens the feed, then unfollows; A1 follows self | Feed shows A1's new recipes; follower count only, never who; self-follow refused | Not run |  |
| R14-PUB-10 | P1 | Publish refusals | Publish a recipe with a people input slot while people are refused; one whose examples used inputs | Refused with the reason | Not run |  |
| R14-PUB-11 | P0 | Account closure | Close A1 | Profile, follows and reports gone; recipes unpublished; example copies deleted | Not run |  |
| R14-COM-01 | P1 | Community pages | Rebuild the site; open `/community`, a recipe and `/u/<handle>` | Pages render from the public API; canonical URLs; in the sitemap | Not run |  |
| R14-COM-02 | P0 | Build survives an API outage | Build the marketing site with the API unreachable | Build succeeds with no community pages and a warning | Not run |  |
| R14-COM-03 | P1 | Community build switch off | Build with marketing `COMMUNITY_ENABLED=false`, including a discovery-on variant | No community output or Community links; discovery alone cannot enable it; setting only `PUBLIC_SITE_DISCOVERY_ENABLED=false` does not turn off independently available Community output | Not run |  |
| R14-COM-04 | P1 | Publish flow in the app | Publish from a recipe page; check each status; unpublish | Dialog shows what becomes public; status line for pending, public, rejected, removed | Not run |  |
| R14-COM-05 | P1 | Run, follow, report, moderate | Run from a community page; follow; report; staff opens the moderation page | Studio loads with `?published=`; feed works; report form works; moderation only for allowed roles | Not run |  |
| R14-BYOK-01 | P0 | Off by default | Providers list empty; open Settings; `PUT /v1/provider-credentials/OPENAI` | No section; 400 `byok_provider_not_supported` | Not run |  |
| R14-BYOK-02 | P0 | Key stored safely | Enable one provider; save a valid key, then an invalid one; search logs and audit | Valid saved and validated, only `last4` returned; invalid refused; the key appears in no log, audit row or response | Not run | dev-assisted |
| R14-BYOK-03 | P0 | Requests use the user's key | Run a request on that provider's model | Provider dashboard shows the user's key; ledger charges only the fee; estimate says `byok: true` | Not run |  |
| R14-BYOK-04 | P0 | Region still decides | Alicloud region, user has an OpenAI credential; call an OpenAI model | 503, nothing spent | Not run |  |
| R14-BYOK-05 | P1 | Rejected key | Revoke the key at the provider; request with fallback off, then on | Off: 402 `byok_credential_rejected` and refund. On: retried once on the platform key at the normal price | Not run |  |
| R14-BYOK-06 | P1 | Caps and access | Cap the key; spend fees past it; call credential routes with an API key | Caps apply to the fee; API key 403 | Not run |  |
| R14-RTE-01 | P1 | Routed requests recorded | Chat with `godance-auto` | Usage record has `routed_policy` and `routed_rule`; margin view for FINANCE and ADMIN sums them | Not run |  |
| R14-RTE-02 | P1 | User policies | Disabled: call `/v1/routing/policies`. Enabled: `PUT /v1/routing/policies/{id}` with valid and invalid policies; call chat with the valid policy id as `model` and `X-Godance-Route` matching a rule | Disabled: 404 `routing_policies_disabled`; validator refuses the invalid one; the hint picks the matching rule | Not run |  |
| R14-RTE-03 | P0 | Invariants | Direct model call; a policy whose top choice the region forbids | Direct call unchanged; forbidden choice falls through | Not run |  |
| R14-RTE-04 | P1 | Batch fallbacks | Save an enabled user policy with media fallbacks; create a batch with `fallbacks: true`; force a provider error, then a content refusal | Error: retried once on the next model, failed job refunded. Refusal: not retried | Not run |  |
| R14-SH-01 | P1 | Self-host quick start | Follow `docs/self-hosting.md` on a clean VM | `/healthz` green, sign-up, admin credit adjustment, one chat, Studio loads; note unclear steps | Not run |  |
| R14-SH-02 | P2 | Configuration reference | Compare the guide's reference with `application.yml`; read `.env.example` | Matches; placeholders are visibly fake; regional allowlist described as fixed | Not run |  |

## Round 15: navigation and existing-feature completion

Five merged streams expose existing workflows and complete billing, adult saved-chat, public discovery and read-only feature status. Use prepared staging/local variants; these cases authorize no production activation. All payment/model responses for this section are synthetic or sandboxed.

### 01 — App navigation, library and session boundaries

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R15-NAV-01 | P1 | Core destinations | A1 opens each Main Menu group and destination | Dashboard; Image Studio `/create/image`, Video Studio `/create/video`, Chat `/chat`; History `/history`, Recipes `/recipes`, Batches `/batches`; API keys `/keys`; Billing `/billing`, Settings `/settings` reachable. MCP documentation points to `https://godance.ai/docs/#mcp`; empty core pages remain linked. | Not run |  |
| R15-NAV-02 | P1 | Accessible Menu | At 320px and desktop width, light/dark: focus Menu, use Enter/Space/Tab, follow a link | Menu opens/closes, visible focus reaches links, current destination identified; same permitted destinations on mobile, no sideways page scrolling. | Not run |  |
| R15-NAV-03 | P1 | Legacy Studio redirect | Open `/create?example=<known-slug>`; repeat with another supported prefill query | Redirect to `/create/image` preserves the query and existing prefill; no generation starts. | Not run |  |
| R15-NAV-04 | P1 | Sign-in return | Signed out, open `/batches`, then a known recipe detail with a query; sign in for each, including a sign-up-to-sign-in round trip | Exact safe destination/query preserved; ownership and consent checks still apply. | Not run |  |
| R15-NAV-05 | P0 | Pending parental consent | A5 signs in through a protected deep link | Waiting-for-consent screen retained; customer Menu absent; no bypass through the new navigation. | Not run |  |
| R15-NAV-06 | P1 | Optional links fail closed | Delay `/v1/profile` and `/v1/referrals/me`; test permitted, disabled, denied and failed responses | Following and Invite absent while unresolved/denied/failed; each appears only with its own permitted response. Core links remain available. | Not run | dev-assisted |
| R15-NAV-07 | P0 | Guidance does not perform actions | Read Dashboard; follow History, Recipes, Batches and API-key/MCP guidance; inspect requests after ordinary sign-in tasks settle | Guidance describes Select → compare 2–4 compatible jobs, Save as recipe, batch and Run at scale. Navigation creates no generation, accepts no terms, saves no credential and initiates no payment. No invented routing editor or job IDs. | Not run |  |
| R15-NAV-08 | P1 | Following discovery CTA | Eligible account with no follows: provide a readable approved public Community index; repeat with empty, unavailable, unreadable and wrong-origin/path redirect output | Explore community recipes appears only for usable output at the configured site's `/community` or `/community/`; hidden otherwise. Discovery flag alone does not establish Community availability. | Not run | dev-assisted |
| R15-LIB-01 | P1 | Three saved concepts | Give A1 a versioned recipe, gallery bookmark and saved generated job; open Recipes and History | Versioned recipes and Gallery bookmarks have separate sections; generated outputs remain in History/Saved. Labels and counts do not conflate them. | Not run |  |
| R15-LIB-02 | P1 | Bookmark persistence | In Studio bookmark an image and video example; leave Studio, open Recipes, reload, reopen each | Bookmarks persist; available examples open the correct Studio with their setup, without generating. | Not run |  |
| R15-LIB-03 | P1 | Unavailable bookmark | Keep a bookmarked slug absent from the current index and recipe output; open it from Recipes | Slug remains identifiable with availability guidance; Studio explains unavailable example and starts empty, without fabricated setup or generation. | Not run | dev-assisted |
| R15-LIB-04 | P1 | Empty and failed bookmark reads | Open an account with no bookmarks; inject 503 then 404 on `GET /v1/saved-recipes`; restore and Retry bookmarks | Empty state explains saving in Studio; 503 reports load failure; 404 reports unavailability. Failure is not an empty successful list; retry rereads. | Not run | dev-assisted |
| R15-LIB-05 | P1 | Remove a bookmark | Remove a bookmark and reload; repeat with a failed DELETE | Success persists without deleting a versioned recipe or job; failure shows an error and retains the bookmark. | Not run | dev-assisted |
| R15-ACC-01 | P0 | Old successful reads cannot cross accounts | Hold A1 discovery/private-library responses; log out and sign in A4 within the same document, then release them | A4 receives no A1 links, bookmarks, recipes, recent jobs, follows or feed results; only its current responses affect the UI. | Not run | dev-assisted |
| R15-ACC-02 | P0 | Old 401 cannot end another session | Hold an A1 read; log out/sign in A4; use a client-side Menu link to reach Dashboard; release A1's 401. Repeat for profile, referral, admin-me, follows, bookmarks, jobs, recipes and feed reads | A4 stays signed in; obsolete reads are cancelled/discarded. No reload or full-document navigation before release, which would hide the race. | Not run | dev-assisted |
| R15-ACC-03 | P0 | New session for the same account | Repeat delayed success/401 while logging out and back into A1; include in-flight recipe/feed Load more | New session rediscovers permissions/data; old responses cannot restore state, append old pages or end the new session. | Not run | dev-assisted |
| R15-ACC-04 | P0 | Current expiry still works | Expire the current session while the app remains open; issue a current private read | Current 401 returns to Sign in; old account data is not shown as current authenticated content. | Not run | dev-assisted |

### 02 — Billing on existing payment and usage APIs

Use controlled synthetic responses and intercept all financial POST/PUT requests before the backend/provider, or use a confirmed sandbox. Boundary amounts and failure fixtures must be mock-only. No real payment or saved live card is required. The marked consent constant remains an operator wording decision before rollout.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R15-BIL-01 | P0 | Entry and return URL are read-only | Open/reload `/billing` and `/billing?success=true&amountCents=999999`; inspect balance/requests | Only account/config/usage reads; no top-up POST or recharge PUT; URL does not confirm payment or alter balance. | Not run | dev-assisted |
| R15-BIL-02 | P0 | Explicit Stripe card-save intent | Stub hosted checkout; observe Save my card; submit once unchecked, then explicitly checked | Starts unchecked; unchecked request omits `save_card`; checked Stripe request includes `save_card:true`. Saving alone never enables auto-recharge. | Not run | dev-assisted |
| R15-BIL-03 | P0 | USDT never saves a card | Check Stripe save-card, switch to USDT, enter `25.29`; use stubbed checkout | NOWPayments receives `amountCents:2529`, no `save_card`; no Stripe or recharge write. | Not run | dev-assisted |
| R15-BIL-04 | P0 | Exact cents and invalid inputs | Use `25.29`; try negative, excess precision, exponent and unsafe values in applicable inputs. Review threshold `90071992547409.91` in a mock-only fixture, then Cancel | Exact integer cents; invalid/unsafe values send nothing; review retains final `.91`. No promise of fixed deployment bounds; server-bound refusal preserves form. Never send the boundary amount to a provider. | Not run | dev-assisted |
| R15-BIL-05 | P1 | Confirmed configuration and failure status | Supply disabled/enabled fixtures with `threshold_cents:500`, `topup_cents:2500`, method availability and synthetic failure fields | Confirmed server state, $5.00 threshold, $25.00 recharge, saved-method availability and last failure displayed. No invented card brand/last4/removal, invoice, receipt or complete payment history. | Not run | dev-assisted |
| R15-BIL-06 | P0 | Review and cancel before enabling | Saved-method fixture: threshold0, recharge12.34; Review; use Tab/Shift+Tab; Cancel/Escape | Review states $0.00 threshold, $12.34 recharge, USD and off-session meaning. No PUT before Confirm; cancel sends none; modal focus contained and restored. | Not run | dev-assisted |
| R15-BIL-07 | P0 | Confirm enable and enabled update | Confirm reviewed0/12.34; change enabled settings to5/30 and review/confirm again | One PUT per explicit Confirm with integer `threshold_cents`/`topup_cents`. Enabled updates also review both amounts; only confirmed response establishes success. | Not run | dev-assisted |
| R15-BIL-08 | P0 | Pending guards and separate disable | Delay confirmed PUT; double-click/Enter; after success choose Disable. Also delay a stubbed checkout and repeat its submit | One request per pending action; no duplicate top-up or recharge write. Disable is separate explicit `{enabled:false}`, never an automatic replay. | Not run | dev-assisted |
| R15-BIL-09 | P0 | Missing or non-card method | Show no-method fixture; then inject409 during enable with subsequent GET returning method absent | Review unavailable without saved method. 409 refreshes status by GET, preserves drafts, shows failure/updated method state; no automatic PUT retry. | Not run | dev-assisted |
| R15-BIL-10 | P0 | Provider/write failure | Inject502 on Confirm or Disable; inspect inputs/status, then Refresh status | Drafts and last confirmed state remain; failure is not success. Refresh is GET-only; another enable/update attempt needs explicit review/confirm. | Not run | dev-assisted |
| R15-BIL-11 | P1 | Recent loaded usage scope | Supply newest-first rows with customer costs123/200; filter by model then clear | GET uses `limit=200`; total $3.23 unfiltered. Filters/totals describe loaded rows only, with no monthly/lifetime/complete-history claim. | Not run | dev-assisted |
| R15-BIL-12 | P0 | Provider-cost frontend containment | Supply `providerCostCents:987654321`, `costCents:123`; inspect UI, accessibility and mapped view/export state if present | Customer $1.23 correct; internal field/value absent from view/accessibility/export state. Raw API response may still contain it; this is not backend redaction. No export feature is required. | Not run | dev-assisted |
| R15-BIL-13 | P1 | Loading, empty, refused and failed reads | Delay reads; return empty usage; inject401/403/503 on separate GETs, restore and retry | Truthful loading/empty/error; current401 signs out; no optimistic enabled state. Read retries replay no checkout/recharge write. | Not run | dev-assisted |
| R15-BIL-14 | P0 | Late reads and checkout across accounts | Hold A1 config/usage GETs and a stubbed checkout success; log out/sign in A4 in same document, release old responses; repeat old GET401 and checkout502 | No A1 usage/config/status or late checkout redirect appears for A4. Obsolete GET401 cannot end its session; financial requests are never repeated. | Not run | dev-assisted |

### 03 — Explicitly saved adult chat history

Use synthetic SSE responses and conversation fixtures. Generation and persistence are separate: a save failure never replays a paid model request. The app's saved-history controls/calls are ADULT-only; this does not claim a new backend age gate. Conversation routes require a signed-in session. Create/append have no idempotency key, so lost responses need read-only reconciliation rather than automatic POST retry.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R15-CHT-01 | P0 | Adult new chat is ephemeral | A1 generates a synthetic SSE answer without Save; inspect conversation calls | Answer works, Ephemeral mode clear; no conversation read/create/append before explicit history/save action. | Not run | dev-assisted |
| R15-CHT-02 | P0 | Child, unknown and pending privacy | A3, unknown-age and pending-consent fixtures open Chat and send only where allowed | No saved-history controls or conversation calls. Child/unknown chat stays ephemeral; pending account retains its waiting screen; the parental-notice promise remains unchanged. | Not run | dev-assisted |
| R15-CHT-03 | P0 | Explicit save and payloads | Adult reads retention disclosure, generates a completed turn, chooses Save this chat; inspect normal successful sequence | One create then sequential eligible appends, retained returned IDs, each message once in normal path. No invented billed `cost_cents` or unavailable token metadata. | Not run | dev-assisted |
| R15-CHT-04 | P1 | Load and continue a saved chat | Seed ordered system/user/assistant records with actual model/token/cost metadata; explicitly load and send a synthetic turn | Order/roles/metadata retained; saving-on status clear; only new eligible messages append, never the whole transcript again. | Not run | dev-assisted |
| R15-CHT-05 | P1 | New chat and reload | Start New chat after saved/unsaved states, handle discard confirmation; reload, explicitly open Saved chats | New/reloaded chat is ephemeral; discard warning truthful; saved list remains loadable. No transcript restored from browser storage/URL. | Not run |  |
| R15-CHT-06 | P0 | Definitively rejected save | Complete answer; reject append400; correct fixture and explicitly Retry save | Answer remains; only unsaved messages retry, with no second create or generation request. Save error distinct from generation error. | Not run | dev-assisted |
| R15-CHT-07 | P0 | Committed append with lost response | Fixture commits append but drops response; reconciliation GET returns unique matching record after known IDs | Read-only reconciliation retains returned record ID; no POST replay or exactly-once guarantee. Continue saving appends only remaining messages. | Not run | dev-assisted |
| R15-CHT-08 | P0 | Ambiguous append stays unresolved | Drop response; saved copy absent/nonunique or GET fails; Check saved copy; send another synthetic turn | Unresolved status and answer remain; checks are GET-only; no automatic replay. Generation can continue while saving is paused. | Not run | dev-assisted |
| R15-CHT-09 | P0 | Lost create response | Fixture creates conversation but drops response; inspect/list again, including empty/failed list | No automatic second create or append to a guessed ID; unresolved outcome and in-memory answer retained; no exactly-once claim. | Not run | dev-assisted |
| R15-CHT-10 | P0 | Serialized writes and stream controls | Delay create/append; repeat Save/Send; while streaming/saving attempt New, selection and archive | One generation for the accepted Send submission; repeated pending actions send none. One create; appends wait for prior returned IDs. Pending controls prevent duplicate/cross-chat writes and switching chunks into another selection. | Not run | dev-assisted |
| R15-CHT-11 | P0 | Stopped, blank and truncated replies | Stream partial text then Stop; repeat before first chunk and EOF without completion; Save, then Send again | Partial/empty assistant remains truthfully not saved and never appends. Next generation has no blank assistant placeholder; completed answer remains visible. | Not run | dev-assisted |
| R15-CHT-12 | P0 | Archive and unknown archive status | Cancel archive, then confirm; repeat with lost response or an externally archived selection | Copy says archive/30-day purge, not immediate erasure. Cancel sends none; confirmed archive leaves active list. Archived/unconfirmed selection read-only until checked; known archived chat cannot generate/append through app. | Not run | dev-assisted |
| R15-CHT-13 | P0 | Account, age and callback fencing | Hold stream/save; log out/sign in A4 or change ADULT to non-ADULT; release old callbacks; inspect storage/URLs/logs with synthetic marker | Old transcript/status/chunks never attach to new account/age state; current memory cleared, no history replay; marker absent from browser storage, URLs, console and telemetry. | Not run | dev-assisted |
| R15-CHT-14 | P1 | Empty, foreign and failed reads | Empty list, failed list/messages read then retry; A4 tries A1 conversation or404 fixture while holding its own current answer | Truthful loading/empty/error; no private leak. Failed open preserves current conversation/answer; retries replay no generation or write. | Not run | dev-assisted |
| R15-CHT-15 | P1 | Existing API limits are honest | Inspect saved-chat actions and retention copy, including archive confirmation | No unsupported rename, restore, hard-delete, message-edit/delete or idempotency controls/promises. Archive is distinct from account closure and immediate erasure. | Not run |  |

### 04 — Opt-in site discovery and independent gates

Local parity builds deliberately unset destination variables. Interactive previews instead set `SITE_URL=$SITE`, marketing `PUBLIC_API_BASE`/`PUBLIC_APP_URL`, and app `VITE_API_URL`/`VITE_GALLERY_URL`. Do not compare a staging-configured build to the all-unset parity baseline. Hosting CORS is independent of discovery; Cloudflare default headers and redirect handling are not fully represented by a local emitted-rule fixture.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R15-SIT-01 | P0 | Default output preserves B0 | Locally build B0 `8219e0497cb12a7be48b80915601811e361726bf` and Round15 integration with identical Node22/npm/lockfile, locale/timezone/year and deterministic offline catalog fallback. Unset optional public/gallery/community variables and `SITE_URL`; compare every path and byte without normalization | 22 paths on each side, none added/removed. Only `docs/index.html`, `llms.txt`, `llms-full.txt` may differ. CSS/JS, hashes, headers, redirects, robots and sitemaps match. | Not run | dev-assisted |
| R15-SIT-02 | P1 | Strict discovery flag | Separate local builds: unset, empty, false, true, then malformed `1` or `tru` | Unset/empty/false preserve legacy shell/home/pricing and omit `/create` + `/integrations`; true emits both. Malformed values fail with the variable name and accepted values. | Not run | dev-assisted |
| R15-SIT-03 | P1 | Enabled site and navigation | On an isolated enabled preview, visit home, `/create`, `/integrations` and pricing on desktop/mobile light/dark; use keyboard/screen reader; follow header/footer/contents links; inspect canonical/sitemap | Accessible Create, Explore, Developers and Pricing destinations, visible focus, usable disclosures and no overflow. Visible internal links reach emitted content; both product pages have metadata/canonical/sitemap entries. | Not run |  |
| R15-SIT-04 | P0 | Safe app handoffs | Follow app CTAs for Image/Video Studio, Chat, History, Recipes, Batches and API keys signed out, as A1, then awaiting consent/onboarding; separately follow MCP documentation | App CTAs use configured `$APP` and existing routes; safe sign-in return and normal gates preserved. Compare starts with History selection; docs reach their documented anchors. No job, terms acceptance or payment starts from the click. | Not run |  |
| R15-SIT-05 | P1 | Gallery gate and validation | Local builds: missing media/contact, valid empty content, valid available content, malformed content/config. Repeat available with discovery false/true | Off/empty emits no Gallery destinations; available Gallery works with either discovery value. Malformed input still fails the build; discovery bypasses no validation. | Not run | dev-assisted |
| R15-SIT-06 | P1 | Comparisons require shared runs | Valid local fixtures: pair below the default minimum 8 shared recipes, then at threshold; inspect pair pages/links | Below threshold no pair page/link; at threshold real pair output/costs, no invented runs or winner claim. | Not run | dev-assisted |
| R15-SIT-07 | P1 | Separate Run this gate | Gallery available: build with `GALLERY_RUN_THIS=false`, then true; open enabled action without submitting | False hides Gallery Run this while retaining Gallery; true loads the configured Studio/recipe with normal gates, without generating. | Not run | dev-assisted |
| R15-SIT-08 | P1 | Community matrix | Local fixtures or prepared staging: Community build off, backend off, empty approved content, API failure, then readable approved content; repeat available with discovery false/true | Off/empty/unreadable omits Community output/links; API failure warns and build succeeds. Available approved content is independent of discovery. Pending/rejected submissions stay absent. | Not run | dev-assisted |
| R15-SIT-09 | P1 | Public index and host CORS | Preview app reads actual staging public Community index: approved direct/canonical redirect, empty/error and blocked-CORS variants | Eligible account can discover readable approved content even with discovery off. Empty/error/CORS-denied or wrong-origin/path redirects hide the CTA; public request sends no credentials. Emitted-header fixtures alone do not prove hosting behavior. | Not run | dev-assisted |
| R15-SIT-10 | P1 | Accurate independent documentation | Read `/docs`, `llms.txt`, `llms-full.txt` with discovery off; inspect enabled home/product/pricing against existing contracts | Docs remain available; saved concepts and compare workflow are distinct. Unsupported audio/embeddings and conditional Inputs/BYOK/routing are described accurately; no new editor/readiness promise. Enabled pricing does not claim BYOK is enterprise-only. | Not run |  |

### 05 — Read-only ADMIN feature configuration

Only backend addition: `GET /v1/admin/features`; UI: `/admin/features`. This panel reports configuration, with no activation controls. Staging variants must be prepared separately, without changing production or placing private configuration in evidence.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| R15-ADM-01 | P0 | Exact redacted schema | Active verified ADMIN with 2FA calls `GET /v1/admin/features`; compare recorded staging configuration | 200, exactly eight booleans: `inputs.enabled`, `inputs.allow_people`, `community.enabled`, `byok.enabled`, `routing_policies.enabled`, `routing_policies.allow_list_restricted`, `referrals.enabled`, `mcp.enabled`. No secrets, addresses/counts, provider details, identities, URLs or readiness claims. Unmodified defaults false except MCP true. | Not run |  |
| R15-ADM-02 | P0 | ADMIN-only access | USER, SUPPORT and FINANCE call endpoint and open `/admin/features`, with/without 2FA; repeat as ADMIN | Non-ADMIN feature API 403. SUPPORT/FINANCE have no Feature status entry: with 2FA the direct page explains ADMIN restriction; without 2FA the existing console enrollment screen appears. Neither fetches feature configuration. USER cannot enter console; eligible ADMIN can. | Not run |  |
| R15-ADM-03 | P0 | Session and account state | Call with no authentication, API key, unverified ADMIN and inactive ADMIN | Respectively 401, 403, 403, 401; no configuration disclosed. | Not run | dev-assisted |
| R15-ADM-04 | P0 | 2FA gate | Active verified ADMIN without 2FA opens Feature status and calls API; compare direct feature API as SUPPORT/FINANCE with and without 2FA | ADMIN API 403 `two_factor_required`; console directs to Settings enrollment without showing statuses. SUPPORT/FINANCE direct feature API is always403 without that code, independently of their console enrollment UI. | Not run | dev-assisted |
| R15-ADM-05 | P0 | Read-only endpoint and UI | Inspect controls/requests; keyboard Refresh; send POST/PUT/PATCH/DELETE with ADMIN session | No switches, Save/Apply or enable action; Refresh only GET. All four write methods 403; no config, ledger or provider mutation/probe. | Not run |  |
| R15-ADM-06 | P1 | Unresolved role and invalid payload | Delay `/v1/admin/me`; delay/fail/malform feature response, including a missing boolean; restore and Retry | Unresolved role exposes no feature entry/request. Loading announced; failed/incomplete data shows error, no optimistic configured-on defaults; retry recovers. | Not run | dev-assisted |
| R15-ADM-07 | P1 | Refresh clears stale status | Load valid status; hold Refresh, return 503, then Retry successfully | Cards clear while loading and remain absent after failure; Refresh disabled pending; recovery shows only current values. | Not run | dev-assisted |
| R15-ADM-08 | P0 | No configuration across sessions | Hold ADMIN feature response; log out/sign in SUPPORT or A4 without reloading; release old success, then repeat old 401 | No old configuration appears, new account stays signed in, entry/content follows current role. | Not run | dev-assisted |
| R15-ADM-09 | P1 | Configuration derivation and limits | In isolated prepared variants, compare empty/nonempty BYOK providers, either referral reward positive, nonempty routing allowlist with routing disabled, and MCP false; inspect UI explanations | Booleans match bound settings: either reward enables referral status; routing restriction independent of routing enablement. No addresses/counts or provider-key health leak. Configured-on does not grant account access or establish content/site/provider availability; Refresh changes no setting. | Not run | dev-assisted |

## Cross-cutting regression

These checks span every round; run them last, against the whole build.

| ID | Pri | Case | Steps | Expected | Result | Notes |
| --- | --- | --- | --- | --- | --- | --- |
| X-01 | P0 | Ledger matches jobs | For 10 jobs across image, edit, video, batch and MCP, compare each job's `actualCostCents` with A1's ledger | Every debit matches; every failed job refunded in full; no orphan entries | Not run |  |
| X-02 | P0 | Regional allowlist on new routes | On the Alicloud region, call an OpenAI, Google, BFL or Runway model through edits, `/v1/videos`, a batch, MCP and BYOK | 503 each, nothing spent | Not run | needs the Alicloud region |
| X-03 | P0 | API key vs session | With `$KEY_ASYNC`, call keys, billing, referrals, attribution, input terms, provider credentials, profile, conversation and `/v1/admin/features` routes | 403 each; the model, job, file, recipe and batch routes accept the key; customer usage remains a separate API-key-permitted read | Not run |  |
| X-04 | P0 | Spending caps everywhere | Set a user daily cap; spend through chat with images, edits, videos, batches and MCP | Each stops at the cap with 402 (a batch pauses) | Not run |  |
| X-05 | P0 | Account closure is complete | Close an account that used every feature, including explicitly saved chats | Jobs, files, recipes, batches, saved items, conversations/messages, first touch, profile and credentials gone | Not run |  |
| X-06 | P1 | 90-day purge | Age jobs past 90 days; run the purge | Prompts, recipe values and batch row values redacted; input files released; saved jobs kept | Not run | dev-assisted |
| X-07 | P0 | No secrets or transcripts in diagnostics | Search staging logs for `gd_live_`, provider-key prefixes and synthetic uploaded-file markers; check a distinctive synthetic chat marker in logs, browser storage, URL, console and telemetry | No secret/file markers in logs and no chat text in diagnostics/storage/URLs. Deliberate Send/Save request bodies may contain synthetic chat content; do not publish those bodies or real transcripts as evidence | Not run | dev-assisted |
| X-08 | P1 | OpenAI drop-in | Follow `docs/openai-dropin-verification.md`: chat, images with a sync key, `images.edit`, videos | Every step passes; record results in that file | Not run |  |
| X-09 | P1 | Themes and screen sizes | Check Menu, Dashboard, Studio, Recipes/bookmarks, Compare, Batches, API keys, Settings inputs, Invite, Following, Billing/recharge review, Chat/history, ADMIN Feature status and discovery-on site in light/dark, desktop/phone/320px | No horizontal page overflow, clipped controls or unreadable text; test long saved-chat titles and synthetic data | Not run |  |
| X-10 | P1 | Accessibility | Keyboard only and a screen reader on the same pages; use Enter/Space on Menu, saved-chat controls and site disclosures; open/cancel recharge review | Every control reachable and labelled; current destination exposed; modal focus stays inside and restores on close; job/batch/loading/save/error status changes announced | Not run |  |
| X-11 | P2 | Docs match the API | Spot-check 10 routes in the docs page, `llms-full.txt` and `/v1/openapi.yaml` | Same fields and codes as the live API | Not run |  |

## Open questions

Answer these before the run starts; each one blocks the rows named.

- [ ] **Staging build and switches.** Confirm each staging surface contains the relevant Round 15 changes from integration `22b4f47bb89cf91c6325e5237c8c7199ccea4c33`; record each build commit, API/app/site origins and runtime/build settings. Provide separate discovery-off/on staging builds and all required off/empty/available/failure fixtures. Production rollout flags stay off. Run all 28 Round 14 rows as regressions as well as the new Round 15 cases.
- [ ] **Staging addresses and accounts.** Who provides `$API`, `$APP`, `$SITE`, A1–A5/unknown-age fixtures, the three staff roles and ADMIN account/2FA denial variants? Confirm app gallery/public-index reads use staging.
- [ ] **Developer help.** Who supports dev-assisted time travel, log/DTO/state inspection, deterministic build parity and delayed/failed/lost-response injection? The account-switch harness must keep the same document alive; a full reload would cancel the obsolete request and hide the regression.
- [ ] **Payment and provider setup.** Confirm Stripe/NOWPayments test mode and stubs for Round 15; no real charge or paid model call is needed for its cases. Agree a budget and use current deployed estimates for any older cases that require real staging providers.
- [ ] **Auto-recharge wording.** The single `DRAFT_AUTO_RECHARGE_CONSENT` source remains marked for operator review before rollout. Who reviews the wording? Testing the confirmation flow does not approve production consent text or enable charging.
- [ ] **Site content and host.** Who supplies approved synthetic staging content and the real staging host/CORS behavior? Never publish QA fixtures as real content; account permission, content/build availability and discovery are independent checks.
- [ ] **Alicloud region.** X-02, R13-MCP-10 and R14-BYOK-04 need a staging deployment in that region. Does one exist?
- [ ] **Person check.** Which vision model runs the check on staging, and which consenting staff member's photo is used?
- [ ] **n8n.** An n8n instance and a Google account for R12-HND-07.
