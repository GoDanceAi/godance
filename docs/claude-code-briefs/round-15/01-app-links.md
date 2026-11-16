# Brief 01 — App links

**Branch:** `feature/app-links` (worktree `../gd-app-links`) off `main`
**Reserved migration:** none. This brief has no backend changes.
**Baseline:** `npm run lint`, `npm run build`, `npm run test:e2e` green in `app/` on parent; record the counts first

## Mission (one line)

Give users a way to reach the gallery from the app and to manage their
routing policies, and let a batch turn on the fallbacks those policies
define. Each appears only where it works.

## Why now

These are built and unreachable from the app:

- **The gallery.** The only link from the app is inside the empty Studio's
  example row (`components/GalleryExamples.tsx`). It disappears once the
  Studio has a prompt, and whenever the gallery index is empty.
- **Routing policies** (round 14). `/v1/routing/policies` has no screen. A
  policy is called by its id as a chat model, but the Chat model picker does
  not list policies.
- **Batch fallbacks** (round 14). `POST /v1/recipe-batches` accepts
  `fallbacks: true`, and the batch page shows which model served each row.
  The new-batch page cannot ask for them.

## Read first

1. This brief and `00-coordination.md` ("links appear only when usable" is
   binding)
2. App:
   - `app/src/components/Layout.tsx`: how Invite and Following are added
     only when their feature is on
   - `app/src/lib/gallery.ts` (`GALLERY_ORIGIN`, `loadGalleryIndex`),
     `components/GalleryExamples.tsx`
   - `app/src/pages/SettingsPage.tsx` and its existing sections, such as
     `components/ProviderKeysSection.tsx`, which is hidden when off
   - `app/src/pages/NewBatchPage.tsx`, `components/batch/BatchRows.tsx`
   - `app/src/pages/ChatPage.tsx`: how it lists models, and why it skips
     virtual ids for pricing
   - `app/src/pages/DashboardPage.tsx`
   - `app/src/lib/api.ts`, `app/e2e/mock-api.ts`
3. Backend, read only:
   - `web/RoutingPolicyController.java`, `routing/RoutingPolicyDocuments.java`
     (the policy JSON: rules, limits, default model, `fallbacks` of
     `{primary, alternatives}`), `routing/RoutingPolicyValidator.java` (the
     400 messages)
   - the errors `routing_policies_disabled`, `routing_policies_not_allowed`,
     `routing_policy_invalid`, `routing_policy_limit_reached` and
     `routing_policy_not_found`
   - `web/dto/BatchDtos.java` (`fallbacks`)
4. `marketing/src/pages/docs/index.astro` and `marketing/public/llms-full.txt`,
   for how routing policies are explained to users

## Scope

### 1. Gallery link

- **Header.** A "Gallery" entry, after Recipes, linking to
  `${GALLERY_ORIGIN}/gallery`. Show it only when `loadGalleryIndex()` returns
  at least one recipe: read it once per session, as the example row does. It
  opens in the same tab.
- **Dashboard.** A card, "Start from a recipe", with the same rule and link.
- **Mobile.** The header still fits on mobile with every entry showing:
  Gallery, Invite, Following and Admin.

### 2. Routing policies

- **Settings section.** A "Routing policies" section, shown only when
  `GET /v1/routing/policies` succeeds. Hide it on
  `routing_policies_disabled` or `routing_policies_not_allowed`.
  - It lists the built-in presets (read-only) and the user's policies:
    id, default model, number of rules, number of fallbacks.
  - It has a "New policy" button.
- **Policy page** at `/settings/routing/:id` (and `/settings/routing/new`):
  - id; for new policies only, with the id rule shown
  - the policy as JSON in a monospace editor with line numbers, prefilled
    for a new policy from a small valid example
  - Save (`PUT`), showing the validator's 400 message exactly as returned;
    Delete with a confirmation
  - a short explanation with a link to the docs section, covering:
    - a policy is used by putting its id as a chat model
    - the `X-Godance-Route` header picks a rule
    - `fallbacks` apply to batches that turn them on
  - `routing_policy_limit_reached` shown with the limit
- **Chat model picker.** List the user's policies (not the presets, which
  are already listed) under "Your routing policies", by id. They have no
  per-token price; say so instead of showing one.

### 3. Batch fallbacks

- **The option.** On the new-batch page, a checkbox: "If a row fails at the
  provider, retry it once on the fallback model your routing policy names."
  - Show it only when one of the user's policies has a `fallbacks` entry
    whose `primary` is the batch's model.
  - Name the policy and the alternative models next to it.
  - Checked sends `fallbacks: true`. Unchecked, or not shown, sends nothing.
- **Content refusals.** State that a refusal for content is never retried.
- **Batch page.** It already shows the serving model per row when
  `fallbacks` is true. Confirm it, and add a test.

## Hard rules

- Edits only in the app files reserved for 01 in `00-coordination.md`,
  `app/src/lib/**` and `app/e2e/**`.
- **No backend change.** If an API cannot support something here, stop and
  raise it.
- Every link and section follows "links appear only when usable". Test both
  states.
- Never send `fallbacks: true` unless the user ticked the box.

## Opening move

Run the baseline. Read `Layout.tsx`'s Invite and Following logic and
`ProviderKeysSection.tsx`'s hide rule. Write the failing e2e test: with a
mocked gallery index of one recipe, the header shows Gallery linking to the
gallery origin; with an empty index, it does not. Then post a 3–5 bullet
plan before writing code.

## Acceptance criteria

- `npm run lint`, `npm run build`, `npm run test:e2e` green on desktop and
  mobile.
- **e2e covers:**
  - the Gallery link and card, present and absent
  - the routing section hidden for disabled and for not-allowed
  - list, create, edit, invalid save showing the validator's message,
    delete, and the limit
  - policies in the Chat model picker
  - the fallbacks option shown only for a matching policy
  - the request carrying `fallbacks: true` only when ticked
  - the batch page's model column
- Screenshots of the header on mobile, the routing section, the policy
  editor and the new-batch option, in light and dark.
- All work committed on `feature/app-links`; `main` untouched.

## Out of scope (do not start)

- A visual policy builder.
- Editing presets.
- Fallbacks for single jobs.
- Gallery pages inside the app.
