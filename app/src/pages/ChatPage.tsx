import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  ApiError,
  apiFetch,
  isVirtualModel,
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

// Tiny offline fallback — used if /v1/models is unreachable so the picker
// is never empty. The live catalog replaces this on every successful load.
const FALLBACK_MODELS: ModelOption[] = [
  { id: 'gpt-4o-mini',                  label: 'gpt-4o-mini (openai)' },
  { id: 'gpt-4o',                       label: 'gpt-4o (openai)' },
  { id: 'claude-3-5-sonnet-20241022',   label: 'claude-3-5-sonnet-20241022 (anthropic)' },
];

type PresetKey = 'smart' | 'fast' | 'cheap' | 'creative';

// Presets are vibes, not model names — a first-time user picks one of these and
// never has to see which model answered. Each carries an ordered preference
// list bound to the first match in the live catalog; if none match we fall back
// to a price heuristic (tier) so a preset always resolves to a real model.
const PRESETS: {
  key: PresetKey; label: string; hint: string; prefer: string[]; tier: 'top' | 'low';
}[] = [
  { key: 'smart', label: 'Smart', hint: 'Deepest reasoning',
    prefer: ['claude-3-5-sonnet-20241022', 'gpt-4o', 'gpt-4.1', 'gemini-1.5-pro'], tier: 'top' },
  { key: 'fast', label: 'Fast', hint: 'Lowest latency',
    prefer: ['gpt-4o-mini', 'claude-3-5-haiku-20241022', 'gemini-1.5-flash'], tier: 'low' },
  { key: 'cheap', label: 'Cheap', hint: 'Lowest cost per token',
    prefer: ['gemini-1.5-flash', 'gpt-4o-mini', 'claude-3-5-haiku-20241022'], tier: 'low' },
  { key: 'creative', label: 'Creative', hint: 'Most expressive',
    prefer: ['gpt-4o', 'claude-3-5-sonnet-20241022', 'gemini-1.5-pro'], tier: 'top' },
];

const FALLBACK_PRESETS: Record<PresetKey, string> = {
  smart: 'gpt-4o', fast: 'gpt-4o-mini', cheap: 'gpt-4o-mini', creative: 'gpt-4o',
};

function toOption(m: ModelEntry): ModelOption {
  return { id: m.id, label: `${m.id} (${m.owned_by})` };
}

function textPrice(m: ModelEntry): number {
  const p = m.godance.pricing;
  return (p.input_microcents_per_1k ?? 0) + (p.output_microcents_per_1k ?? 0);
}

// Bind each preset to a concrete model id from the live text catalog.
function resolvePresets(textModels: ModelEntry[]): Record<PresetKey, string> {
  const ids = new Set(textModels.map((m) => m.id));
  const byPrice = [...textModels].sort((a, b) => textPrice(a) - textPrice(b));
  const cheapest = byPrice[0]?.id;
  const priciest = byPrice[byPrice.length - 1]?.id;
  const out = {} as Record<PresetKey, string>;
  for (const p of PRESETS) {
    const hit = p.prefer.find((id) => ids.has(id));
    out[p.key] = hit ?? (p.tier === 'low' ? cheapest : priciest) ?? FALLBACK_PRESETS[p.key];
  }
  return out;
}

