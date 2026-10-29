# Godance — App

User-facing app for `app.godance.ai`. Vite + React 18 + TypeScript + Tailwind +
React Router, deployed to Cloudflare Pages alongside the marketing site.

## Stack rationale

- **Vite + React + TS** — fast dev server, ES2022 target, no SSR (logged-in app pages
  don't need SEO; in fact `<meta name="robots" content="noindex">` keeps them out of
  search).
- **Tailwind** — same `tailwind.config.js` palette as `frontend-marketing/` so the
  brand presentation is consistent.
- **React Router v6** with a `ProtectedRoute` wrapper and a centralised `AuthProvider`
  context. JWT stored in `localStorage`; backend 401s dispatch a custom event that
  the context listens for and clears state.
- **No state library, no UI library, no form library** by design — skeleton stays
  tiny and grows on demand.

## Local dev

```bash
cp .env.example .env.local       # one-time; tweaks VITE_API_URL if needed
npm install
npm run dev                       # http://localhost:5173
```

Backend (`godance/`) should be running on `http://localhost:8080` — Spring Boot
side has CORS open in dev via Spring Security defaults for the
`localhost:5173` origin (verify when wiring real CORS in Session 12).

## Build / preview

```bash
npm run build         # tsc -b && vite build → ./dist/
npm run preview       # serve ./dist on http://localhost:4173
npm run lint          # tsc -b: typecheck src/, vite.config.ts and e2e/, no emit
npm run test:e2e      # Playwright browser journeys, desktop + mobile, API mocked
```

`test:e2e` builds the app into `dist-e2e/` with `VITE_API_URL=http://api.test`, a
fake origin that every spec mocks with `page.route` (helpers in `e2e/mock-api.ts`),
so it needs no backend. First run: `npx playwright install chromium`.

## Deploy to Cloudflare Pages

```bash
# Via wrangler:
npx wrangler pages deploy dist --project-name=godance-app

# Or via the dashboard:
#   Build command:    npm run build
#   Output directory: dist
#   Node version:     20+
#   Environment vars: VITE_API_URL=https://api.godance.ai
```

`public/_redirects` ships an SPA fallback (`/* → /index.html` 200) so React-Router
client-side routes don't 404 on direct visits.

## Project layout

```
frontend-app/
├── vite.config.ts            # Vite + React + Tailwind plugins
├── tailwind.config.js        # Same palette as marketing
├── tsconfig.{,app,node}.json # Strict TS with project references
├── index.html                # Vite entry HTML
├── public/
│   ├── favicon.svg
│   ├── _headers              # CF Pages security + cache headers
│   └── _redirects            # SPA fallback
└── src/
    ├── main.tsx              # createRoot + BrowserRouter
    ├── App.tsx               # Routes + AuthProvider
    ├── index.css             # Tailwind import + design tokens
    ├── lib/
    │   ├── api.ts            # apiFetch wrapper + streamChat generator + DTOs
    │   └── auth.tsx          # AuthContext (login/signup/logout/refresh)
    ├── components/
    │   ├── Layout.tsx        # Nav + main + Footer
    │   └── ProtectedRoute.tsx
    └── pages/
        ├── LoginPage.tsx
        ├── SignupPage.tsx
        ├── VerifyEmailPage.tsx
        ├── DashboardPage.tsx
        ├── ChatPage.tsx      # fetch + ReadableStream (NOT EventSource — see Session 7)
        ├── ApiKeysPage.tsx
        ├── BillingPage.tsx
        └── SettingsPage.tsx  # Webhook signing secret display + rotate
```

## Streaming chat — why not EventSource?

The browser `EventSource` API is GET-only and can't set custom headers. Our
`/v1/chat/completions` endpoint is POST + JSON body + `Authorization: Bearer`.
Instead `src/lib/api.ts#streamChat` uses `fetch()` with a `ReadableStream` reader
and an SSE parser to consume the streaming response while keeping POST/JSON/Bearer
semantics. See PROGRESS.md Session 7 for the original lesson.
