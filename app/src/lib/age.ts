// Mirrors ParentalConsentService.needsConsent on the backend. Under 16 needs
// a parent's consent: one global threshold (GDPR Art. 8's highest age, which
// also covers COPPA's 13). Only the birth month is known, so the whole birth
// month counts as before the birthday: 16 from the first day of the month
// after it. UTC, as on the server.
export const CONSENT_AGE = 16;

/** month is 1–12. */
export function needsParentalConsent(year: number, month: number, now: Date = new Date()): boolean {
  // Calendar months counted as year * 12 + zero-based month. A one-based
  // `month` used as a zero-based one is the month after the birth month.
  const surelySixteen = (year + CONSENT_AGE) * 12 + month;
  const today = now.getUTCFullYear() * 12 + now.getUTCMonth();
  return today < surelySixteen;
}

/** YYYY-MM, as POST /v1/auth/signup takes it. */
export function birthMonthParam(year: number, month: number): string {
  return `${year}-${String(month).padStart(2, '0')}`;
}
