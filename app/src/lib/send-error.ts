import { ApiError } from './api';

/**
 * Why a paid request (a chat message, a generation) was refused. Not enough
 * credit gets a way to add some; any other error, a spending cap's 402
 * included (topping up doesn't lift a cap), shows the API's reason.
 */
export type SendError = { outOfCredit: true } | { outOfCredit: false; message: string };

export function sendError(err: unknown, fallback: string): SendError {
  if (err instanceof ApiError && err.status === 402 && err.code === 'insufficient_credit') {
    return { outOfCredit: true };
  }
  return { outOfCredit: false, message: err instanceof ApiError ? err.detail || err.message : fallback };
}
