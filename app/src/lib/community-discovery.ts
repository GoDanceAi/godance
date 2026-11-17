import { GALLERY_ORIGIN } from './gallery';

export const COMMUNITY_DESTINATION = `${GALLERY_ORIGIN}/community`;

/**
 * Backend account eligibility does not prove the public site's build has
 * community pages. Offer its index only after a readable successful page
 * contains a link to a published recipe on that site. A CORS denial, empty
 * page, error or redirect to another origin/path fails closed; no session or provider is probed.
 */
export async function publicCommunityAvailable(signal: AbortSignal): Promise<boolean> {
  try {
    const response = await fetch(COMMUNITY_DESTINATION, {
      credentials: 'omit', redirect: 'follow', signal,
    });
    if (!response.ok || !response.headers.get('content-type')?.includes('text/html')) return false;
    const final = new URL(response.url);
    if (final.origin !== GALLERY_ORIGIN || !['/community', '/community/'].includes(final.pathname)) return false;
    const page = new DOMParser().parseFromString(await response.text(), 'text/html');
    return [...page.querySelectorAll('main a[href]')].some((link) => {
      const target = new URL(link.getAttribute('href') ?? '', COMMUNITY_DESTINATION);
      return target.origin === GALLERY_ORIGIN && /^\/community\/[a-z0-9][a-z0-9-]{0,63}\/?$/.test(target.pathname);
    });
  } catch {
    return false;
  }
}
