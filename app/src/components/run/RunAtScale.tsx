import { useId, useRef, useState } from 'react';
import type { KeyboardEvent } from 'react';
import type { HandOver } from '../../lib/hand-over';
import { DEFAULT_DAILY_CAP } from '../../lib/keys';
import CreateKey from '../keys/CreateKey';
import AssistantTab from './AssistantTab';
import BatchTab from './BatchTab';
import CodeTab from './CodeTab';
import N8nTab from './N8nTab';

const TABS = [
  { id: 'assistant', label: 'Ask your AI assistant' },
  { id: 'code', label: 'Code' },
  { id: 'n8n', label: 'No-code (n8n)' },
  { id: 'batch', label: 'Batch' },
] as const;

type TabId = (typeof TABS)[number]['id'];

const INTRO = 'Four ways to run this outside the Studio, or over many rows. Three of them use an API key, '
  + 'read from the GODANCE_API_KEY environment variable.';

/**
 * "Run at scale", open under its own heading: on a recipe's page.
 */
export default function RunAtScale({ handOver, className = 'mt-8' }: { handOver: HandOver; className?: string }) {
  const heading = useId();
  return (
    <section aria-labelledby={heading} className={className}>
      <h2 id={heading} className="text-sm font-semibold text-fg">Run at scale</h2>
      <p className="mt-1 text-sm text-fg-muted">{INTRO}</p>
      <div className="card mt-3">
        <RunAtScaleTabs handOver={handOver} />
      </div>
    </section>
  );
}

type Source = HandOver | (() => Promise<HandOver | null>);
type Loaded = { handOver: HandOver } | { nothing: true } | { failed: true };

/**
 * "Run at scale" behind a button: on a finished Studio result and on a
 * batch's page, where it is not what the page is for. `source` is the
 * hand-over, or how to read it; it is read when the panel is first opened,
 * so a page that offers the panel asks the API for nothing more.
 */
export function RunAtScaleDisclosure({ source, className = '' }: { source: Source; className?: string }) {
  const body = useId();
  const [open, setOpen] = useState(false);
  const [loaded, setLoaded] = useState<Loaded | null>(null);
  const [loading, setLoading] = useState(false);

  const load = async (read: () => Promise<HandOver | null>) => {
    setLoading(true);
    try {
      const handOver = await read();
      setLoaded(handOver ? { handOver } : { nothing: true });
    } catch {
      setLoaded({ failed: true });
    } finally {
      setLoading(false);
    }
  };

  const toggle = () => {
    const next = !open;
    setOpen(next);
    if (next && typeof source === 'function' && (loaded === null || 'failed' in loaded) && !loading) void load(source);
  };

  const shown: Loaded | null = typeof source === 'function' ? loaded : { handOver: source };

  return (
    <section aria-label="Run at scale" className={className}>
      <button
        type="button"
        onClick={toggle}
        aria-expanded={open}
        aria-controls={body}
        className="btn btn-ghost btn-sm px-0"
      >
        Run at scale {open ? '▴' : '▾'}
      </button>
      {open && (
        <div id={body} className="mt-3">
          <p className="text-xs text-fg-muted">{INTRO}</p>
          {shown === null && <p className="mt-3 text-xs text-fg-faint">Loading…</p>}
          {shown && 'failed' in shown && (
            <p role="alert" className="mt-3 text-sm text-danger">
              Couldn't load the recipe this ran.{' '}
              {typeof source === 'function' && (
                <button type="button" onClick={() => void load(source)} disabled={loading} className="underline">
                  Try again
                </button>
              )}
            </p>
          )}
          {shown && 'nothing' in shown && (
            <p className="mt-3 text-sm text-fg-muted">The recipe this ran has been deleted, so there is nothing to hand over.</p>
          )}
          {shown && 'handOver' in shown && (
            <div className="mt-3">
              <RunAtScaleTabs handOver={shown.handOver} />
            </div>
          )}
        </div>
      )}
    </section>
  );
}

/** A key for this, then the four tabs and the panel of the one that is open. */
function RunAtScaleTabs({ handOver }: { handOver: HandOver }) {
  const base = useId();
  const [tab, setTab] = useState<TabId>('assistant');
  const buttons = useRef<Partial<Record<TabId, HTMLButtonElement | null>>>({});

  // Arrow keys move along the tabs, as a tab list does; Tab leaves it for the panel.
  const onKeyDown = (e: KeyboardEvent<HTMLDivElement>) => {
    const at = TABS.findIndex((each) => each.id === tab);
    const to = e.key === 'ArrowRight' ? (at + 1) % TABS.length
      : e.key === 'ArrowLeft' ? (at + TABS.length - 1) % TABS.length
        : e.key === 'Home' ? 0
          : e.key === 'End' ? TABS.length - 1
            : -1;
    if (to === -1) return;
    e.preventDefault();
    setTab(TABS[to].id);
    buttons.current[TABS[to].id]?.focus();
  };

  return (
    <div className="min-w-0">
      <div className="embedded-surface p-3">
        <p className="text-xs text-fg-muted">
          <span className="font-semibold text-fg">A key for this.</span> It can spend no more in a day than the cap
          you give it, so a script that runs away stops there.
        </p>
        <div className="mt-2">
          <CreateKey defaultName={handOver.name} defaultDailyCap={DEFAULT_DAILY_CAP} submitLabel="Create a key for this" />
        </div>
      </div>

      {/* The tabs scroll inside their row on a narrow screen, so the page never moves sideways. */}
      <div
        role="tablist"
        aria-label="Ways to run it"
        onKeyDown={onKeyDown}
        className="mt-4 flex gap-1 overflow-x-auto border-b border-border"
      >
        {TABS.map((each) => {
          const selected = each.id === tab;
          return (
            <button
              key={each.id}
              ref={(el) => { buttons.current[each.id] = el; }}
              type="button"
              role="tab"
              id={`${base}-tab-${each.id}`}
              aria-selected={selected}
              aria-controls={selected ? `${base}-panel` : undefined}
              tabIndex={selected ? 0 : -1}
              onClick={() => setTab(each.id)}
              className={`-mb-px shrink-0 whitespace-nowrap border-b-2 px-3 py-2 text-[13px] font-semibold transition-colors ${
                selected ? 'border-[var(--color-primary)] text-brand-600' : 'border-transparent text-fg-muted hover:text-fg'}`}
            >
              {each.label}
            </button>
          );
        })}
      </div>

      <div role="tabpanel" id={`${base}-panel`} aria-labelledby={`${base}-tab-${tab}`} className="min-w-0 pt-4">
        {tab === 'assistant' && <AssistantTab handOver={handOver} />}
        {tab === 'code' && <CodeTab handOver={handOver} />}
        {tab === 'n8n' && <N8nTab handOver={handOver} />}
        {tab === 'batch' && <BatchTab handOver={handOver} />}
      </div>
    </div>
  );
}
