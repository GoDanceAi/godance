import { useEffect, useRef, useState } from 'react';
import type { FormEvent } from 'react';
import {
  apiFetch,
  isVirtualModel,
  type ModelEntry,
  type ModelListResponse,
} from '../lib/api';
import { useAuth, formatCents } from '../lib/auth';
import { localMessage, streamChatTurn } from '../lib/chat-history';
import { useSavedChat } from '../lib/use-saved-chat';
import { formatDateTime } from '../lib/time';
import { sendError, type SendError } from '../lib/send-error';
import SendErrorNotice from '../components/SendErrorNotice';

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
  const { user } = useAuth();
  return <AccountChat key={`${user?.id}:${user?.ageStatus}`} adult={user?.ageStatus === 'ADULT'} />;
}

function AccountChat({ adult }: { adult: boolean }) {
  const { refresh } = useAuth();
  const history = useSavedChat(adult);
  const { messages, update } = history;
  const [models, setModels] = useState<ModelOption[]>(FALLBACK_MODELS);
  const [presets, setPresets] = useState<Record<PresetKey, string>>(FALLBACK_PRESETS);
  const [activePreset, setActivePreset] = useState<PresetKey | null>('smart');
  const [advancedModel, setAdvancedModel] = useState<string>(FALLBACK_MODELS[1].id);
  const [advancedOpen, setAdvancedOpen] = useState(false);
  const [input, setInput] = useState('');
  const [streaming, setStreaming] = useState(false);
  const [error, setError] = useState<SendError | null>(null);
  const abortRef = useRef<AbortController | null>(null);

  const requestRef = useRef<object | null>(null);
  const sending = useRef(false);
  const alive = useRef(true);
  // A different account/age-state mounts a fresh chat, and late callbacks are fenced.
  useEffect(() => {
    alive.current = true;
    return () => { alive.current = false; requestRef.current = null; abortRef.current?.abort(); };
  }, []);

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
    if (!input.trim() || sending.current || streaming || history.busy || history.readOnly) return;
    sending.current = true;
    if (!await history.prepareSend() || !alive.current) { sending.current = false; return; }
    setError(null);
    const userMsg = localMessage('user', input.trim());
    const assistant = { ...localMessage('assistant', '', false), model };
    const next = [...history.transcript.current, userMsg, assistant];
    update(next);
    setInput('');
    setStreaming(true);
    const controller = new AbortController();
    const request = {};
    requestRef.current = request;
    abortRef.current = controller;
    let complete = false;
    try {
      const requestMessages = next.slice(0, -1).filter((message) => message.content.trim() !== '')
        .map(({ role, content }) => ({ role, content }));
      for await (const event of streamChatTurn({ model, messages: requestMessages, stream: true }, controller.signal)) {
        if (!alive.current || requestRef.current !== request) return;
        if ('complete' in event) { complete = true; continue; }
        const delta = event.chunk.choices[0]?.delta?.content;
        if (!delta) continue;
        update((current) => current.map((message) => message.localId === assistant.localId
          ? { ...message, content: message.content + delta, model: event.chunk.model || model } : message));
      }
    } catch (err) {
      if (!alive.current || requestRef.current !== request) return;
      if ((err as Error).name !== 'AbortError') {
        setError(sendError(err, 'Chat failed'));
        update((current) => current.filter((message) => message.localId !== assistant.localId || message.content !== ''));
      }
    } finally {
      sending.current = false;
      if (alive.current && requestRef.current === request) {
        update((current) => current.map((message) => message.localId === assistant.localId
          ? { ...message, complete: complete && !controller.signal.aborted } : message));
        setStreaming(false);
        requestRef.current = null;
        abortRef.current = null;
        void refresh();
        // Only a conversation the adult chose to save appends subsequent turns.
        // A persistence error pauses saving; later model turns never retry writes.
        if (adult && history.selected && !history.readOnly) void history.persist(false);
      }
    }
  };

  const newChat = () => {
    if (!streaming && !history.busy && history.newChat()) { setInput(''); setError(null); }
  };
  const openChat = async (id: string) => {
    if (!streaming && await history.open(id)) { setInput(''); setError(null); }
  };

  const stop = () => abortRef.current?.abort();

  const pickAdvanced = (id: string) => {
    setAdvancedModel(id);
    setActivePreset(null);
  };

  return (
    <div className="mx-auto max-w-3xl px-6 py-8 flex flex-col min-h-[calc(100vh-8rem)]">
      <div className="mb-4">
        <div className="flex items-center justify-between gap-3 flex-wrap">
          <h1 className="text-xl font-semibold text-fg">Chat</h1>
          <div className="flex flex-wrap items-center gap-1.5">
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

      {adult && (
        <section aria-label="Saved chat controls" className="mb-4 card space-y-3">
          <div className="flex flex-wrap gap-2">
            <button type="button" className="btn btn-secondary btn-sm" onClick={newChat} disabled={streaming || history.busy}>New chat</button>
            <button type="button" className="btn btn-secondary btn-sm" onClick={() => void history.loadList()}
              disabled={streaming || history.busy || history.listLoading}>Saved chats</button>
            {!history.selected && <button type="button" className="btn btn-primary btn-sm" onClick={() => void history.persist()}
              disabled={streaming || history.busy || !messages.some((message) => message.complete && message.content.trim()) || history.problem?.kind === 'unknown-create'}>Save this chat</button>}
            {history.selected && <button type="button" className="btn btn-ghost btn-sm" onClick={() => void history.archive()}
              disabled={streaming || history.busy || history.readOnly}>Archive chat</button>}
          </div>
          <p aria-live="polite" className="break-words text-xs text-fg-muted">
            {history.selected ? history.archiveUncertain ? 'Archival is unconfirmed. This chat is read-only until its status is checked.'
              : history.readOnly ? 'Archived chat · Read-only. It leaves the active list and is removed for good after 30 days.'
              : history.problem ? `Saving paused for “${history.selected.title}”. Your current messages remain visible here.`
              : `Saving is on for “${history.selected.title}”. Completed messages are saved; partial replies stay only on this page.`
              : 'Ephemeral chat · New chats stay only on this page until you choose Save this chat. Saving retains the conversation in your account until you archive it; it is removed for good 30 days after archival.'}
          </p>
          {history.busy && <p role="status" className="text-sm text-fg-muted">Checking or saving chat…</p>}
          {history.problem && <div className="space-y-2">
            <p role="alert" className="text-sm text-danger">{history.problem.text}</p>
            {(history.problem.kind === 'rejected' || history.problem.kind === 'reconciled') && <button type="button" className="btn btn-secondary btn-sm"
              disabled={streaming || history.busy || history.readOnly} onClick={() => void history.persist()}>{history.problem.kind === 'rejected' ? 'Retry save' : 'Continue saving'}</button>}
            {history.problem.kind === 'unknown-append' && <button type="button" className="btn btn-secondary btn-sm"
              disabled={streaming || history.busy} onClick={() => void history.checkSavedCopy()}>Check saved copy</button>}
          </div>}
          {history.historyError && <p role="alert" className="text-sm text-danger">{history.historyError}</p>}
          {history.archiveUncertain && <button type="button" className="btn btn-secondary btn-sm" disabled={streaming || history.busy}
            onClick={() => void history.prepareSend()}>Check chat status</button>}
          {history.listOpen && <section aria-labelledby="saved-chat-list" className="border-t border-border pt-3">
            <h2 id="saved-chat-list" className="text-sm font-semibold text-fg">Your saved chats</h2>
            {history.listLoading && <p className="mt-2 text-sm text-fg-muted">Loading saved chats…</p>}
            {!history.listLoading && history.list?.length === 0 && <p className="mt-2 text-sm text-fg-muted">No active saved chats yet.</p>}
            {history.list && <ul className="mt-2 space-y-2">
              {history.list.map((conversation) => <li key={conversation.id}>
                <button type="button" className="max-w-full text-left text-sm font-medium text-brand-600 hover:underline break-words"
                  aria-pressed={history.selected?.id === conversation.id} disabled={streaming || history.busy || history.listLoading}
                  onClick={() => void openChat(conversation.id)}>{conversation.title}</button>
                <span className="ml-2 text-xs text-fg-faint">{formatDateTime(conversation.updatedAt)}</span>
              </li>)}
            </ul>}
          </section>}
        </section>
      )}

      <div aria-label="Chat messages" className="flex-1 min-h-64 max-h-[60vh] overflow-y-auto card p-4 space-y-4">
        {messages.length === 0 && (
          <p className="text-sm text-fg-faint text-center py-12">
            Type a prompt below. Responses stream as tokens arrive.
          </p>
        )}
        {messages.map((m) => (
          <div key={m.localId} className={m.role === 'user' ? 'text-right' : ''}>
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
              {m.content || <span className="text-fg-faint">{streaming ? '…' : 'No text returned. Not saved.'}</span>}
            </div>
            {m.role === 'assistant' && !m.complete && !streaming && <p className="mt-1 text-xs text-fg-muted">Partial or interrupted reply · Not saved</p>}
            {m.saved && <p className="mt-1 text-xs text-fg-faint">
              Saved {formatDateTime(m.saved.createdAt)}{m.saved.model ? ` · ${m.saved.model}` : ''}
              {m.saved.input_tokens != null ? ` · ${m.saved.input_tokens} input tokens` : ''}
              {m.saved.output_tokens != null ? ` · ${m.saved.output_tokens} output tokens` : ''}
              {m.saved.cost_cents != null ? ` · ${formatCents(m.saved.cost_cents)}` : ''}
            </p>}
          </div>
        ))}
      </div>

      {error && (
        <SendErrorNotice error={error} what="this message" then="to keep chatting" className="mt-2" />
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
          disabled={streaming || history.busy || history.readOnly}
        />
        {streaming ? (
          <button type="button" onClick={stop} className="btn btn-secondary">
            Stop
          </button>
        ) : (
          <button type="submit" disabled={!input.trim() || history.busy || history.readOnly} className="btn btn-primary">
            Send
          </button>
        )}
      </form>
    </div>
  );
}
