# Round 8 — Opening prompts (paste as the first message of each session)

Order: Session 0 first. Once it is merged, Sessions A and B run at the same
time. Session C runs alongside them as soon as round 7 brief 02 is merged.

## Session 0 — Recipe schema (step zero)

```
You're a Claude Code session working on Godance round-8 brief 00 — Recipe
schema: put the gallery's recipe format into code once (one schema module,
the content collection, a validate command, the test script and the
dependency the import pipeline needs), so the import and pages sessions can
then run at the same time without sharing a file.

Setup: working dir is a dedicated git worktree on branch
feature/gallery-schema, off main. Confirm lint and build are green in
marketing/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-8/00-recipe-schema.md (the brief)
2. docs/claude-code-briefs/round-8/00-coordination.md (the recipe format is
   binding)
3. marketing/package.json, marketing/astro.config.mjs, marketing/.gitignore
4. The content collection docs for the installed Astro version

Hard rules:
- Commit to feature/gallery-schema as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Edits only in the paths the brief lists. No pages, no import tooling, no
  recipes.
- One definition of the format, importable by Astro and by plain Node.
- If the format cannot be expressed as written, stop and raise it.

Opening move: run the baseline. Check how the installed Astro version
defines and validates a content collection, and whether a collection that
no page reads is validated at build. Then summarize your plan in 3 bullets
before writing code.

Done = npm run lint, npm run build and npm test green in marketing/, the
built site has the same routes as before, a malformed recipe shown failing
the build and the validate command, all commits on your branch, main
untouched. Hand back.
```

## Session A — Gallery import

Start after brief 00 is merged to `main`. It can run at the same time as
Session B.

```
You're a Claude Code session working on Godance round-8 brief 01 — Gallery
import: a command-line pipeline that turns entries from licensed open-source
prompt lists into reviewed, bilingual, reproducible gallery recipes, plus
the content standard and licence notice that go with it.

Setup: working dir is a dedicated git worktree on branch
feature/gallery-import, off main with brief 00 already merged. Confirm lint,
build and test are green in marketing/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-8/01-gallery-import.md (the brief)
2. docs/claude-code-briefs/round-8/00-coordination.md (the recipe format is
   binding)
3. marketing/README.md, marketing/package.json, marketing/astro.config.mjs
4. marketing/src/lib/catalog.ts and marketing/src/pages/docs/index.astro
5. docs/claude-code-briefs/round-8/00-recipe-schema.md and the schema
   module and validate command it produced
6. The two source lists named in the brief, read-only, at a commit you pin

Hard rules:
- Commit to feature/gallery-import as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- Every stage defaults to a dry run. NO paid API call without my go-ahead
  and a --max-spend-cents cap.
- You never approve a recipe. You prepare candidates and the review sheet;
  I approve.
- The API key comes from the environment and is written nowhere: no file,
  log, state file or commit.
- Never download or copy a list's own example images or videos. A list
  without a clear licence is never a source.
- Edits only in the paths reserved in 00-coordination.md. Do not touch
  .github/workflows/, backend/ or app/.
- Do NOT edit marketing/package.json, its lockfile, the schema module or
  the collection definition. The pages session is running at the same time.

Opening move: run the baseline. Read both source lists at a pinned commit
without writing anything, and report for each how many entries it has, how
many need an input image, and anything about its shape the brief gets wrong.
Then summarize your build plan in 3–5 bullets before writing code.

Done = npm run lint, npm run build and npm test green in marketing/, a dry
run over both sources that reports counts and projected spend with no paid
call, the content standard and licence notice drafted, all commits on your
branch, main untouched. Hand back and wait for my go-ahead before any paid
stage.
```

## Session B — Gallery pages

Start after brief 00 is merged to `main`. It can run at the same time as
Session A and does not need its output.

