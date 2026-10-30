// Two origins on one site, as production's app.godance.ai and api.godance.ai
// are, so the browser's own cookie rules apply: SameSite lets the app's fetch
// carry the session cookie, and credentialed CORS needs the exact origin.
// Chromium treats *.localhost as secure, so a Secure cookie works over http.
export const APP_HOST = 'app.godance.localhost';

// The e2e build's VITE_API_URL. It never resolves: every request to it is
// answered in the browser by MockApi (mock-api.ts).
export const API_ORIGIN = 'http://api.godance.localhost';
