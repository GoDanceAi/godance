import type { ReactNode } from 'react';
import { Link } from 'react-router-dom';
import type { BatchEstimate } from '../../lib/api';
import { formatCents } from '../../lib/auth';
import { modelName, priceLabel, type ProviderGroup } from '../../lib/media-models';

/**
 * The last step of a new batch: the model, how many rows run at once, the
 * estimate for exactly what is on the page, and Start. Start is offered
 * only while that estimate is shown; anything that changes the batch takes
 * the estimate away until the new one arrives.
 */
export default function BatchStart({
  groups, catalogFailed, model, versionModel, onModel, settings, notes, cap, concurrency, onConcurrency,
  waiting, estimate, asking, problem, starting, onStart,
}: {
  /** The models of the recipe's kind, by provider; null until the list is read. */
  groups: ProviderGroup[] | null;
  catalogFailed: boolean;
  model: string;
  /** The model the recipe's version keeps. */
  versionModel: string;
  onModel: (id: string) => void;
  /** The settings the batch sends, as short text; empty for none. */
  settings: string;
  /** What changed about the settings because another model was chosen. */
  notes: string[];
  /** The most rows at a time the API allows. */
  cap: number;
  concurrency: number;
  onConcurrency: (n: number) => void;
  /** Why there is nothing to price yet, in words; null when the batch is whole. */
  waiting: string | null;
  /** The estimate for exactly the batch on the page, or null. */
  estimate: BatchEstimate | null;
  asking: boolean;
  /** A refusal from the estimate or from Start, already worded. */
  problem: ReactNode;
  starting: boolean;
  onStart: () => void;
}) {
  const listed = groups?.some((group) => group.models.some((m) => m.id === versionModel)) ?? false;

  return (
    <section aria-labelledby="batch-start">
      <h2 id="batch-start" className="text-sm font-semibold text-fg">Before you start</h2>

      <div className="mt-3 grid gap-4 sm:grid-cols-2">
        <div>
          <label htmlFor="batch-model" className="block text-sm font-medium text-fg">Model</label>
          <select id="batch-model" value={model} onChange={(e) => onModel(e.target.value)} className="input mt-1.5">
            {/* The recipe's own model is always offered, in the list or not: the estimate says if it can't run. */}
            {!listed && <option value={versionModel}>{versionModel} · the recipe's model</option>}
            {groups?.map((group) => (
              <optgroup key={group.provider} label={group.name}>
                {group.models.map((m) => {
                  const price = priceLabel(m);
                  const name = m.id === versionModel ? `${modelName(m)} · the recipe's model` : modelName(m);
                  return <option key={m.id} value={m.id}>{price ? `${name} · ${price}` : name}</option>;
                })}
              </optgroup>
            ))}
          </select>
          {catalogFailed && (
            <p className="mt-1.5 text-xs text-warning">
              Couldn't load the other models. The batch runs on the recipe's model.
            </p>
          )}
        </div>
        <div>
          <label htmlFor="batch-concurrency" className="block text-sm font-medium text-fg">Rows at a time</label>
          <select
            id="batch-concurrency"
            value={String(concurrency)}
            onChange={(e) => onConcurrency(Number(e.target.value))}
            className="input mt-1.5"
          >
            {Array.from({ length: cap }, (_, i) => i + 1).map((n) => <option key={n} value={n}>{n}</option>)}
          </select>
          <p className="mt-1.5 text-xs text-fg-subtle">
            More rows at a time finish sooner. The cost is the same.
          </p>
        </div>
      </div>

      {(settings || notes.length > 0) && (
        <div className="mt-3 text-xs text-fg-subtle">
          {settings && <p>Settings for every row: <span className="text-fg">{settings}</span></p>}
          {notes.map((note) => <p key={note} className="mt-0.5 text-warning">{note}</p>)}
        </div>
      )}

      <div aria-live="polite" className="mt-4 card" data-batch-estimate>
        {waiting ? (
          <p className="text-sm text-fg-muted">{waiting}</p>
        ) : estimate ? (
          <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-6 gap-y-2 text-sm">
            <dt className="text-fg-subtle">Rows</dt>
            <dd className="tabular-nums text-fg">{estimate.rows.toLocaleString('en-US')}</dd>
            <dt className="text-fg-subtle">Estimated total</dt>
            <dd className="font-semibold tabular-nums text-fg">{formatCents(estimate.total_cents)}</dd>
            <dt className="text-fg-subtle">Balance</dt>
            <dd className="tabular-nums text-fg">{formatCents(estimate.balance_cents)}</dd>
          </dl>
        ) : asking ? (
          <p className="text-sm text-fg-faint">Working out the total…</p>
        ) : (
          <p className="text-sm text-fg-muted">The total could not be worked out.</p>
        )}
      </div>

      {estimate && !estimate.enough && (
        <p role="alert" className="mt-3 text-sm text-warning" data-not-enough>
          Your balance of {formatCents(estimate.balance_cents)} does not cover the estimated{' '}
          {formatCents(estimate.total_cents)}.{' '}
          <Link to="/billing" className="text-brand-600 hover:text-brand-700 underline">
            Top up on the Billing page
          </Link>{' '}
          first, or start as it is: the batch runs until the credit runs out, then pauses, and Resume
          continues it once you have topped up.
        </p>
      )}
      {problem}

      <div className="mt-4 flex flex-wrap items-center gap-x-4 gap-y-2">
        <button type="button" onClick={onStart} disabled={!estimate || starting} className="btn btn-primary btn-lg">
          {starting ? 'Starting…' : (
            <>Start batch{estimate && <> <span aria-hidden="true">·</span> {formatCents(estimate.total_cents)}</>}</>
          )}
        </button>
        <p className="text-xs text-fg-subtle">
          Nothing is generated or charged before you press Start. Each row is charged as it runs.
        </p>
      </div>
    </section>
  );
}
