import { useEffect, useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, type UserRecipe } from '../lib/api';
import { formatCents } from '../lib/auth';
import { formatAge, formatDateTime } from '../lib/time';
import { attemptsText, listRecipes } from '../lib/user-recipes';

const PAGE_SIZE = 24;

/** /recipes — the user's own recipes, newest first, a page at a time. */
export default function RecipesPage() {
  const [recipes, setRecipes] = useState<UserRecipe[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [hasMore, setHasMore] = useState(false);
  const [loadingMore, setLoadingMore] = useState(false);
  const [moreFailed, setMoreFailed] = useState(false);

  useEffect(() => {
    let cancelled = false;
    listRecipes({ limit: PAGE_SIZE })
      .then((page) => {
        if (cancelled) return;
        setRecipes(page);
        setHasMore(page.length === PAGE_SIZE);
      })
      .catch((err: unknown) => {
        if (!cancelled) setError(err instanceof ApiError ? err.detail || err.message : 'Could not load your recipes.');
      });
    return () => { cancelled = true; };
  }, []);

  const loadMore = async () => {
    const last = recipes?.at(-1);
    if (!last) return;
    setLoadingMore(true);
    setMoreFailed(false);
    try {
      const page = await listRecipes({ limit: PAGE_SIZE, before: last.id });
      setRecipes((cur) => {
        const seen = new Set(cur?.map((r) => r.id));
        return [...(cur ?? []), ...page.filter((r) => !seen.has(r.id))];
      });
      setHasMore(page.length === PAGE_SIZE);
    } catch {
      setMoreFailed(true);
    } finally {
      setLoadingMore(false);
    }
  };

  return (
    <div className="mx-auto max-w-6xl px-6 py-8">
      <h1 className="text-2xl font-bold tracking-tight text-fg">Recipes</h1>
      <p className="mt-2 text-sm text-fg-muted">
        A recipe keeps a setup that works: the prompt with its variables, the model and the settings.
        Every run of it is counted here, with what it cost.
      </p>

      {error && <p role="alert" className="mt-6 text-sm text-danger">{error}</p>}
      {!error && recipes === null && <p className="mt-6 text-sm text-fg-faint">Loading…</p>}

      {recipes?.length === 0 && (
        <div className="mt-6 card py-12 text-center">
          <p className="font-semibold text-fg">No recipes yet.</p>
          <p className="mt-1 text-sm text-fg-muted">
            Set something up in the Studio, then choose Save as recipe to keep it.
          </p>
          <div className="mt-5 flex flex-wrap justify-center gap-2">
            <Link to="/create/image" className="btn btn-primary btn-sm">Create an image</Link>
            <Link to="/create/video" className="btn btn-secondary btn-sm">Create a video</Link>
          </div>
        </div>
      )}

      {recipes !== null && recipes.length > 0 && (
        <>
          <ul className="mt-6 grid gap-4 sm:grid-cols-2 lg:grid-cols-3">
            {recipes.map((recipe) => (
              <li key={recipe.id} className="card card-hover relative flex flex-col gap-2">
                <Link to={`/recipes/${recipe.id}`}
                      className="line-clamp-2 break-words font-semibold text-fg after:absolute after:inset-0">
                  {recipe.title}
                </Link>
                <p className="text-xs text-fg-subtle">
                  {recipe.modality === 'VIDEO' ? 'Video' : 'Image'}
                  <span aria-hidden="true"> · </span>
                  <span className="break-all font-mono text-[11px]">{recipe.latest_version.model}</span>
                </p>
                <dl className="mt-auto flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 pt-2 text-sm">
                  <div>
                    <dt className="sr-only">Attempts</dt>
                    <dd className="text-fg-muted">{attemptsText(recipe.attempts)}</dd>
                  </div>
                  <div>
                    <dt className="sr-only">Spent</dt>
                    <dd className="font-semibold tabular-nums text-fg">{formatCents(recipe.spent_cents)} spent</dd>
                  </div>
                </dl>
                <time dateTime={recipe.updated_at} title={formatDateTime(recipe.updated_at)} className="text-xs text-fg-faint">
                  Updated {formatAge(recipe.updated_at)}
                </time>
              </li>
            ))}
          </ul>
          {hasMore && (
            <div className="mt-8 flex flex-col items-center gap-2">
              <button type="button" onClick={() => void loadMore()} disabled={loadingMore} className="btn btn-secondary">
                {loadingMore ? 'Loading…' : 'Load more'}
              </button>
              {moreFailed && <p role="alert" className="text-sm text-danger">Couldn't load more. Try again.</p>}
            </div>
          )}
        </>
      )}
    </div>
  );
}
