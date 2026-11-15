import type { ApiKeySummary } from './api';

// A key's limits as a person types and reads them. A cap is typed in
// dollars and sent in whole cents; an expiry is picked as a day and sent as
// the end of that day. Nothing here calls the API (lib/keys.ts does).

/** The daily cap a key made for a recipe starts with: 5.00 USD. */
export const DEFAULT_DAILY_CAP = '5.00';

/** The longest name a key takes. */
export const KEY_NAME_MAX_LENGTH = 255;

/** The most a cap can be: more than this is not a figure anyone means. */
const MAX_CAP_CENTS = 100_000_000;

/**
 * A cap as typed, in cents. Empty means no cap (null). "5", "5.5" and
 * "5.00" are dollars; anything else, an amount under one cent, or one with
 * more than two decimals is `'invalid'`.
 */
export function capCents(text: string): number | null | 'invalid' {
  const typed = text.trim().replace(/^\$\s*/, '');
  if (typed === '') return null;
  const match = /^(\d{1,9})(?:\.(\d{1,2}))?$/.exec(typed);
  if (!match) return 'invalid';
  const cents = Number(match[1]) * 100 + Number((match[2] ?? '').padEnd(2, '0'));
  return cents >= 1 && cents <= MAX_CAP_CENTS ? cents : 'invalid';
}

/** A cap as the field shows it: "5.00". Empty for no cap. */
export function capText(cents: number | null | undefined): string {
  if (cents === null || cents === undefined) return '';
  return `${Math.floor(cents / 100)}.${String(cents % 100).padStart(2, '0')}`;
}

export const CAP_INVALID = 'Enter an amount in dollars, such as 5.00, or leave it empty for no cap.';

const DAY = /^(\d{4})-(\d{2})-(\d{2})$/;

/**
 * The expiry for a day picked in a date field ("2027-01-31"): the last
 * second of that day, where the user is. Empty means the key never expires
 * (null). A day that is already over, or not a date, is `'invalid'`.
 */
export function expiryOf(day: string, now: number = Date.now()): string | null | 'invalid' {
  if (day.trim() === '') return null;
  const match = DAY.exec(day.trim());
  if (!match) return 'invalid';
  const end = new Date(Number(match[1]), Number(match[2]) - 1, Number(match[3]), 23, 59, 59);
  if (Number.isNaN(end.getTime()) || end.getTime() <= now) return 'invalid';
  // In UTC and to the second, as the API keeps it.
  return end.toISOString().replace(/\.\d{3}Z$/, 'Z');
}

/** The day an expiry falls on, where the user is, as a date field takes it. Empty for none. */
export function dayOf(expiresAt: string | null | undefined): string {
  if (!expiresAt) return '';
  const at = new Date(expiresAt);
  if (Number.isNaN(at.getTime())) return '';
  const two = (n: number) => String(n).padStart(2, '0');
  return `${at.getFullYear()}-${two(at.getMonth() + 1)}-${two(at.getDate())}`;
}

/** Today, as the earliest day a date field offers. */
export function today(now: number = Date.now()): string {
  return dayOf(new Date(now).toISOString());
}

export const EXPIRY_INVALID = 'Pick a day from today on, or leave it empty for a key that does not expire.';

type Expiry = Pick<ApiKeySummary, 'expires_at' | 'expiresAt'>;

/** A key's expiry: `expires_at`, or the older `expiresAt` from an API without the caps. */
export function expiryOfKey(key: Expiry): string | null {
  return key.expires_at ?? key.expiresAt ?? null;
}

/** Whether a key has stopped working because its expiry has passed. */
export function isExpired(key: Expiry, now: number = Date.now()): boolean {
  const expiry = expiryOfKey(key);
  return expiry !== null && Date.parse(expiry) <= now;
}

/** Whether the API this key came from has caps at all: one without them sends none of the fields. */
export function hasCaps(key: ApiKeySummary): boolean {
  return key.daily_cap_cents !== undefined || key.monthly_cap_cents !== undefined
    || key.spent_today_cents !== undefined || key.spent_month_cents !== undefined;
}

/**
 * How far a key's spend is into its cap, for the colour it is shown in:
 * `over` once the cap is reached (the next request is refused), `near` from
 * 80% of it, `ok` below that and when there is no cap.
 */
export function capState(spentCents: number, capCentsOrNone: number | null | undefined): 'ok' | 'near' | 'over' {
  if (capCentsOrNone === null || capCentsOrNone === undefined) return 'ok';
  if (spentCents >= capCentsOrNone) return 'over';
  return spentCents * 5 >= capCentsOrNone * 4 ? 'near' : 'ok';
}
