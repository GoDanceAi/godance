import { apiFetch, API_BASE, ApiError, apiErrorFrom, UNAUTHORIZED_EVENT, type ChatChunk } from './api';

export type ChatRole = 'system' | 'user' | 'assistant';
export interface Conversation {
  id: string; title: string; createdAt: string; updatedAt: string; archivedAt: string | null;
}
export interface SavedMessage {
  id: string; role: ChatRole; content: string; model: string | null;
  input_tokens: number | null; output_tokens: number | null; cost_cents: number | null; createdAt: string;
}
export interface ChatMessage {
  localId: string; role: ChatRole; content: string; complete: boolean; model?: string; saved?: SavedMessage;
}
export interface AppendMessage { role: ChatRole; content: string; model?: string }

const path = (id: string) => `/v1/conversations/${encodeURIComponent(id)}`;
export const conversations = {
  list: (signal?: AbortSignal) => apiFetch<Conversation[]>('/v1/conversations', { signal }),
  create: (title: string, signal?: AbortSignal) => apiFetch<Conversation>('/v1/conversations', { method: 'POST', json: { title }, signal }),
  get: (id: string, signal?: AbortSignal) => apiFetch<Conversation>(path(id), { signal }),
  messages: (id: string, signal?: AbortSignal) => apiFetch<SavedMessage[]>(`${path(id)}/messages`, { signal }),
  append: (id: string, json: AppendMessage, signal?: AbortSignal) => apiFetch<SavedMessage>(`${path(id)}/messages`, { method: 'POST', json, signal }),
  archive: (id: string, signal?: AbortSignal) => apiFetch<void>(path(id), { method: 'DELETE', signal }),
};

export const localMessage = (role: ChatRole, content: string, complete = true): ChatMessage =>
  ({ localId: crypto.randomUUID(), role, content, complete });
export const loadMessage = (saved: SavedMessage): ChatMessage => ({
  localId: crypto.randomUUID(), role: saved.role, content: saved.content, complete: true, saved,
  ...(saved.model ? { model: saved.model } : {}),
});
export const eligible = (message: ChatMessage) => message.complete && message.content.trim() !== '';
export const rejectedWrite = (error: unknown) => error instanceof ApiError && error.status >= 400 && error.status < 500 && error.status !== 408;

/** A unique additional matching record plus the exact known prefix can reconcile a lost append response. */
export function reconciledMessage(server: SavedMessage[], known: SavedMessage[], sent: AppendMessage): SavedMessage | null {
  if (server.length !== known.length + 1 || known.some((message, index) => server[index]?.id !== message.id)) return null;
  const last = server.at(-1);
  return last && last.role === sent.role && last.content === sent.content && (last.model ?? undefined) === sent.model ? last : null;
}

export type ChatStreamEvent = { chunk: ChatChunk } | { complete: true };

/**
 * The existing stateless chat route/cookie contract, with completion evidence
 * exposed for persistence. A quiet truncated EOF must never save partial text.
 * No body, transcript or key is logged or stored outside React memory.
 */
export async function* streamChatTurn(body: unknown, signal: AbortSignal): AsyncGenerator<ChatStreamEvent> {
  const response = await fetch(`${API_BASE}/v1/chat/completions`, {
    method: 'POST', credentials: 'include', signal,
    headers: { 'Content-Type': 'application/json', Accept: 'text/event-stream' }, body: JSON.stringify(body),
  });
  if (response.status === 401) window.dispatchEvent(new CustomEvent(UNAUTHORIZED_EVENT));
  if (!response.ok) throw await apiErrorFrom(response);
  if (!response.body) throw new ApiError(response.status, 'The chat response has no stream.');
  const reader = response.body.getReader();
  const decoder = new TextDecoder();
  let buffer = '';
  let finished = false;
  try {
    while (true) {
      const { done, value } = await reader.read();
      if (done) break;
      buffer = (buffer + decoder.decode(value, { stream: true })).replace(/\r\n/g, '\n');
      let split: number;
      while ((split = buffer.indexOf('\n\n')) !== -1) {
        const frame = buffer.slice(0, split);
        buffer = buffer.slice(split + 2);
        for (const line of frame.split('\n')) {
          if (!line.startsWith('data:')) continue;
          const payload = line.slice(5).trim();
          if (payload === '[DONE]') { yield { complete: true }; return; }
          let chunk: ChatChunk;
          try { chunk = JSON.parse(payload) as ChatChunk; } catch { continue; }
          if (!Array.isArray(chunk.choices)) continue;
          if (chunk.choices.some((choice) => choice.finish_reason !== null && choice.finish_reason !== undefined)) finished = true;
          yield { chunk };
        }
      }
    }
    if (finished) yield { complete: true };
  } finally {
    await reader.cancel().catch(() => undefined);
    reader.releaseLock();
  }
}
