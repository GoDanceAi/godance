// @ts-check
import { defineConfig } from 'astro/config';
import tailwind from '@astrojs/tailwind';
import sitemap from '@astrojs/sitemap';

// Site URL is used by @astrojs/sitemap and by structured-data canonical URLs.
// Override at build time via SITE_URL env var when deploying to staging / preview.
const SITE_URL = process.env.SITE_URL || 'https://godance.ai';

export default defineConfig({
  site: SITE_URL,
  output: 'static',
  integrations: [tailwind(), sitemap()],
  build: {
    inlineStylesheets: 'auto',
  },
  vite: {
    envPrefix: 'PUBLIC_',
  },
});
