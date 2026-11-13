// Dates as the app shows them. English, like the rest of the app.

const DATE = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium' });
const DATE_TIME = new Intl.DateTimeFormat('en-US', { dateStyle: 'medium', timeStyle: 'short' });
const RELATIVE = new Intl.RelativeTimeFormat('en-US', { numeric: 'auto' });

function parse(iso: string): number | null {
  const t = Date.parse(iso);
  return Number.isNaN(t) ? null : t;
}

/** "Feb 10, 2027". Empty for a value that is not a date. */
export function formatDate(iso: string): string {
  const t = parse(iso);
  return t === null ? '' : DATE.format(t);
}

/** "Feb 10, 2027, 9:00 AM". Empty for a value that is not a date. */
export function formatDateTime(iso: string): string {
  const t = parse(iso);
  return t === null ? '' : DATE_TIME.format(t);
}

/** "just now", "5 minutes ago", "yesterday"; the date once it is a week old. */
export function formatAge(iso: string, now: number = Date.now()): string {
  const t = parse(iso);
  if (t === null) return '';
  const seconds = Math.round((now - t) / 1000);
  if (seconds < 45) return 'just now';
  const minutes = Math.round(seconds / 60);
  if (minutes < 60) return RELATIVE.format(-minutes, 'minute');
  const hours = Math.round(minutes / 60);
  if (hours < 24) return RELATIVE.format(-hours, 'hour');
  const days = Math.round(hours / 24);
  if (days < 7) return RELATIVE.format(-days, 'day');
  return DATE.format(t);
}
