/**
 * Which licence a list's licence file grants. Read from the text of the
 * file itself at the pinned commit: a badge, a README line or what the host
 * reports is not enough.
 *
 * Returns one of the licences a recipe may carry, or null when the text is
 * missing, is another licence, or adds a restriction (non-commercial, no
 * derivatives, share-alike).
 */
export function detectLicence(text) {
  if (typeof text !== 'string' || text.trim() === '') return null;
  const flat = text.replace(/\s+/g, ' ');

  if (/\bCC0 1\.0 Universal\b/.test(flat) && /Statement of Purpose/.test(flat)) return 'CC0-1.0';

  if (/Creative Commons Attribution 4\.0 International/.test(flat) || /creativecommons\.org\/licenses\/by\/4\.0/.test(flat)) {
    const restricted = /Non-?Commercial|No-?Deriv|Share-?Alike|\bBY-(NC|ND|SA)\b|licenses\/by-(nc|nd|sa)/i.test(flat);
    return restricted ? null : 'CC-BY-4.0';
  }

  if (/\bMIT License\b/.test(flat) && /Permission is hereby granted, free of charge/.test(flat)) return 'MIT';

  if (/Apache License/.test(flat) && /Version 2\.0/.test(flat)) return 'Apache-2.0';

  return null;
}
