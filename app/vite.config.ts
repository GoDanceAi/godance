import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';

// Backend API URL is configurable so dev/preview can point at localhost,
// staging, or prod without code changes. Vite exposes VITE_* vars to the client.
export default defineConfig({
  plugins: [react()],
  envPrefix: 'VITE_',
  server: {
    port: 5173,
    strictPort: true,
  },
  build: {
    target: 'es2022',
    sourcemap: true,
  },
});
