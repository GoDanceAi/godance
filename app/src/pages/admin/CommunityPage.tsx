import { useCallback, useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { useAdmin } from '../../lib/admin';
import {
  moderation,
  publicProfileUrl,
  publicRecipeUrl,
  REASON_MAX,
  refusal,
  type QueueItem,
  type ReviewRecipe,
  type ReviewReport,
  type SubmissionStatus,
} from '../../lib/community';
import { parseTemplate } from '../../lib/template';
import { formatDateTime } from '../../lib/time';
import { paramsText } from '../../lib/user-recipes';

/** The roles the API lets moderate (round 14 brief 01). */
export const MODERATOR_ROLES = ['SUPPORT', 'ADMIN'] as const;

const WHY: Record<QueueItem['why'], { label: string; pill: string }> = {
  pending: { label: 'New submission', pill: 'pill-amber' },
  reported: { label: 'Reported', pill: 'pill-rose' },
  profile: { label: 'Profile', pill: 'pill-violet' },
};

const STATUS_PILL: Record<SubmissionStatus, string> = {
  PENDING: 'pill-amber',
  PUBLIC: 'pill-emerald',
  REJECTED: 'pill-rose',
  REMOVED: 'pill',
};

/**
 * /admin/community: the moderation queue. New submissions, public recipes
 * with open reports, and profiles to review, each with what a decision
 * needs: the template and its variables, the examples, the author and their
 * history. Approve, reject with a reason, remove with a reason. Then the
 * open reports. Support and admin only.
 */
export default function CommunityPage() {
  const { role } = useAdmin();
  if (!MODERATOR_ROLES.some((each) => each === role)) {
    return <p className="text-sm text-fg-muted">Moderating the community is for the support and admin roles.</p>;
  }
  return <Moderation />;
}

function Moderation() {
  const [queue, setQueue] = useState<QueueItem[] | null>(null);
  const [reports, setReports] = useState<ReviewReport[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [done, setDone] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [items, open] = await Promise.all([moderation.queue(), moderation.reports()]);
      setQueue(items);
      setReports(open);
      setError(null);
    } catch (err) {
      setError(refusal(err, "Couldn't load the moderation queue."));
    }
  }, []);

  useEffect(() => { void load(); }, [load]);

  const decided = async (message: string) => {
    setDone(message);
    await load();
  };

  return (
    <div>
      <p className="text-sm text-fg-muted">
        Nothing users publish is public until it is approved here. A report puts a public recipe back in the queue;
        it stays public unless it is removed. The author sees the reason for a rejection or a removal.
      </p>
      {error && <p role="alert" className="mt-3 text-sm text-danger">{error}</p>}
      <p aria-live="polite" className="mt-3 text-sm text-success">{done}</p>

      <section aria-labelledby="moderation-queue" className="mt-4">
        <h2 id="moderation-queue" className="text-sm font-semibold text-fg">
          Queue{queue !== null && <span className="font-normal text-fg-subtle"> · {queue.length}</span>}
        </h2>
        {queue === null && !error && <p className="mt-3 text-sm text-fg-faint">Loading…</p>}
        {queue?.length === 0 && <p className="mt-3 card py-8 text-center text-sm text-fg-faint">Nothing waits for review.</p>}
        <div className="mt-3 space-y-4">
          {queue?.map((item) => (
            <QueueCard key={`${item.why}:${item.recipe?.public_id ?? item.author.handle}`} item={item} onDecided={decided} />
          ))}
        </div>
      </section>

      <section aria-labelledby="moderation-reports" className="mt-10">
        <h2 id="moderation-reports" className="text-sm font-semibold text-fg">
          Open reports{reports !== null && <span className="font-normal text-fg-subtle"> · {reports.length}</span>}
        </h2>
        {reports?.length === 0 && <p className="mt-3 text-sm text-fg-faint">No open reports.</p>}
        {reports !== null && reports.length > 0 && (
          <div className="mt-3 card p-0 overflow-x-auto">
            <table className="w-full text-sm">
              <thead className="border-b border-border bg-surface-alt text-left text-fg-subtle">
                <tr>
                  <th className="px-4 py-2.5 font-semibold">What</th>
                  <th className="px-4 py-2.5 font-semibold">Reason</th>
                  <th className="px-4 py-2.5 font-semibold">Reported</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-border">
                {reports.map((report) => (
                  <tr key={report.id}>
                    <td className="px-4 py-3 align-top whitespace-nowrap"><ReportTarget report={report} /></td>
                    <td className="px-4 py-3 align-top whitespace-pre-wrap break-words text-fg">{report.reason}</td>
                    <td className="px-4 py-3 align-top whitespace-nowrap text-fg-muted">{formatDateTime(report.created_at)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        )}
      </section>
    </div>
  );
}

function ReportTarget({ report }: { report: ReviewReport }) {
  if (!report.target) return <span className="text-fg-faint">{report.kind === 'profile' ? 'A profile' : 'A recipe'}, now gone</span>;
  return report.kind === 'profile' ? (
    <a href={publicProfileUrl(report.target)} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">
      Profile @{report.target}
    </a>
  ) : (
    <a href={publicRecipeUrl(report.target)} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">
      Recipe <span className="font-mono">{report.target}</span>
    </a>
  );
}

/** One item of the queue, with what deciding it needs and the decisions it allows. */
function QueueCard({ item, onDecided }: { item: QueueItem; onDecided: (message: string) => Promise<void> }) {
  const { recipe, author } = item;
  const name = recipe ? recipe.title : `@${author.handle ?? 'unknown'}'s profile`;
  return (
    <article aria-label={name} className="card space-y-4" data-queue-item={item.why}>
      <header className="flex flex-wrap items-center gap-2">
        <span className={`pill ${WHY[item.why].pill}`}>{WHY[item.why].label}</span>
        <h3 className="min-w-0 break-words font-semibold text-fg">{name}</h3>
        {recipe && <span className={`pill ${STATUS_PILL[recipe.status]}`}>{recipe.status.toLowerCase()}</span>}
        {recipe && (
          <span className="text-xs text-fg-subtle">
            {recipe.modality} · version {recipe.version} · submitted {formatDateTime(recipe.submitted_at)}
          </span>
        )}
      </header>

      {recipe && <RecipeReview recipe={recipe} />}

      <div className="grid gap-4 md:grid-cols-2">
        <section aria-label="Author" className="embedded-surface p-3 text-sm">
          <h4 className="text-xs font-semibold text-fg-subtle">Author</h4>
          <dl className="mt-1.5 grid grid-cols-[auto_minmax(0,1fr)] gap-x-4 gap-y-1">
            <dt className="text-fg-subtle">Handle</dt>
            <dd className="text-fg">{author.handle ? `@${author.handle}` : 'none'}</dd>
            <dt className="text-fg-subtle">Display name</dt>
            <dd className="break-words text-fg">
              {author.display_name ?? 'none'}
              {author.approved_at && author.approved_display_name !== author.display_name && (
                <span className="block text-xs text-fg-subtle">approved: {author.approved_display_name ?? 'none'}</span>
              )}
            </dd>
            <dt className="text-fg-subtle">Link</dt>
            <dd className="break-all text-fg">
              {author.link ?? 'none'}
              {author.approved_at && author.approved_link !== author.link && (
                <span className="block text-xs text-fg-subtle">approved: {author.approved_link ?? 'none'}</span>
              )}
            </dd>
            <dt className="text-fg-subtle">Email</dt>
            <dd className="break-all text-fg">{author.email ?? 'unknown'}</dd>
            <dt className="text-fg-subtle">Followers</dt>
            <dd className="text-fg">{author.followers}</dd>
            <dt className="text-fg-subtle">Profile</dt>
            <dd className="text-fg">{author.approved_at ? `approved ${formatDateTime(author.approved_at)}` : 'not approved yet'}</dd>
          </dl>
        </section>

        <section aria-label="Author history" className="embedded-surface p-3 text-sm">
          <h4 className="text-xs font-semibold text-fg-subtle">Earlier decisions on this author</h4>
          {item.prior_decisions.length === 0 ? (
            <p className="mt-1.5 text-fg-muted">None: this is their first submission.</p>
          ) : (
            <ul className="mt-1.5 space-y-1.5">
              {item.prior_decisions.map((prior) => (
                <li key={prior.public_id}>
                  <span className={`pill ${STATUS_PILL[prior.status]}`}>{prior.status.toLowerCase()}</span>{' '}
                  <span className="text-fg">{prior.title}</span>
                  {prior.reason && <span className="block text-xs text-fg-muted">{prior.reason}</span>}
                </li>
              ))}
            </ul>
          )}
        </section>
      </div>

      {item.reports.length > 0 && (
        <section aria-label="Reports" className="text-sm">
          <h4 className="text-xs font-semibold text-fg-subtle">Open reports</h4>
          <ul className="mt-1.5 space-y-1.5">
            {item.reports.map((report) => (
              <li key={report.id} className="embedded-surface p-2.5">
                <p className="whitespace-pre-wrap break-words text-fg">{report.reason}</p>
                <p className="mt-0.5 text-xs text-fg-subtle">{formatDateTime(report.created_at)}</p>
              </li>
            ))}
          </ul>
        </section>
      )}

      <Decisions item={item} name={name} onDecided={onDecided} />
    </article>
  );
}

/** What a moderator reviews of a recipe: the examples, the template and its variables, and how it runs. */
function RecipeReview({ recipe }: { recipe: ReviewRecipe }) {
  const settings = paramsText(recipe.params ?? {});
  return (
    <div className="space-y-3">
      <ul aria-label="Examples" className="grid grid-cols-2 gap-3 sm:grid-cols-4">
        {recipe.examples.map((example) => (
          <li key={example.position} className="overflow-hidden rounded-md bg-bg-muted">
            {example.url === null ? (
              <div className="flex aspect-square items-center justify-center p-2 text-center text-xs text-fg-faint">Files deleted</div>
            ) : example.content_type.startsWith('video/') ? (
              <video src={example.url} controls preload="metadata" className="block aspect-square w-full object-cover"
                     aria-label={`Example ${example.position}`} />
            ) : (
              <a href={example.url} target="_blank" rel="noopener noreferrer">
                <img src={example.url} alt={`Example ${example.position}`} className="block aspect-square w-full object-cover" />
              </a>
            )}
          </li>
        ))}
      </ul>
      <p className="whitespace-pre-wrap break-words embedded-surface p-3 text-sm text-fg" data-review-template>
        {parseTemplate(recipe.template).parts.map((part, i) => ('text' in part
          ? <span key={i}>{part.text}</span>
          : <mark key={i} className="code-chip text-fg">{`{{${part.name}}}`}</mark>))}
      </p>
      <dl className="grid grid-cols-[auto_minmax(0,1fr)] gap-x-6 gap-y-1 text-sm">
        {recipe.variables.map((variable) => (
          <div key={variable.name} className="contents">
            <dt className="font-mono text-[13px] text-fg-subtle">{variable.name}</dt>
            <dd className="whitespace-pre-wrap break-words text-fg">{variable.default === '' ? <span className="italic text-fg-subtle">empty</span> : variable.default}</dd>
          </div>
        ))}
        <dt className="text-fg-subtle">Model</dt>
        <dd className="break-all font-mono text-[13px] text-fg">{recipe.model}</dd>
        {settings && (
          <>
            <dt className="text-fg-subtle">Settings</dt>
            <dd className="text-fg">{settings}</dd>
          </>
        )}
        {recipe.inputs.length > 0 && (
          <>
            <dt className="text-fg-subtle">Inputs</dt>
            <dd className="text-fg">{recipe.inputs.map((slot) => slot.slot).join(', ')}</dd>
          </>
        )}
        <dt className="text-fg-subtle">Licence</dt>
        <dd className="text-fg">{recipe.licence}</dd>
        <dt className="text-fg-subtle">Tags</dt>
        <dd className="text-fg">{recipe.tags.length > 0 ? recipe.tags.join(', ') : 'none'}</dd>
        {recipe.made_from && (
          <>
            <dt className="text-fg-subtle">Made from</dt>
            <dd className="text-fg">{recipe.made_from.kind === 'gallery' ? 'gallery recipe' : 'community recipe'} <span className="font-mono">{recipe.made_from.id}</span></dd>
          </>
        )}
        {recipe.status === 'PUBLIC' && (
          <>
            <dt className="text-fg-subtle">Public page</dt>
            <dd>
              <a href={publicRecipeUrl(recipe.public_id)} target="_blank" rel="noopener noreferrer" className="text-brand-600 hover:underline">
                {recipe.public_id}
              </a>
            </dd>
          </>
        )}
      </dl>
    </div>
  );
}

type Asking = null | 'reject' | 'remove' | 'remove-profile';

/** The decisions an item allows: approving at once, or refusing with a reason the author sees. */
function Decisions({ item, name, onDecided }: { item: QueueItem; name: string; onDecided: (message: string) => Promise<void> }) {
  const [asking, setAsking] = useState<Asking>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);
  const publicId = item.recipe?.public_id ?? null;
  const handle = item.author.handle;

  const act = async (run: () => Promise<unknown>, message: string) => {
    setBusy(true);
    setProblem(null);
    try {
      await run();
      setAsking(null);
      setReason('');
      await onDecided(message);
    } catch (err) {
      setProblem(refusal(err, "Couldn't record that decision. Try again."));
    } finally {
      setBusy(false);
    }
  };

  const approve = () => {
    if (item.why === 'profile') {
      if (!handle || !window.confirm(`Approve @${handle}'s profile as it is now?`)) return;
      void act(() => moderation.approveProfile(handle), `Approved @${handle}'s profile.`);
      return;
    }
    if (!publicId) return;
    const question = item.why === 'reported'
      ? `Keep “${name}” public and close its reports?`
      : `Approve “${name}”? It becomes public at once, with its examples.`;
    if (!window.confirm(question)) return;
    void act(() => moderation.approve(publicId), item.why === 'reported' ? `Kept “${name}” public.` : `Approved “${name}”.`);
  };

  const refuse = (e: FormEvent) => {
    e.preventDefault();
    const why = reason.trim();
    if (why === '' || busy) return;
    if (asking === 'reject' && publicId) void act(() => moderation.reject(publicId, why), `Rejected “${name}”.`);
    if (asking === 'remove' && publicId) void act(() => moderation.remove(publicId, why), `Removed “${name}”.`);
    if (asking === 'remove-profile' && handle) void act(() => moderation.removeProfile(handle, why), `Removed @${handle}'s profile.`);
  };

  const refusing = { reject: 'Reject', remove: 'Remove', 'remove-profile': 'Remove profile' } as const;

  return (
    <div className="border-t border-border pt-3">
      {asking === null ? (
        <div className="flex flex-wrap items-center gap-2">
          <button type="button" onClick={approve} disabled={busy} className="btn btn-primary btn-sm">
            {item.why === 'profile' ? 'Approve profile' : item.why === 'reported' ? 'Keep it public' : 'Approve'}
          </button>
          {item.why === 'pending' && (
            <button type="button" onClick={() => setAsking('reject')} disabled={busy} className="btn btn-danger btn-sm">Reject…</button>
          )}
          {(item.why === 'pending' || item.why === 'reported') && (
            <button type="button" onClick={() => setAsking('remove')} disabled={busy}
                    className={`btn btn-sm ${item.why === 'reported' ? 'btn-danger' : 'btn-ghost'}`}>Remove…</button>
          )}
          {item.why === 'profile' && (
            <button type="button" onClick={() => setAsking('remove-profile')} disabled={busy} className="btn btn-danger btn-sm">
              Remove profile…
            </button>
          )}
        </div>
      ) : (
        <form onSubmit={refuse} aria-label={`${refusing[asking]} with a reason`} className="space-y-2">
          <label htmlFor={`reason-${publicId ?? handle}`} className="block text-sm font-medium text-fg">
            Why? The author sees this.
          </label>
          <textarea
            id={`reason-${publicId ?? handle}`}
            value={reason}
            onChange={(e) => setReason(e.target.value)}
            maxLength={REASON_MAX}
            rows={3}
            autoFocus
            className="input resize-y"
          />
          <div className="flex flex-wrap items-center gap-2">
            <button type="submit" disabled={busy || reason.trim() === ''} className="btn btn-danger btn-sm">
              {busy ? 'Saving…' : `${refusing[asking]} with this reason`}
            </button>
            <button type="button" onClick={() => { setAsking(null); setReason(''); }} disabled={busy} className="btn btn-ghost btn-sm">
              Cancel
            </button>
          </div>
        </form>
      )}
      {problem && <p role="alert" className="mt-2 text-sm text-danger">{problem}</p>}
    </div>
  );
}
