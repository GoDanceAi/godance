import { useEffect, useState } from 'react';
import type { FormEvent } from 'react';
import { Link, useNavigate, useParams } from 'react-router-dom';
import { ApiError, type JobView, type UserRecipe, type UserRecipeVersion } from '../lib/api';
import { formatCents, useAuth } from '../lib/auth';
import { COMPARE_MAX, COMPARE_MIN, compareLink, toggleSelected } from '../lib/compare';
import { costText, listJobs, useWatchedJob } from '../lib/jobs';
import { parseTemplate } from '../lib/template';
import { formatAge, formatDateTime } from '../lib/time';
import {
  attemptsText,
  deleteRecipe,
  fetchRecipe,
  fetchVersions,
  isRecipeId,
  paramsText,
  renameRecipe,
  studioLink,
  TITLE_MAX_LENGTH,
} from '../lib/user-recipes';
import { JobThumb } from '../components/JobMedia';
import JobStatusPill from '../components/JobStatusPill';
import SaveJobButton from '../components/SaveJobButton';
import VariableValues from '../components/VariableValues';

const PAGE_SIZE = 24;

type Load = { recipe: UserRecipe } | { missing: true } | { failed: string };

/** /recipes/:id — one of the user's recipes: its latest version, its versions, and every attempt with its cost. */
export default function RecipePage() {
  const { recipeId = '' } = useParams();
  const [load, setLoad] = useState<Load | null>(null);

  useEffect(() => {
    let cancelled = false;
    setLoad(null);
    if (!isRecipeId(recipeId)) {
      setLoad({ missing: true });
      return;
    }
    fetchRecipe(recipeId)
      .then((recipe) => { if (!cancelled) setLoad({ recipe }); })
      .catch((err: unknown) => {
        if (cancelled) return;
        setLoad(err instanceof ApiError && err.status === 404
          ? { missing: true }
          : { failed: err instanceof ApiError ? err.detail || err.message : 'Could not load this recipe.' });
      });
    return () => { cancelled = true; };
  }, [recipeId]);

  return (
    <div className="mx-auto max-w-4xl px-6 py-8">
      <Link to="/recipes" className="text-sm text-fg-muted hover:text-fg">← Recipes</Link>
      {load === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}
      {load && 'missing' in load && (
        <div className="mt-6 card py-10 text-center">
          <p className="font-semibold text-fg">Recipe not found.</p>
          <p className="mt-1 text-sm text-fg-muted">It may have been deleted, or the link is incomplete.</p>
        </div>
      )}
      {load && 'failed' in load && <p role="alert" className="mt-6 text-sm text-danger">{load.failed}</p>}
      {load && 'recipe' in load && <RecipeDetail key={load.recipe.id} initial={load.recipe} />}
    </div>
  );
}

