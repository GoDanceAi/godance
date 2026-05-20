import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  ApiError,
  apiFetch,
  streamChat,
  type ModelEntry,
  type ModelListResponse,
} from '../lib/api';
import { useAuth } from '../lib/auth';

interface Message {
  role: 'system' | 'user' | 'assistant';
  content: string;
}

interface ModelOption { id: string; label: string; }

// Tiny static fallback — used if /v1/models is unreachable so the picker
// is never empty. Kept in sync with backend ai.godance.gateway.ModelCatalog
// text models.
const FALLBACK_MODELS: ModelOption[] = [
  { id: 'gpt-4o-mini',                  label: 'gpt-4o-mini (openai)' },
  { id: 'gpt-4o',                       label: 'gpt-4o (openai)' },
  { id: 'claude-3-5-sonnet-20241022',   label: 'claude-3-5-sonnet (anthropic)' },
];

function toOption(m: ModelEntry): ModelOption {
  return { id: m.id, label: `${m.id} (${m.owned_by})` };
}

export default function ChatPage() {
  const { refresh } = useAuth();
  const [models, setModels] = useState<ModelOption[]>(FALLBACK_MODELS);
  const [model, setModel] = useState<string>(FALLBACK_MODELS[1].id);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  useEffect(() => {
    let cancelled = false;
    apiFetch<ModelListResponse>('/v1/models', { auth: false })
      .then((res) => {
        if (cancelled) return;
        // Only show TEXT models in the chat picker — image/video have separate UIs.
        const opts = res.data
          .filter((m) => m.godance.modality === 'TEXT')
          .map(toOption);
        if (opts.length) {
          setModels(opts);
          setModel((cur) => (opts.some((o) => o.id === cur) ? cur : opts[0].id));
        }
      })
      .catch(() => { /* keep fallback */ });
    return () => { cancelled = true; };
  }, []);

  const send = async (e: FormEvent) => {
    e.preventDefault();
    if (!input.trim() || streaming) return;
    setError(null);

    const userMsg: Message = { role: 'user', content: input.trim() };
    const assistantPlaceholder: Message = { role: 'assistant', content: '' };
    const next: Message[] = [...messages, userMsg, assistantPlaceholder];
    setMessages(next);
    setInput('');
    setStreaming(true);

    const controller = new AbortController();
    abortRef.current = controller;

    // Index of the placeholder we'll mutate as deltas arrive.
    const assistantIdx = next.length - 1;

    try {
      const requestMessages = next
        .slice(0, -1)                  // exclude the empty assistant placeholder
        .map(({ role, content }) => ({ role, content }));

      const stream = streamChat('/v1/chat/completions',
        { model, messages: requestMessages, stream: true },
        controller.signal);

      for await (const chunk of stream) {
        const delta = chunk.choices[0]?.delta?.content;
        if (!delta) continue;
        setMessages((cur) => {
          const copy = cur.slice();
          const existing = copy[assistantIdx];
          if (existing) {
            copy[assistantIdx] = { ...existing, content: existing.content + delta };
          }
          return copy;
        });
      }
    } catch (err) {
      if ((err as Error).name === 'AbortError') {
        // user cancelled — leave the partial assistant content in place
      } else {
        setError(err instanceof ApiError ? err.detail || err.message : 'Chat failed');
        // Roll back the empty placeholder if we never wrote into it.
        setMessages((cur) => (cur[assistantIdx]?.content === '' ? cur.slice(0, -1) : cur));
      }
    } finally {
      setStreaming(false);
      abortRef.current = null;
      void refresh();   // balance moved; re-fetch /me
    }
  };

  const stop = () => abortRef.current?.abort();

  return (
    <div className="mx-auto max-w-3xl px-6 py-8 flex flex-col h-[calc(100vh-8rem)]">
      <div className="flex items-center justify-between mb-4">
        <h1 className="text-xl font-semibold">Chat</h1>
        <select
          value={model}
          onChange={(e) => setModel(e.target.value)}
          disabled={streaming}
          className="text-sm border border-gray-300 rounded-md px-2 py-1.5 bg-white"
        >
          {models.map((m) => (
            <option key={m.id} value={m.id}>{m.label}</option>
          ))}
        </select>
      </div>

      <div className="flex-1 overflow-y-auto border border-gray-200 rounded-lg bg-white p-4 space-y-4">
        {messages.length === 0 && (
          <p className="text-sm text-gray-400 text-center py-12">
            Type a prompt below. Responses stream as tokens arrive.
          </p>
        )}
        {messages.map((m, i) => (
          <div key={i} className={m.role === 'user' ? 'text-right' : ''}>
            <span className="text-xs font-medium uppercase tracking-wider text-gray-400">
              {m.role}
            </span>
            <div
              className={`mt-1 inline-block max-w-[85%] whitespace-pre-wrap rounded-lg px-4 py-2 text-sm ${
                m.role === 'user'
                  ? 'bg-brand-600 text-white'
                  : 'bg-gray-100 text-gray-900'
              }`}
            >
              {m.content || <span className="text-gray-400">…</span>}
            </div>
          </div>
        ))}
      </div>

      {error && (
        <p className="mt-2 text-sm text-red-600">{error}</p>
      )}

      <form onSubmit={send} className="mt-4 flex gap-2">
        <textarea
          value={input}
          onChange={(e) => setInput(e.target.value)}
          onKeyDown={(e) => {
            if (e.key === 'Enter' && !e.shiftKey) { e.preventDefault(); void send(e); }
          }}
          placeholder="Ask anything…  (Enter to send, Shift+Enter for newline)"
          rows={2}
          className="flex-1 resize-none rounded-md border border-gray-300 px-3 py-2 text-sm outline-none focus:border-brand-600 focus:ring-1 focus:ring-brand-600"
          disabled={streaming}
        />
        {streaming ? (
          <button
            type="button"
            onClick={stop}
            className="px-4 bg-gray-200 hover:bg-gray-300 text-gray-800 font-medium rounded-md"
          >
            Stop
          </button>
        ) : (
          <button
            type="submit"
            disabled={!input.trim()}
            className="px-4 bg-brand-600 hover:bg-brand-700 disabled:bg-brand-300 text-white font-medium rounded-md"
          >
            Send
          </button>
        )}
      </form>
    </div>
  );
}
