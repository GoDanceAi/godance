// @ts-check
import { defineConfig } from 'astro/config';
import tailwindcss from '@tailwindcss/vite';
import sitemap from '@astrojs/sitemap';

// Site URL is used by @astrojs/sitemap and by structured-data canonical URLs.
// Override at build time via SITE_URL env var when deploying to staging / preview.
const SITE_URL = process.env.SITE_URL || 'https://godance.ai';

export default defineConfig({
  site: SITE_URL,
  output: 'static',
  // /legal/* are noindex placeholders until the real policies are published;
  // drop this filter (and their noindex) when they are.
  integrations: [sitemap({ filter: (page) => !page.includes('/legal/') })],
  // Astro 7 defaults to 'jsx', which also strips whitespace inside inline
  // elements; true keeps the Astro 5 output.
  compressHTML: true,
  build: {
    inlineStylesheets: 'auto',
  },
  vite: {
    plugins: [tailwindcss()],
    envPrefix: 'PUBLIC_',
  },
});