function RecipeDetail({ initial }: { initial: UserRecipe }) {
  const navigate = useNavigate();
  const [recipe, setRecipe] = useState(initial);
  const [versions, setVersions] = useState<UserRecipeVersion[] | null>(null);
  const [deleting, setDeleting] = useState(false);
  const [deleteFailed, setDeleteFailed] = useState(false);
  const latest = recipe.latest_version;

  useEffect(() => {
    let cancelled = false;
    // The latest version is already on the page; the list is not needed to read it.
    fetchVersions(initial.id).then(
      (list) => { if (!cancelled) setVersions(list); },
      () => { if (!cancelled) setVersions(null); },
    );
    return () => { cancelled = true; };
  }, [initial.id]);

  const remove = async () => {
    const kept = recipe.attempts === 0
      ? ''
      : ` The ${recipe.attempts === 1 ? 'generation' : `${recipe.attempts.toLocaleString('en-US')} generations`} made with it `
        + `${recipe.attempts === 1 ? 'is' : 'are'} kept in History.`;
    if (!window.confirm(`Delete “${recipe.title}” and every version of it?${kept} This can't be undone.`)) return;
    setDeleting(true);
    setDeleteFailed(false);
    try {
      await deleteRecipe(recipe.id);
      navigate('/recipes');
    } catch {
      setDeleteFailed(true);
      setDeleting(false);
    }
  };

  const older = versions?.filter((v) => v.id !== latest.id) ?? [];

  return (
    <article className="mt-4">
      <Title recipe={recipe} onRenamed={setRecipe} />
      <p className="mt-1 text-sm text-fg-subtle">
        {recipe.modality === 'VIDEO' ? 'Video' : 'Image'} recipe
        <span aria-hidden="true"> · </span>made {formatDateTime(recipe.created_at)}
        {recipe.source_gallery_recipe && (
          <>
            <span aria-hidden="true"> · </span>from the gallery recipe{' '}
            <span className="break-all font-mono text-[12px]">{recipe.source_gallery_recipe}</span>
          </>
        )}
      </p>

      <div className="mt-5 flex flex-wrap items-center gap-2">
        <Link to={studioLink(recipe)} className="btn btn-primary">Open in Studio</Link>
        <button type="button" onClick={() => void remove()} disabled={deleting} className="btn btn-danger">
          {deleting ? 'Deleting…' : 'Delete recipe'}
        </button>
      </div>
      {deleteFailed && <p role="alert" className="mt-2 text-sm text-danger">Couldn't delete this recipe. Try again.</p>}

      <section aria-labelledby="recipe-latest" className="mt-8">
        <h2 id="recipe-latest" className="text-sm font-semibold text-fg">Version {latest.number}, the latest</h2>
        <VersionBody version={latest} />
      </section>

      <section aria-labelledby="recipe-versions" className="mt-8">
        <h2 id="recipe-versions" className="text-sm font-semibold text-fg">Versions</h2>
        <ol className="mt-2 card divide-y divide-border p-0">
          <li className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 px-4 py-3 text-sm">
            <span className="font-medium text-fg">Version {latest.number} <span className="font-normal text-fg-subtle">· latest</span></span>
            <VersionMeta version={latest} />
          </li>
          {older.map((version) => (
            <li key={version.id} className="px-4 py-3 text-sm">
              <details>
                <summary className="flex cursor-pointer flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                  <span className="font-medium text-fg">Version {version.number}</span>
                  <VersionMeta version={version} />
                </summary>
                <VersionBody version={version} />
              </details>
            </li>
          ))}
        </ol>
        {versions === null && latest.number > 1 && (
          <p className="mt-2 text-xs text-fg-faint">Earlier versions couldn't be loaded.</p>
        )}
      </section>

      <Attempts recipe={recipe} />
    </article>
  );
}

/** The recipe's title, which can be changed in place. */
function Title({ recipe, onRenamed }: { recipe: UserRecipe; onRenamed: (recipe: UserRecipe) => void }) {
  const [editing, setEditing] = useState(false);
  const [title, setTitle] = useState(recipe.title);
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<string | null>(null);

  const save = async (e: FormEvent) => {
    e.preventDefault();
    const next = title.trim();
    if (next === '' || busy) return;
    if (next === recipe.title) {
      setEditing(false);
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      // Only the title comes from the answer: the counts on the page stay as they were read.
      const renamed = await renameRecipe(recipe.id, next);
      onRenamed({ ...recipe, title: renamed.title, updated_at: renamed.updated_at });
      setEditing(false);
    } catch (err) {
      setProblem(err instanceof ApiError && !err.message.startsWith('HTTP ') ? err.message : "Couldn't rename this recipe. Try again.");
    } finally {
      setBusy(false);
    }
  };

  if (!editing) {
    return (
      <header className="flex flex-wrap items-center gap-3">
        <h1 className="min-w-0 break-words text-2xl font-bold tracking-tight text-fg">{recipe.title}</h1>
        <button
          type="button"
          onClick={() => {
            setTitle(recipe.title);
            setProblem(null);
            setEditing(true);
          }}
          className="btn btn-ghost btn-sm"
        >
          Rename
        </button>
      </header>
    );
  }
  return (
    <form onSubmit={(e) => void save(e)} aria-label="Rename recipe">
      <label htmlFor="recipe-title" className="block text-xs font-medium text-fg">Recipe title</label>
      <div className="mt-1 flex flex-wrap items-center gap-2">
        <input
          id="recipe-title"
          type="text"
          value={title}
          onChange={(e) => setTitle(e.target.value)}
          maxLength={TITLE_MAX_LENGTH}
          autoComplete="off"
          autoFocus
          className="input min-w-0 flex-1"
        />
        <button type="submit" disabled={busy || title.trim() === ''} className="btn btn-primary btn-sm">
          {busy ? 'Saving…' : 'Save title'}
        </button>
        <button type="button" onClick={() => setEditing(false)} disabled={busy} className="btn btn-ghost btn-sm">Cancel</button>
      </div>
      {problem && <p role="alert" className="mt-1.5 text-xs text-danger">{problem}</p>}
    </form>
  );
}

function VersionMeta({ version }: { version: UserRecipeVersion }) {
  return (
    <span className="text-xs text-fg-subtle">
      <span className="break-all font-mono text-[11px]">{version.model}</span>
      <span aria-hidden="true"> · </span>
      <time dateTime={version.created_at}>{formatDateTime(version.created_at)}</time>
    </span>
  );
}

/** A version's template, with its variables marked, their defaults, and the model and settings it keeps. */
function VersionBody({ version }: { version: UserRecipeVersion }) {
  const settings = paramsText(version.params ?? {});
  return (
    <div className="mt-2 space-y-3">
      <p className="whitespace-pre-wrap break-words embedded-surface p-4 text-sm text-fg">
        {parseTemplate(version.template).parts.map((part, i) => ('text' in part
          ? <span key={i}>{part.text}</span>
          : <mark key={i} className="code-chip text-fg">{`{{${part.name}}}`}</mark>))}
      </p>
      <dl className="card grid grid-cols-[auto_minmax(0,1fr)] gap-x-6 gap-y-2 text-sm">
        {version.variables.map((variable) => (
          <div key={variable.name} className="contents">
            <dt className="font-mono text-[13px] text-fg-subtle">{variable.name}</dt>
            <dd className="whitespace-pre-wrap break-words text-fg">
              {variable.default === '' ? <span className="italic text-fg-subtle">empty</span> : variable.default}
            </dd>
          </div>
        ))}
        <dt className="text-fg-subtle">Model</dt>
        <dd className="break-all font-mono text-[13px] text-fg">{version.model}</dd>
        {settings && (
          <>
            <dt className="text-fg-subtle">Settings</dt>
            <dd className="text-fg">{settings}</dd>
          </>
        )}
      </dl>
    </div>
  );
}

/**
 * Every job that ran a version of the recipe, newest first, with the total
 * they cost. Up to four can be picked to compare side by side.
 */
function Attempts({ recipe }: { recipe: UserRecipe }) {
  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [failed, setFailed] = useState(false);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);
  const [selected, setSelected] = useState<string[]>([]);

  useEffect(() => {
    let cancelled = false;
    listJobs({ limit: PAGE_SIZE, recipe: recipe.id }).then(
      (page) => {
        if (cancelled) return;
        setJobs(page);
        setHasMore(page.length === PAGE_SIZE);
      },
      () => { if (!cancelled) setFailed(true); },
    );
    return () => { cancelled = true; };
  }, [recipe.id]);

  const loadMore = async () => {
    const last = jobs?.at(-1);
    if (!last) return;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const page = await listJobs({ limit: PAGE_SIZE, recipe: recipe.id, before: last.id });
      setJobs((cur) => {
        const seen = new Set(cur?.map((j) => j.id));
        return [...(cur ?? []), ...page.filter((j) => !seen.has(j.id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch {
      setMoreFailed(true);
    } finally {
      setLoadingMore(false);
    }
  };

  const full = selected.length >= COMPARE_MAX;

  return (
    <section aria-labelledby="recipe-attempts" className="mt-8">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 id="recipe-attempts" className="text-sm font-semibold text-fg">Attempts</h2>
        {/* What every attempt cost so far, from the API: the list below may show only the newest. */}
        <p className="text-sm text-fg-muted">
          {attemptsText(recipe.attempts)}
          <span aria-hidden="true"> · </span>
          <strong className="font-semibold tabular-nums text-fg">{formatCents(recipe.spent_cents)}</strong> spent in total
        </p>
      </div>

      {failed && <p role="alert" className="mt-3 text-sm text-danger">Couldn't load this recipe's attempts.</p>}
      {!failed && jobs === null && <p className="mt-3 text-sm text-fg-faint">Loading…</p>}
      {jobs?.length === 0 && (
        <p className="mt-3 text-sm text-fg-muted">
          Nothing has been made with this recipe yet. <Link to={studioLink(recipe)} className="text-brand-600 hover:underline">Open it in the Studio</Link> to run it.
        </p>
      )}

      {jobs !== null && jobs.length > 0 && (
        <>
          <div className="mt-3 flex flex-wrap items-center gap-3">
            {selected.length >= COMPARE_MIN ? (
              <Link to={compareLink(selected)} className="btn btn-secondary btn-sm">
                Compare {selected.length}
              </Link>
            ) : (
              <button type="button" disabled className="btn btn-secondary btn-sm">Compare</button>
            )}
            <p aria-live="polite" className="text-xs text-fg-subtle">
              {selected.length === 0
                ? `Tick two to ${COMPARE_MAX === 4 ? 'four' : COMPARE_MAX} attempts to see them side by side.`
                : `${selected.length} selected${full ? ', the most that fit side by side' : ''}.`}
            </p>
          </div>
          <ul className="mt-3 space-y-3">
            {jobs.map((job) => (
              <AttemptRow
                key={job.id}
                initial={job}
                selected={selected.includes(job.id)}
                selectable={!full || selected.includes(job.id)}
                onToggle={() => setSelected((cur) => toggleSelected(cur, job.id))}
              />
            ))}
          </ul>
          {hasMore && (
            <div className="mt-6 flex flex-col items-center gap-2">
              <button type="button" onClick={() => void loadMore()} disabled={loadingMore} className="btn btn-secondary">
                {loadingMore ? 'Loading…' : 'Load more'}
              </button>
              {moreFailed && <p role="alert" className="text-sm text-danger">Couldn't load more. Try again.</p>}
            </div>
          )}
        </>
      )}
    </section>
  );
}

/** One attempt: what it made, which version it ran with which values, what it cost, and the save control. */
function AttemptRow({ initial, selected, selectable, onToggle }: {
  initial: JobView;
  selected: boolean;
  selectable: boolean;
  onToggle: () => void;
}) {
  const { refresh } = useAuth();
  const watched = useWatchedJob(initial, () => void refresh());
  const [savedState, setSavedState] = useState<Pick<JobView, 'saved' | 'mediaExpiresAt'> | null>(null);
  const job = savedState ? { ...watched, ...savedState } : watched;
  const when = formatAge(job.createdAt);

  return (
    <li className="card flex flex-wrap gap-4 p-3 sm:flex-nowrap">
      <div className="flex shrink-0 items-start gap-3">
        <input
          type="checkbox"
          checked={selected}
          disabled={!selectable}
          onChange={onToggle}
          aria-label={`Compare the attempt from ${when}`}
          className="mt-1 h-4 w-4 accent-[var(--color-primary)]"
        />
        <Link to={`/history/${job.id}`} aria-label={`Open the attempt from ${when}`} className="block w-24 overflow-hidden rounded-md">
          <JobThumb job={job} />
        </Link>
      </div>
      <div className="min-w-0 flex-1 space-y-2">
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-fg-subtle">
          {job.recipe && <span className="font-semibold text-fg">Version {job.recipe.version}</span>}
          <span aria-live="polite"><JobStatusPill status={job.status} /></span>
          <span className="tabular-nums text-fg">{costText(job)}</span>
          <time dateTime={job.createdAt} title={formatDateTime(job.createdAt)}>{when}</time>
          <span className="break-all font-mono text-[11px]">{job.model}</span>
        </div>
        {job.recipe && <VariableValues recipe={job.recipe} />}
        <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
          <SaveJobButton
            job={job}
            onChange={(next) => setSavedState({ saved: next.saved, mediaExpiresAt: next.mediaExpiresAt })}
            className="btn btn-ghost btn-sm -ml-2 px-2 py-1"
          />
        </div>
      </div>
    </li>
  );
}