```
You're a Claude Code session working on Godance round-8 brief 02 — Gallery
pages: static pages on the marketing site for each gallery recipe, a gallery
page to browse them, model pages, a home-page strip and two JSON endpoints,
all switched off until the operator turns the gallery on.

Setup: working dir is a dedicated git worktree on branch
feature/gallery-pages, off main with brief 00 already merged. Confirm lint,
build and test are green in marketing/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-8/02-gallery-pages.md (the brief)
2. docs/claude-code-briefs/round-8/00-coordination.md (recipe format, JSON
   endpoints and the gallery switch are binding)
3. docs/claude-code-briefs/round-8/00-recipe-schema.md and the schema
   module it produced. The import session is running at the same time, so
   none of its output exists yet
4. marketing/src/layouts/Layout.astro, marketing/src/components/Nav.astro
5. marketing/src/pages/index.astro, models.astro and docs/index.astro
6. marketing/src/styles/global.css, marketing/public/_headers,
   marketing/astro.config.mjs

Hard rules:
- Commit to feature/gallery-pages as you go. Do NOT merge to main. Do NOT
  push or open a pull request unless asked. main auto-deploys.
- The gallery ships OFF. With the config empty, the build must produce no
  gallery route, link or sitemap entry.
- No client framework, no new runtime dependency, no third-party script.
- Do NOT change the recipe format. If a page needs something it lacks,
  stop and raise it.
- A recipe that fails validation fails the build; never skip one quietly.
- Fixtures are invented: no text or media from the source lists.
- Edits only in the paths reserved for 02. Do not touch
  .github/workflows/, marketing/public/llms.txt, backend/ or app/.
- Do NOT edit marketing/package.json, its lockfile, the schema module or
  the collection definition. Page tests go under
  marketing/scripts/gallery-pages/.

Opening move: run the baseline. Write the fixtures, then build once with
the gallery on against them and once with it off, and confirm the off build
has no gallery output. Then summarize your build plan in 3–5 bullets with
the page and component split before writing the pages.

Done = npm run lint, npm run build and npm test green in marketing/, the
on-build and off-build checks in the brief passing, screenshots of the
gallery and of an image and a video recipe on desktop and phone in light
and dark, all commits on your branch, main untouched. Hand back.
```

## Session C — "Run this" hand-off

Start this one only after round 7 brief 02 (Studio and History) is merged to
`main`. It does not need round 8 brief 02.

```
You're a Claude Code session working on Godance round-8 brief 03 — the
"Run this" hand-off: a recipe link opens the app's Studio with that recipe
loaded, the destination survives sign-in and sign-up, and an empty Studio
offers examples from the gallery.

Setup: working dir is a dedicated git worktree on branch
feature/gallery-hand-off, off main with the Studio already merged. Confirm
lint, build and the e2e suite are green in app/ before changing anything.

Read these before writing any code:
1. docs/claude-code-briefs/round-8/03-app-hand-off.md (the brief)
2. docs/claude-code-briefs/round-8/00-coordination.md (the JSON endpoints
   are binding; you read them through mocks)
3. docs/claude-code-briefs/round-7/02-studio-and-history.md and the Studio
   code it produced
4. app/src/components/ProtectedRoute.tsx, app/src/pages/LoginPage.tsx,
   SignupPage.tsx, OAuthCallbackPage.tsx, app/src/lib/oauth.ts — including
   the comments on why destinations and messages stay out of the URL
5. app/e2e/mock-api.ts, app/e2e/api-origin.ts, app/playwright.config.ts

Hard rules:
- Commit to feature/gallery-hand-off as you go. Do NOT merge to main. Do
  NOT push or open a pull request unless asked. main auto-deploys.
- Loading a recipe NEVER starts a generation. It fills the form only.
- The destination comes only from the route the visitor was on. It is never
  read from a URL parameter. Keep the sign-in nonce check untouched.
- Requests to the gallery origin carry no cookies, and the recipe JSON is
  treated as untrusted input.
- Edits only in app/src/**, app/e2e/**, app/playwright.config.ts and
  app/.env.example. No backend or marketing change. No new dependency.
- Do NOT change the JSON endpoints. If the app needs something they lack,
  stop and raise it.

Opening move: run the baseline. Trace each sign-in path from ProtectedRoute
to the first signed-in page and list, with file and line, where the
destination is lost today. Then summarize your build plan in 3–5 bullets
before writing code.

Done = npm run lint, npm run build and npm run test:e2e green on desktop
and mobile with every case in the brief covered, screenshots of the Studio
with a recipe loaded, the example row and the sign-in page's recipe line,
all commits on your branch, main untouched. Hand back.
```
