import { useEffect, useState } from 'react';
import type { SavedRecipe } from '../lib/api';
import { loadGalleryIndex } from '../lib/gallery';
import type { GalleryEntry } from '../lib/recipe';

const SHOWN = 12;

/**
 * The gallery recipes the user saved, for an empty Studio to start from.
 * Choosing one hands its slug to `onPick`, which loads it as its link would;
 * each can be removed from the list. A recipe's title comes from the
 * gallery's index; one the index doesn't list is shown by its slug.
 */
export default function SavedRecipes({ saved, onPick, onRemove }: {
  saved: SavedRecipe[];
  onPick: (slug: string) => void;
  onRemove: (slug: string) => void;
}) {
  const [entries, setEntries] = useState<Map<string, GalleryEntry>>(new Map());
  const [showAll, setShowAll] = useState(false);

  useEffect(() => {
    let cancelled = false;
    void loadGalleryIndex().then((all) => {
      if (!cancelled) setEntries(new Map(all.map((e) => [e.slug, e])));
    });
    return () => { cancelled = true; };
  }, []);

  if (saved.length === 0) return null;
  const shown = showAll ? saved : saved.slice(0, SHOWN);

  return (
    <section aria-labelledby="studio-saved-recipes" className="mt-6">
      <h2 id="studio-saved-recipes" className="text-sm font-semibold text-fg">Your saved recipes</h2>
      <ul className="mt-3 flex flex-wrap gap-2">
        {shown.map((recipe) => {
          const entry = entries.get(recipe.slug);
          const title = entry ? entry.title.en ?? entry.title.zh ?? recipe.slug : recipe.slug;
          const chinese = entry !== undefined && entry.title.en === null;
          return (
            <li key={recipe.slug}
                className="inline-flex max-w-full items-stretch overflow-hidden rounded-md border border-border bg-surface">
              <button
                type="button"
                onClick={() => onPick(recipe.slug)}
                lang={chinese ? 'zh-Hans' : undefined}
                className="min-w-0 truncate px-3 py-1.5 text-left text-xs font-medium text-fg hover:text-brand-600"
              >
                {title}
              </button>
              <button
                type="button"
                onClick={() => onRemove(recipe.slug)}
                aria-label={`Remove ${title} from your saved recipes`}
                title="Remove from your saved recipes"
                className="shrink-0 border-l border-border px-2.5 py-1.5 text-xs text-fg-subtle hover:text-danger"
              >
                <span aria-hidden="true">✕</span>
              </button>
            </li>
          );
        })}
      </ul>
      {saved.length > SHOWN && (
        <button type="button" onClick={() => setShowAll((all) => !all)} className="btn btn-ghost btn-sm mt-2 px-0">
          {showAll ? 'Show fewer' : `Show all ${saved.length}`}
        </button>
      )}
    </section>
  );
}
