import { API_BASE } from './api';
import { curlGet, curlPost } from './run-code';

// curl for the requests the app makes, to run with an API key. The key is
// always a placeholder: the app never shows a real one. The commands are
// written by lib/run-code.ts, which writes the Python and the Java too.

export { API_KEY_VAR, JOB_ID_VAR } from './run-code';

export function postCurl(path: string, body: unknown): string {
  return curlPost(API_BASE, path, body);
}

export function getCurl(path: string): string {
  return curlGet(API_BASE, path);
}
