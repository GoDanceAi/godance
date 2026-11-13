# Brief 03 — "Run this": the hand-off into the app

**Branch:** `feature/gallery-hand-off` (worktree `../gd-gallery-hand-off`) off `main` once round 7 brief 02 (Studio and History) is merged
**Reserved migration:** none. This brief has no backend or marketing changes.
**Baseline:** `npm run lint`, `npm run build` and `npm run test:e2e` green in `app/` on parent

## Mission (one line)

A visitor who clicks "Run this" on a recipe lands in the Studio with that
recipe loaded, whether they are signed in, signing in or signing up, and an
empty Studio offers examples to start from.

## Why now

The gallery exists to get people generating. Without the hand-off a recipe
page ends at a signup link, and the visitor arrives at an empty prompt box
having lost what they came for. The loss happens at one step in particular:
password sign-in returns a visitor to where they were going, but sign-up and
social sign-in always land on the dashboard.

## Read first

1. This brief and `00-coordination.md` (the JSON endpoints are binding)
2. Round 7 brief 02 and the Studio code it produced, especially how the
   Studio pre-fills from `?model=&prompt=…` and `?job=`
3. `app/src/components/ProtectedRoute.tsx`, `app/src/pages/LoginPage.tsx`,
   `SignupPage.tsx`, `OAuthCallbackPage.tsx` and `app/src/lib/oauth.ts`.
   Read their comments: destinations and messages travel in navigation
   state, and a social sign-in is accepted only with this tab's nonce, so
   that a crafted link cannot steer the app. Keep it that way.
4. `app/src/lib/api.ts`, `app/src/lib/auth.tsx`
5. `app/e2e/mock-api.ts`, `app/e2e/api-origin.ts`, `app/playwright.config.ts`.
   The test harness aborts every request that is not to the app or the API.

## Scope

### 1. Load a recipe into the Studio

- Routes: `/create/image?example=<slug>` and `/create/video?example=<slug>`.
- Check the slug before any request: lowercase letters, digits and hyphens,
  at most 80 characters. Anything else is ignored.
- Fetch `<gallery origin>/gallery/<slug>.json` with no credentials. The
  gallery origin comes from `VITE_GALLERY_URL`, defaulting to
  `https://godance.ai`. Nothing else is ever fetched for a recipe.
- Treat the response as untrusted:
  - check its shape and ignore it when it does not match
  - keep the prompt within the Studio's own length limit
  - take only `size`, `n` and `seconds` from `params`, and only values the
    chosen model's capabilities allow
  - when the model is not in `/v1/models`, use the Studio's default and say so
- A video recipe opened on the image route goes to the video route, and the
  other way round.
- **Loading never generates.** The form is filled in and nothing is
  submitted. A link must not be able to spend anyone's credit.
- Show one line saying it came from the gallery, with the recipe's title
  linking to its `url`.
- Load the language the run used. When the recipe has both, offer a switch
  between English and Chinese, and mark Chinese text `lang="zh-Hans"`.
- A missing recipe, a gallery that is switched off, or a network failure
  opens the Studio empty with a short note. Never an error page.
- When several are present, `?job=` wins, then `?example=`, then the loose
  `?prompt=` parameters.

### 2. Keep the destination through sign-in and sign-up

What happens today:
- `ProtectedRoute` hands the destination to the login page in navigation
  state, and a password sign-in returns there, with or without 2FA.
- Sign-up always goes to `/dashboard` (`SignupPage.tsx`).
- Social sign-in always goes to `/dashboard` (`OAuthCallbackPage.tsx`),
  including when it passes through the 2FA step.

What is required: the destination survives
- the link from the sign-in page to the sign-up page
- a social sign-in, which leaves the app and comes back
- a social sign-in that then asks for a 2FA code

Rules:
- The destination comes only from the route the visitor was actually on.
  It is never read from a URL parameter.
- Where it must outlive a full page load, keep it in this tab's
  `sessionStorage`, as the sign-in nonce already is. When it is read back:
  accept it only if it is a path inside the app, use it once, and ignore it
  when it is more than 30 minutes old.
- An account waiting for parental consent sees the waiting screen, as today.
- When the destination is a recipe link, the sign-in and sign-up pages show
  one fixed line saying so. The words come from the app, not from the link.

### 3. Start from an example

- An empty Studio shows a row of up to six examples for its modality, read
  once per session from `<gallery origin>/gallery/index.json` with no
  credentials: thumbnail and title, plus a link to the gallery.
- Choosing one loads it as in item 1 and puts `?example=` in the URL.
- When the list is empty, the gallery is off or the request fails, the row
  is simply absent.

### 4. Refresh the balance on return

When a Studio tab becomes visible again, re-fetch the account. Credit added
in another tab then shows without a reload. New accounts usually have to
leave the tab to verify an email or top up before their first run.

### 5. Tests

Playwright, with everything mocked.
- Give the e2e build a fake gallery origin through `VITE_GALLERY_URL`, and
  teach the harness to answer it. Every other origin stays blocked.
- Cover, on the desktop and mobile projects:
  - signed in: the recipe link fills the form and sends no generation request
  - signed out, then each of: password sign-in, sign-up, social sign-in,
    social sign-in with 2FA. Each ends in the Studio with the recipe loaded
  - a malformed slug sends no request to the gallery
  - a missing recipe opens the Studio empty with the note
  - an unknown model falls back to the default and says so
  - oversized or unexpected values in the recipe are ignored
  - the wrong route for the recipe's modality redirects
  - the language switch, and its absence when only one language exists
  - the example row present, and absent when the index is empty or fails
  - a stored destination that points off the app, starts with `//`, is
    expired or was already used is ignored
  - no URL parameter can set the destination
  - the balance refreshes when the tab becomes visible

## Hard rules

- Edits only in `app/src/**`, `app/e2e/**`, `app/playwright.config.ts` and
  `app/.env.example`.
- No backend or marketing change. Do not change the JSON endpoints. If the
  app needs something they lack, stop and raise it.
- Loading a recipe never starts a generation.
- Requests to the gallery origin carry no cookies.
- Keep the existing sign-in safeguards exactly as they are: no destination
  or message in the query string, and the nonce check untouched.
- No new dependency.

## Opening move

Run the baseline. Trace each sign-in path from `ProtectedRoute` to the first
signed-in page and list, with file and line, where the destination is lost
today. Then post a 3–5 bullet plan before writing code.

## Acceptance criteria

- `npm run lint`, `npm run build` and `npm run test:e2e` green on both
  Playwright projects, with every case in item 5 covered.
- Screenshots in the final summary: the Studio with a recipe loaded, the
  example row, and the sign-in page showing the recipe line, on desktop and
  phone, in light and dark.
- All work committed on `feature/gallery-hand-off`; `main` untouched.

## Out of scope (do not start)

Recording which recipe a job came from. Measuring how many first runs start
from a recipe. Favourites. Browsing the whole gallery inside the app.
Changing what sign-up requires. Translating the app. Turning "Run this" on
in the marketing site, which is the operator's step once this is live.
