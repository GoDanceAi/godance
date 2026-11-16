# Round 15 — Coordination

**Drafted:** 2026-11-16 · **Parent:** `main` @ `c4a1c08` (rounds 7–14 merged; update if `main` has moved)
**Workstreams:**
- 01 App links (app)
- 02 Billing screens (app)
- 03 Chat history (app)
- 04 Site links and docs (marketing, CI)
- 05 Admin switches panel (backend, app)

Context: rounds 1–14 built more than either frontend lets people reach. An
audit of `main` at `c4a1c08` found three kinds of gap.

**1. Linked, but hidden behind a switch that ships off.** Nothing is missing
in code. The operator cannot see which switches are on, and the marketing
deploy passes none of the gallery or community switches. Brief 05 shows the
switches; brief 04 lets the deploy read them.

| Hidden | Switch that hides it |
|---|---|
| Invite | referral rewards |
| Inputs, Animate this, Describe as prompt | `INPUTS_ENABLED` |
| Following, Profile, Publish, Report, Admin Community | `COMMUNITY_ENABLED` |
| Provider keys | `BYOK_PROVIDERS` |
| The gallery, comparisons, Run this, the Studio's example row | gallery config, plus at least one published recipe |

**2. Built, with no link or screen.**

| API | What is missing |
|---|---|
| `/v1/routing/policies` | No screen, and policies are not in the Chat model picker |
| `fallbacks` on `POST /v1/recipe-batches` | No option on the new-batch page |
| `GET /v1/usage` | No usage page |
| `GET` and `PUT /v1/billing/auto-recharge` | Nothing in Billing |
| `/v1/conversations` | Chat never saves, lists or reopens a conversation |
| The site's community pages | Nothing on the site links to them |
| The site's comparison index | Not linked from the gallery |
| The app and the gallery | No standing link between them |

**3. Documentation gaps.**
- The docs page lacks routing policies and the public community API.
- `llms-full.txt` lacks bring your own key and the public community API.

This round closes all three. It adds no new product behaviour: every
screen calls an API that exists today, and everything that ships off stays
off.

## Worktrees (one per agent — never share a checkout)

```bash
git worktree add ../gd-app-links  -b feature/app-links main
git worktree add ../gd-billing-ui -b feature/billing-screens main
git worktree add ../gd-chat-hist  -b feature/chat-history main
git worktree add ../gd-site-links -b feature/site-links main
git worktree add ../gd-switches   -b feature/admin-switches main
```

## Reserved resources

| Resource | 01 app links | 02 billing | 03 chat | 04 site | 05 switches |
|---|---|---|---|---|---|
| Flyway | none | none | none | none | none |
| Backend | none | none | none | none | new `web/AdminFeaturesController`, `service/admin/Features*` |
| App | `Layout.tsx` (Gallery entry), `SettingsPage.tsx` (routing section), new `pages/RoutingPolicyPage.tsx`, `NewBatchPage.tsx`, `ChatPage.tsx` (model picker only), `DashboardPage.tsx` (one card) | `BillingPage.tsx`, new `pages/UsagePage.tsx`, `App.tsx` (one route), `DashboardPage.tsx` (one link) | `ChatPage.tsx` (history), new chat components, `App.tsx` (one route) | none | `pages/admin/AdminLayout.tsx`, new `pages/admin/SwitchesPage.tsx`, `App.tsx` (one route) |
| Marketing | none | none | none | `components/Nav.astro`, `components/Footer.astro`, `pages/gallery/[...index].astro`, `pages/docs/index.astro`, `public/llms.txt`, `public/llms-full.txt`, `scripts/llms/**` | none |
| CI | none | none | none | `.github/workflows/deploy-frontends.yml` (env only) | none |

## Shared contract

Additions only. Nothing that exists today changes shape.

### Admin switches (05)

```json
GET /v1/admin/features        (SUPPORT, FINANCE, ADMIN)
{ "region": "local",
  "referral":       { "enabled": false, "referrer_reward_cents": 0, "referee_reward_cents": 0 },
  "signup_credit":  { "enabled": false, "cents": 0 },
  "images":         { "new_key_response": "async" },
  "inputs":         { "enabled": false, "allow_people": false, "check_model": null },
  "community":      { "enabled": false },
  "byok":           { "providers": [] },
  "routing_user_policies": { "enabled": false, "allow_list_size": 0 } }
```

- **Values only.** Never a secret, a key, a URL with credentials, or who is on
  an allow-list.
- **Read-only.** Changing a switch stays a deployment change.

### Links appear only when usable

A link or screen added this round appears only when its feature works for
the signed-in user. That is the pattern `Layout.tsx` already follows for
Invite and Following. No link may lead to a page that then says the feature
is off.

## Shared-file conflict watch

- **`app/src/pages/DashboardPage.tsx`:** 01 adds a gallery card; 02 adds a
  usage link. 02 rebases.
- **`app/src/pages/ChatPage.tsx`:** 01 changes only the model list; 03 adds
  history. 03 rebases onto 01.
- **`app/src/App.tsx`:** routes only. 01 adds the routing policy pages, and
  02, 03 and 05 add one route each.
- **`app/src/lib/api.ts`, `app/e2e/mock-api.ts`:** 01, 02, 03 and 05.

## Hard rules (all sessions)

- Commit to your own branch as you go. Do not merge to `main`. Do not push
  or open a pull request unless the operator asks. `main` auto-deploys.
- **No backend change except brief 05's read-only route.** No migration. No
  switch default changes.
- **Checks stay green:**
  - app: `npm run lint`, `npm run build`, `npm run test:e2e`
  - marketing: `npm ci && npm run lint && npm run build && npm test`
  - backend (05): `cd backend && docker compose up -d`, then `mvn -B verify`
- **e2e coverage.** Every new link has a test that it shows when its feature
  is usable and is absent when it is not.
- **No new runtime dependency** without flagging it first.
- Use existing theme tokens and classes, in light and dark, desktop and
  mobile. New controls are labelled and keyboard-reachable.
- **Money.** Amounts come from the API in cents and are shown with
  `formatCents`. Nothing that can charge a card happens without an explicit
  confirmation that states the amount.
- Public repo: no secrets, private URLs or operator notes in committed files.

## Merge order

Independent. 02 rebases onto 01 for the dashboard, and 03 rebases onto 01
for the chat page.

## Operator decisions

- **Which switches to turn on, where.** After 04 merges, the marketing build
  reads the gallery and community switches from repository variables:
  `GALLERY_MEDIA_BASE_URL`, `GALLERY_REMOVAL_CONTACT`,
  `GALLERY_OUTPUT_LICENCE`, `GALLERY_RUN_THIS`, `GALLERY_COMPARE_MIN`,
  `COMMUNITY_ENABLED`. Unset means the committed default, which is off.
- **`providerCostCents` in `GET /v1/usage`.** It shows every caller what
  Godance pays the provider. 02's page does not display it. Decide whether
  the API should stop returning it; that change is not in this round.
- **How long chat history is kept.** The privacy policy must say it is
  stored, and for how long.
- **Wording for auto-recharge consent.** 02 ships a draft in one constant:
  what is charged, when, and how to turn it off.
