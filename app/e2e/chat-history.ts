import type { MockApi } from './mock-api';
import { problem } from './mock-api';
import type { ChatRole, Conversation, SavedMessage } from '../src/lib/chat-history';

export const CONVERSATION = '01010101-0000-4000-8000-000000000001';
export const NOW = '2026-11-16T12:00:00Z';
export const savedChat = (overrides: Partial<Conversation> = {}): Conversation => ({
  id: CONVERSATION, title: 'Synthetic saved chat', createdAt: NOW, updatedAt: NOW, archivedAt: null, ...overrides,
});
export const savedMessage = (content: string, role: ChatRole = 'user', index = 1): SavedMessage => ({
  id: `02020202-0000-4000-8000-${String(index).padStart(12, '0')}`, role, content,
  model: role === 'assistant' ? 'gpt-4o-mini' : null, input_tokens: null, output_tokens: null, cost_cents: null, createdAt: NOW,
});

/** Feature-specific in-memory conversation contract; never a real account/provider. */
export function serveChatHistory(api: MockApi, initialMessages: ReturnType<typeof savedMessage>[] = []) {
  let conversation = savedChat();
  let exists = false;
  const messages = [...initialMessages];
  api.on('GET', '/v1/conversations', () => ({ json: exists && !conversation.archivedAt ? [conversation] : [] }))
    .on('POST', '/v1/conversations', (call) => {
      exists = true;
      conversation = savedChat({ title: (call.body as { title?: string }).title || 'New conversation' });
      return { status: 201, json: conversation };
    })
    .on('GET', `/v1/conversations/${CONVERSATION}`, () => exists ? { json: conversation } : problem(404, 'conversation not found'))
    .on('GET', `/v1/conversations/${CONVERSATION}/messages`, () => exists ? { json: messages } : problem(404, 'conversation not found'))
    .on('POST', `/v1/conversations/${CONVERSATION}/messages`, (call) => {
      const message = { ...savedMessage('', 'user', messages.length + 1), ...(call.body as Record<string, unknown>) } as ReturnType<typeof savedMessage>;
      messages.push(message);
      return { status: 201, json: message };
    })
    .on('DELETE', `/v1/conversations/${CONVERSATION}`, () => {
      conversation = savedChat({ ...conversation, archivedAt: NOW });
      return { status: 204 };
    });
  return { messages, seed: () => { exists = true; }, archive: () => { conversation = savedChat({ ...conversation, archivedAt: NOW }); } };
}
