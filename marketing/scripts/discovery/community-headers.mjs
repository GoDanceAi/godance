import { appendFile } from 'node:fs/promises';

/**
 * Permit credential-free app discovery of the public Community index only.
 * The generated create/integrations routes prove the strict discovery flag
 * resolved true (including .env inputs); the Community route proves its
 * existing loader actually emitted approved readable content. No independent
 * environment parsing or backend capability inference happens in this hook.
 *
 * @returns {import('astro').AstroIntegration}
 */
export function publicCommunityReadHeaders() {
  return {
    name: 'godance-public-community-index-read',
    hooks: {
      'astro:build:done': async ({ dir, pages }) => {
        const emitted = new Set(pages.map(({ pathname }) => pathname.replace(/^\/+|\/+$/g, '')));
        if (!['create', 'integrations', 'community'].every(pathname => emitted.has(pathname))) return;
        await appendFile(new URL('_headers', dir), [
          '',
          '# Public Community index: app discovery reads this without credentials.',
          '/community',
          '  Access-Control-Allow-Origin: *',
          '/community/',
          '  Access-Control-Allow-Origin: *',
          '',
        ].join('\n'));
      },
    },
  };
}
