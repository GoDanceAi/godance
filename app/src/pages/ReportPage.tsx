import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, Navigate, useSearchParams } from 'react-router-dom';
import {
  codeOf,
  getPublicRecipe,
  isHandle,
  isPublicId,
  publicProfileUrl,
  publicRecipeUrl,
  REASON_MAX,
  refusal,
  report,
  type PublicRecipe,
  type ReportKind,
} from '../lib/community';
import { useCommunity } from '../lib/community-context';

/** What a report link names, when its kind and id have the right shape. */
function target(params: URLSearchParams): { kind: ReportKind; id: string } | null {
  const kind = params.get('kind');
  const id = params.get('id')?.trim() ?? '';
  if (kind === 'published_recipe' && isPublicId(id)) return { kind, id };
  if (kind === 'profile' && isHandle(id)) return { kind, id };
  return null;
}

/**
 * /report?kind=published_recipe|profile&id=…: tell the moderators about a
 * public recipe or profile. Signed in only (the route is protected), and only
 * while the community is open. The report goes to a moderator; what is
 * reported stays up until one decides.
 */
export default function ReportPage() {
  const { open, loading } = useCommunity();
  const [params] = useSearchParams();
  const named = target(params);

  if (loading) {
    return <div className="flex min-h-[40vh] items-center justify-center text-sm text-fg-faint">Loading…</div>;
  }
  if (!open) return <Navigate to="/dashboard" replace />;

  return (
    <div className="mx-auto max-w-2xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Report</h1>
      {named ? (
        <ReportForm key={`${named.kind}:${named.id}`} kind={named.kind} id={named.id} />
      ) : (
        <div className="mt-6 card">
          <p className="font-semibold text-fg">This report link is incomplete.</p>
          <p className="mt-1 text-sm text-fg-muted">
            Open the recipe or profile you want to report on godance.ai, and use its Report link.
          </p>
        </div>
      )}
    </div>
  );
}

function ReportForm({ kind, id }: { kind: ReportKind; id: string }) {
  const [recipe, setRecipe] = useState<PublicRecipe | null | 'missing'>(null);
  const [reason, setReason] = useState('');
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState(false);
  const [gone, setGone] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  // For a recipe, its title and author, so the reader sees what they report.
  useEffect(() => {
    if (kind !== 'published_recipe') return;
    let cancelled = false;
    getPublicRecipe(id).then(
      (found) => { if (!cancelled) setRecipe(found); },
      // Only a recipe the API says is not public is gone; on any other failure the form still names it by id.
      (err: unknown) => { if (!cancelled && codeOf(err) === 'not_found') setRecipe('missing'); },
    );
    return () => { cancelled = true; };
  }, [kind, id]);

  const trimmed = reason.trim();
  const submit = async (e: FormEvent) => {
    e.preventDefault();
    if (busy || trimmed === '') return;
    setBusy(true);
    setProblem(null);
    try {
      await report({ kind, id, reason: trimmed });
      setSent(true);
    } catch (err) {
      if (codeOf(err) === 'not_found') setGone(true);
      else setProblem(refusal(err, "Couldn't send your report. Try again."));
    } finally {
      setBusy(false);
    }
  };

  const what = kind === 'profile'
    ? <>the profile <a href={publicProfileUrl(id)} target="_blank" rel="noopener noreferrer" className="font-medium text-brand-600 hover:underline">@{id}</a></>
    : recipe !== null && recipe !== 'missing'
      ? <>the recipe <a href={publicRecipeUrl(id)} target="_blank" rel="noopener noreferrer" className="font-medium text-brand-600 hover:underline">“{recipe.title}”</a> by @{recipe.author.handle}</>
      : <>the recipe <a href={publicRecipeUrl(id)} target="_blank" rel="noopener noreferrer" className="font-mono text-brand-600 hover:underline">{id}</a></>;

  if (sent) {
    return (
      <div className="mt-6 card" role="status">
        <p className="font-semibold text-fg">Thank you. Your report is with the moderators.</p>
        <p className="mt-1 text-sm text-fg-muted">
          A moderator will look at {what}. It stays up until one decides.
        </p>
        <Link to="/dashboard" className="mt-4 btn btn-secondary btn-sm">Back to the dashboard</Link>
      </div>
    );
  }
  if (gone || recipe === 'missing') {
    return (
      <div className="mt-6 card">
        <p className="font-semibold text-fg">There is nothing to report.</p>
        <p className="mt-1 text-sm text-fg-muted">
          {kind === 'profile' ? `@${id} has no public profile` : 'That recipe is not public'} any more.
        </p>
      </div>
    );
  }

  return (
    <form onSubmit={(e) => void submit(e)} aria-label="Report" className="mt-6 card space-y-4">
      <p className="text-sm text-fg-muted">You are reporting {what} to the moderators.</p>
      <div>
        <label htmlFor="report-reason" className="block text-sm font-medium text-fg">What is wrong with it?</label>
        <textarea
          id="report-reason"
          value={reason}
          onChange={(e) => setReason(e.target.value)}
          maxLength={REASON_MAX}
          rows={5}
          required
          aria-describedby="report-reason-note"
          className="input mt-1.5 resize-y"
        />
        <p id="report-reason-note" className="mt-1.5 text-xs text-fg-subtle">
          A moderator reads this. Say what breaks the rules, and where. {reason.length.toLocaleString('en-US')} of {REASON_MAX.toLocaleString('en-US')} characters.
        </p>
      </div>
      {problem && <p role="alert" className="text-sm text-danger">{problem}</p>}
      <div className="flex flex-wrap items-center gap-3">
        <button type="submit" disabled={busy || trimmed === ''} className="btn btn-primary btn-sm">
          {busy ? 'Sending…' : 'Send report'}
        </button>
        <p className="text-xs text-fg-subtle">It stays up until a moderator decides.</p>
      </div>
    </form>
  );
}
