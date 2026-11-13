import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import tailwindcss from '@tailwindcss/vite';

// The commit this build came from: CI sets GITHUB_SHA, local builds say "dev".
// main.tsx puts it on <html data-build>, so anyone can read which build is
// live. Being in the bundle, it also gives every deploy a new bundle name, so
// a browser holding a bad copy of an old bundle loads a fresh one on the next
// deploy (one had cached index.html in place of the bundle on 2026-11-11).
const build = process.env.GITHUB_SHA?.slice(0, 7) || 'dev';

// Backend API URL is configurable so dev/preview can point at localhost,
// staging, or prod without code changes. Vite exposes VITE_* vars to the client.
export default defineConfig({
  plugins: [react(), tailwindcss()],
  envPrefix: 'VITE_',
  define: {
    __GODANCE_BUILD__: JSON.stringify(build),
  },
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    target: 'es2022',
    sourcemap: true,
  },
});
