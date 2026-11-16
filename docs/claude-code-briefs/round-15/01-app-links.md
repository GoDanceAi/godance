# 01 — App navigation and discovery

## Outcome

An eligible signed-in user can find the existing Studio, history/saved outputs, recipes, batches, API/MCP tools and account pages on desktop and mobile. Existing deep links keep working. Discovery should explain the workflow, not create another list of unavailable features.

Read `00-coordination.md`, `session-prompts.md`, `app/src/App.tsx`, `components/Layout.tsx`, `pages/DashboardPage.tsx`, `pages/RecipesPage.tsx`, `components/RecipesTabs.tsx`, `pages/FollowingPage.tsx`, `pages/StudioPage.tsx`, `components/SavedRecipes.tsx`, `lib/{gallery,saved-recipes,community-context,referral,after-sign-in,return-to}.ts*`, and navigation/first-touch tests.

## Scope

- Improve the existing app shell into clear groups: Create (Image, Video, Chat), Library (History/Saved, Recipes/Batches), Developer tools (API keys/MCP documentation), Account (Billing, Settings). Keep the structure compact; a group can expand rather than making every destination a primary item.
- Preserve Dashboard and the existing `/create` redirect, all existing URLs, active states, sign-in return destinations and first-touch attribution.
- Give Batches a clear discoverable entry while retaining Recipes/Batches subnavigation. No new route is needed.
- Refresh Dashboard with the existing workflow: create → compare in History → save a recipe → run as batch or use Run at scale. Explain that the panel already has code, assistant and n8n options. Remove the stale next-release usage promise; 02 owns actual billing usage UI.
- Give saved gallery bookmarks a persistent home within the existing Recipes/library screen. Distinguish them from the user's versioned recipes and from saved generated outputs. Reuse current bookmark APIs and unavailable-example handling.
- Following's empty state offers community discovery only when the community is open and the existing public destination is usable. Reuse existing community information; do not manufacture authors/content.
- Keep existing input/BYOK/profile/Publish controls and their gates. Add appropriate contextual guidance to their existing homes; do not duplicate their forms.

## Rules

Follow the shared features contract. Core pages with legitimate empty states stay linked. Optional feature loading/error/denied states do not enable links. Reuse current account-scoped providers; ignore stale responses across logout/account changes.

Compare remains History selection → existing Compare flow. MCP discovery points to API keys/Use with MCP or accurate docs; no session probe to `/mcp`. Routing policies have no app editor; never invent a route. Do not display the ADMIN-only feature-status data in customer navigation.

The mobile shell must expose the same permitted destinations through a visible, keyboard-usable mechanism, rather than relying on undiscoverable horizontal overflow. Support screen-reader names, focus, current-page indication and light/dark themes. Onboarding/sign-in redirects remain valid destinations.

Do not edit BillingPage, ChatPage, any part of App.tsx, admin navigation, backend code, payment actions, feature defaults or marketing files. 05 owns the single new App.tsx admin route. Prefer a focused navigation module/components without changing global styling unless necessary and explained.

## Acceptance and evidence

1. Every core destination is reachable for an eligible user on desktop and mobile, has the correct active state, and retains existing URLs.
2. Test optional links shown and hidden for community/referral gates, loading, denied/error results and account switching. No stale feature data from a previous account.
3. A signed-out deep link returns to the same destination after sign-in; pending-consent users retain their waiting screen. Existing input age/terms rules hold.
4. Recipes, Batches and Developer tools are discoverable from the shell or Dashboard. Dashboard guidance leads to actual existing actions, without causing a generation or payment.
5. Saved gallery bookmarks remain accessible after a Studio run and after reload, and have truthful empty/unavailable/removal states. Saved outputs remain discoverable in History.
6. Compare guidance works with selection; no fabricated IDs. Community discovery never links to a known-disabled/empty destination.
7. Existing e2e flows still pass. Run app lint/build and the full app E2E suite; include representative desktop/mobile light/dark screenshots and keyboard/focus checks.
8. No backend calls that create jobs, accept terms, save keys or initiate payments occur just from navigation.

Opening move: inventory the current routes and permission sources, then post the 3–5 bullet plan and destination map. Write failing tests for the new navigation's core path and one enabled/disabled optional link before changing the shell.