export default function ChatPage() {
  const { refresh } = useAuth();
  const [models, setModels] = useState<ModelOption[]>(FALLBACK_MODELS);
  const [presets, setPresets] = useState<Record<PresetKey, string>>(FALLBACK_PRESETS);
  const [activePreset, setActivePreset] = useState<PresetKey | null>('smart');
  const [advancedModel, setAdvancedModel] = useState<string>(FALLBACK_MODELS[1].id);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [messages, setMessages] = useState<Message[]>([]);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  // Effective model: a preset resolves to its bound id; Advanced uses the
  // explicitly chosen one. Either way the request always carries a real id.
  const model = activePreset ? (presets[activePreset] || advancedModel) : advancedModel;

  useEffect(() => {
    let cancelled = false;
    // Only TEXT models drive the chat picker — image/video have no chat UI.
    // Virtual routing ids are skipped: they have no pricing for textPrice().
    apiFetch<ModelListResponse>('/v1/models', { auth: false })
      .then((res) => {
        if (cancelled) return;
        const textEntries = res.data.filter(
          (m): m is ModelEntry => !isVirtualModel(m) && m.godance.modality === 'TEXT');
        if (!textEntries.length) return;
        setModels(textEntries.map(toOption));
        setPresets(resolvePresets(textEntries));
        setAdvancedModel((cur) =>
          textEntries.some((m) => m.id === cur) ? cur : textEntries[0].id);
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

  const pickAdvanced = (id: string) => {
    setAdvancedModel(id);
    setActivePreset(null);
  };

  return (
    <div className="mx-auto max-w-3xl px-6 py-8 flex flex-col h-[calc(100vh-8rem)]">
      <div className="mb-4">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <h1 className="text-xl font-semibold text-fg">Chat</h1>
          <div className="flex items-center gap-1.5">
            {PRESETS.map((p) => (
              <button
                key={p.key}
                type="button"
                title={p.hint}
                onClick={() => setActivePreset(p.key)}
                disabled={streaming}
                className={`btn btn-sm ${activePreset === p.key ? 'btn-primary' : 'btn-secondary'}`}
              >
                {p.label}
              </button>
            ))}
            <button
              type="button"
              onClick={() => setAdvancedOpen((v) => !v)}
              disabled={streaming}
              aria-expanded={advancedOpen}
              className={`btn btn-sm ${activePreset === null ? 'btn-primary' : 'btn-ghost'}`}
            >
              Advanced {advancedOpen ? '▴' : '▾'}
            </button>
          </div>
        </div>

        {advancedOpen && (
          <div className="mt-3 flex items-center justify-end gap-2">
            <label htmlFor="model-select" className="text-xs text-fg-faint">
              Pick an exact model
            </label>
            <select
              id="model-select"
              value={activePreset === null ? advancedModel : ''}
              onChange={(e) => pickAdvanced(e.target.value)}
              disabled={streaming}
              className="input text-sm"
              style={{ width: 'auto' }}
            >
              {activePreset !== null && (
                <option value="" disabled>Choose a model…</option>
              )}
              {models.map((m) => (
                <option key={m.id} value={m.id}>{m.label}</option>
              ))}
            </select>
          </div>
        )}
      </div>

      <div className="flex-1 overflow-y-auto card p-4 space-y-4">
        {messages.length === 0 && (
          <p className="text-sm text-fg-faint text-center py-12">
            Type a prompt below. Responses stream as tokens arrive.
          </p>
        )}
        {messages.map((m, i) => (
          <div key={i} className={m.role === 'user' ? 'text-right' : ''}>
            <span className="text-xs font-semibold uppercase tracking-wider text-fg-faint">
              {m.role}
            </span>
            <div
              className={`mt-1 inline-block max-w-[85%] whitespace-pre-wrap rounded-xl px-4 py-2 text-sm leading-relaxed ${
                m.role === 'user'
                  ? 'bg-brand-600 text-white'
                  : 'bubble-assistant text-fg'
              }`}
            >
              {m.content || <span className="text-fg-faint">…</span>}
            </div>
          </div>
        ))}
      </div>

      {error && (
        <p className="mt-2 text-sm text-danger">{error}</p>
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
          className="input flex-1 resize-none"
          disabled={streaming}
        />
        {streaming ? (
          <button type="button" onClick={stop} className="btn btn-secondary">
            Stop
          </button>
        ) : (
          <button type="submit" disabled={!input.trim()} className="btn btn-primary">
            Send
          </button>
        )}
      </form>
    </div>
  );
}
