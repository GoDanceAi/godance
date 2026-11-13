import { API_BASE } from './api';

// curl for the requests the app makes, to run with an API key. The key is
// always this placeholder: the app never shows a real one.

export const API_KEY_VAR = '$GODANCE_API_KEY';
export const JOB_ID_VAR = '$JOB_ID';

/** One single-quoted shell word. */
function shellWord(s: string): string {
  return `'${s.replaceAll("'", `'\\''`)}'`;
}

export function postCurl(path: string, body: unknown): string {
  return [
    `curl ${API_BASE}${path} \\`,
    `  -H "Authorization: Bearer ${API_KEY_VAR}" \\`,
    `  -H "Content-Type: application/json" \\`,
    // The JSON body indented under -d.
    `  -d ${shellWord(JSON.stringify(body, null, 2).replaceAll('\n', '\n  '))}`,
  ].join('\n');
}

export function getCurl(path: string): string {
  return [
    `curl ${API_BASE}${path} \\`,
    `  -H "Authorization: Bearer ${API_KEY_VAR}"`,
  ].join('\n');
}
