import { useEffect, useRef } from 'react';
import { apiFetch } from './api';
import { useAfterSignIn } from './after-sign-in';
import { useAuth } from './auth';
import { isRecipeSlug } from './recipe';

// First touch: what brought a visitor to the app before they had an account.
// Campaign tags from the link, the gallery recipe a "Run this" link named,
// the page they landed on and the site that sent them. It is kept in this
// browser until someone signs in, handed to the API once, and forgotten.
// The API keeps the first one it is given for a new account and ignores the
// rest. Nothing else is recorded, and no third party is involved.

/** POST /v1/attribution/first-touch. Every field is optional; at least one is sent. */
export interface FirstTouch {
  utm_source?: string;
  utm_medium?: string;
  utm_campaign?: string;
  gallery_recipe?: string;
  landing_path?: string;
  referrer_host?: string;
}

const STORAGE_KEY = 'godance.first-touch';
// As long as an invite code is kept (lib/referral): a social sign-in round
// trip, or an email opened days later.
const KEEP_MS = 30 * 24 * 60 * 60 * 1000;
// The API takes at most 100 printable characters in each field.
const FIELD_MAX = 100;
const FIELDS = ['utm_source', 'utm_medium', 'utm_campaign', 'gallery_recipe', 'landing_path', 'referrer_host'] as const;
// Control characters and the line and paragraph separators: none is printable.
const UNPRINTABLE = new RegExp('[\\u0000-\\u001f\\u007f-\\u009f\\u2028\\u2029]', 'g');
// The Studio's routes, where ?example=<slug> names a gallery recipe.
const STUDIO_PATH = /^\/create(\/(image|video))?\/?$/i;

function clean(value: string | null | undefined): string | undefined {
  const text = value?.replace(UNPRINTABLE, '').trim() ?? '';
  return text === '' ? undefined : Array.from(text).slice(0, FIELD_MAX).join('');
}

function hostOf(url: string): string | null {
  try {
    return new URL(url).hostname.toLowerCase() || null;
  } catch {
    return null;
  }
}

/**
 * What a page load says about where the visit came from. The path goes
 * without its query, which can carry a token (a reset or verification link).
 * The referrer counts only when it is another site.
 */
export function readFirstTouch(
  page: { pathname: string; search: string; hostname: string },
  referrer: string,
): FirstTouch {
  const params = new URLSearchParams(page.search);
  const touch: FirstTouch = {};
  for (const tag of ['utm_source', 'utm_medium', 'utm_campaign'] as const) {
    const value = clean(params.get(tag));
    if (value) touch[tag] = value;
  }
  const example = params.get('example');
  if (STUDIO_PATH.test(page.pathname) && isRecipeSlug(example)) touch.gallery_recipe = example;
  const path = clean(page.pathname);
  if (path) touch.landing_path = path;
  const from = hostOf(referrer);
  if (from && from !== page.hostname.toLowerCase()) touch.referrer_host = clean(from);
  return touch;
}

// Read as the bundle loads, before the router moves a signed-out visitor to
// the sign-in page and the address no longer says where they landed.
const LANDED: FirstTouch = readFirstTouch(window.location, document.referrer);

/** The kept first touch, or null. An expired or unreadable one is dropped. */
export function storedFirstTouch(): FirstTouch | null {
  try {
    const raw = localStorage.getItem(STORAGE_KEY);
    if (raw === null) return null;
    let kept: { touch?: unknown; expires?: unknown } | null = null;
    try {
      kept = JSON.parse(raw) as { touch?: unknown; expires?: unknown };
    } catch {
      kept = null;
    }
    const touch = kept && typeof kept.expires === 'number' && kept.expires > Date.now() ? touchOf(kept.touch) : null;
    if (!touch) localStorage.removeItem(STORAGE_KEY);
    return touch;
  } catch {
    return null;
  }
}

/** Only the fields the API takes, each a non-empty string; null when none is left. */
function touchOf(value: unknown): FirstTouch | null {
  if (typeof value !== 'object' || value === null) return null;
  const touch: FirstTouch = {};
  for (const field of FIELDS) {
    const text = (value as Record<string, unknown>)[field];
    if (typeof text === 'string') {
      const kept = clean(text);
      if (kept) touch[field] = kept;
    }
  }
  return Object.keys(touch).length > 0 ? touch : null;
}

/** Keep `touch`, unless one is kept already: the first touch wins. */
function keepFirstTouch(touch: FirstTouch): void {
  if (Object.keys(touch).length === 0 || storedFirstTouch() !== null) return;
  try {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ touch, expires: Date.now() + KEEP_MS }));
  } catch {
    // Storage blocked: there is nowhere to keep it.
  }
}

let claiming: Promise<void> | null = null;

/**
 * Hand the kept first touch in for the signed-in account, once, and forget
 * it whatever the answer: the API decides whether it is the account's first,
 * and one it refused is no use later. Nothing is shown either way.
 */
function claimFirstTouch(): Promise<void> {
  if (claiming) return claiming;
  const touch = storedFirstTouch();
  if (!touch) return Promise.resolve();
  claiming = apiFetch('/v1/attribution/first-touch', { method: 'POST', json: touch })
    .then(() => undefined, () => undefined)
    .finally(() => {
      try {
        localStorage.removeItem(STORAGE_KEY);
      } catch {
        // Storage blocked: nothing was kept.
      }
      claiming = null;
    });
  return claiming;
}

/**
 * Once per page load, when the API has said whether anyone is signed in: a
 * visitor's landing is kept, and a signed-in account's is not, since it
 * says nothing about what brought the account here. Then, whenever an
 * account becomes signed in, the kept one is handed in.
 */
export function useFirstTouch(): void {
  const { user, loading } = useAuth();
  const decided = useRef(false);
  useEffect(() => {
    if (loading || decided.current) return;
    decided.current = true;
    if (!user) keepFirstTouch(LANDED);
  }, [loading, user]);

  useAfterSignIn(() => {
    void claimFirstTouch();
  });
}
