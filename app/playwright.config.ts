import { defineConfig, devices } from '@playwright/test';
import { API_ORIGIN } from './e2e/api-origin';

// Browser journeys against a production build of the app. The API is never
// real: the build points VITE_API_URL at a fake origin and every test mocks
// it in the browser with page.route (see e2e/mock-api.ts). Nothing leaves
// localhost.
const PORT = 4174;
const CI = !!process.env.CI;

export default defineConfig({
  testDir: './e2e',
  fullyParallel: true,
  forbidOnly: CI,
  retries: CI ? 1 : 0,
  reporter: CI
    ? [['github'], ['html', { open: 'never' }]]
    : [['list'], ['html', { open: 'never' }]],
  use: {
    baseURL: `http://127.0.0.1:${PORT}`,
    trace: 'retain-on-failure',
    // Backstop for what page.route can't see (e.g. <link rel=preconnect>):
    // no hostname but localhost resolves.
    launchOptions: {
      args: ['--host-resolver-rules=MAP * ~NOTFOUND, EXCLUDE 127.0.0.1, EXCLUDE localhost'],
    },
  },
  projects: [
    { name: 'desktop', use: { ...devices['Desktop Chrome'] } },
    // Chromium-based, so CI installs one browser for both projects.
    { name: 'mobile', use: { ...devices['Pixel 7'] } },
  ],
  // Its own outDir, so the e2e build (fake API origin) never overwrites dist/.
  webServer: {
    command:
      `npx vite build --outDir dist-e2e --emptyOutDir --logLevel warn && ` +
      `npx vite preview --outDir dist-e2e --host 127.0.0.1 --port ${PORT} --strictPort`,
    url: `http://127.0.0.1:${PORT}`,
    env: { VITE_API_URL: API_ORIGIN },
    reuseExistingServer: false,
    timeout: 120_000,
  },
});
