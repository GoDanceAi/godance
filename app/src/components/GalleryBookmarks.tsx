import { useEffect, useRef, useState } from 'react';
import { Link } from 'react-router-dom';
import type { SavedRecipe } from '../lib/api';
import { ApiError } from '../lib/api';
import { useSignedInAccountId } from '../lib/after-sign-in';
import { loadGalleryIndex } from '../lib/gallery';
import type { GalleryEntry } from '../lib/recipe';
import { listSavedRecipes, unsaveRecipe } from '../lib/saved-recipes';

/** Bookmarks are gallery slugs, separate from the user's own versioned recipes. */
export default function GalleryBookmarks() {
  const account = useSignedInAccountId();
  const current = useRef(account);
  current.current = account;
  const [saved, setSaved] = useState<SavedRecipe[] | null>(null);
  const [entries, setEntries] = useState<Map<string, GalleryEntry> | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [read, setRead] = useState(0);

  useEffect(() => {
    let active = true;
    setSaved(null);
    setError(null);
    setBusy(null);
    if (!account) return;
    listSavedRecipes().then(
      (list) => { if (active) setSaved(list); },
      (err: unknown) => { if (active) setError(err instanceof ApiError && err.status === 404
        ? 'Gallery bookmarks are unavailable for this account.' : 'Could not load your gallery bookmarks.'); },
    );
    return () => { active = false; };
  }, [account, read]);

  useEffect(() => {
    let active = true;
    void loadGalleryIndex().then((list) => { if (active) setEntries(new Map(list.map((entry) => [entry.slug, entry]))); });
    return () => { active = false; };
  }, []);

  const remove = async (slug: string) => {
    if (busy) return;
    const owner = account;
    setBusy(slug);
    setError(null);
    try {
      await unsaveRecipe(slug);
      if (current.current === owner) setSaved((list) => list?.filter((item) => item.slug !== slug) ?? null);
    } catch {
      if (current.current === owner) setError('Could not remove the bookmark. Try again.');
    } finally {
      if (current.current === owner) setBusy(null);
    }
  };

  return (
    <section aria-labelledby="gallery-bookmarks" className="mt-10 border-t border-border pt-8">
      <h2 id="gallery-bookmarks" className="text-lg font-semibold text-fg">Gallery bookmarks</h2>
      <p className="mt-2 text-sm text-fg-muted">
        Examples you bookmarked in the Studio. These are links to gallery setups, separate from your own versioned recipes above.
        Generated outputs are in <Link to="/history" className="text-brand-600 hover:underline">History</Link>.
      </p>
      {error && <div className="mt-3 text-sm"><p role="alert" className="text-danger">{error}</p>
        {saved === null && <button type="button" className="btn btn-ghost btn-sm mt-2" onClick={() => setRead((value) => value + 1)}>Retry bookmarks</button>}
      </div>}
      {!error && saved === null && <p className="mt-3 text-sm text-fg-faint">Loading bookmarks…</p>}
      {saved?.length === 0 && <p className="mt-3 text-sm text-fg-muted">No gallery bookmarks yet. Open an example in the Studio and choose Save recipe to bookmark it.</p>}
      {saved !== null && saved.length > 0 && <ul className="mt-4 grid gap-3 sm:grid-cols-2 lg:grid-cols-3">
        {saved.map((item) => {
          const entry = entries?.get(item.slug);
          const title = entry?.title.en ?? entry?.title.zh ?? item.slug;
          return <li key={item.slug} className="card min-w-0">
            <Link to={`/create/${entry?.modality === 'VIDEO' ? 'video' : 'image'}?example=${encodeURIComponent(item.slug)}`}
              className="break-words font-semibold text-brand-600 hover:underline">{title}</Link>
            {entries !== null && !entry && <p className="mt-1 text-xs text-fg-muted">Not in the current gallery. Opening it checks availability; an unavailable example leaves the Studio form empty.</p>}
            <button type="button" onClick={() => void remove(item.slug)} disabled={busy !== null}
              aria-label={`Remove ${title} from gallery bookmarks`} className="btn btn-ghost btn-sm mt-3">
              {busy === item.slug ? 'Removing…' : 'Remove bookmark'}
            </button>
          </li>;
        })}
      </ul>}
    </section>
  );
}
