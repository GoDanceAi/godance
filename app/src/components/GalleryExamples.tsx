import { useEffect, useState } from 'react';
import type { MediaModality } from '../lib/api';
import { GALLERY_ORIGIN, loadGalleryIndex } from '../lib/gallery';
import type { GalleryEntry } from '../lib/recipe';

const SHOWN = 6;

/**
 * Up to six of the gallery's recipes of one modality, for an empty Studio to
 * start from. Choosing one hands its slug to `onPick`. Nothing is shown when
 * the gallery has none, is switched off or can't be reached.
 */
export default function GalleryExamples({ modality, onPick }: {
  modality: MediaModality;
  onPick: (slug: string) => void;
}) {
  const [entries, setEntries] = useState<GalleryEntry[]>([]);

  useEffect(() => {
    let cancelled = false;
    void loadGalleryIndex().then((all) => {
      if (!cancelled) setEntries(all.filter((e) => e.modality === modality).slice(0, SHOWN));
    });
    return () => { cancelled = true; };
  }, [modality]);

  if (entries.length === 0) return null;

  return (
    <section aria-labelledby="studio-examples" className="mt-6">
      <div className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-1">
        <h2 id="studio-examples" className="text-sm font-semibold text-fg">Start from an example</h2>
        <a href={`${GALLERY_ORIGIN}/gallery`} target="_blank" rel="noopener noreferrer"
           className="text-sm text-brand-600 hover:underline">
          Browse the gallery
        </a>
      </div>
      {/* One row: on a phone it scrolls sideways instead of pushing the form down. */}
      <ul className="mt-3 flex gap-3 overflow-x-auto pb-1 lg:grid lg:grid-cols-6 lg:overflow-visible lg:pb-0">
        {entries.map((entry) => {
          const chinese = entry.title.en === null;
          return (
            <li key={entry.slug} className="w-36 shrink-0 lg:w-auto">
              <button
                type="button"
                onClick={() => onPick(entry.slug)}
                className="card-hover flex h-full w-full flex-col overflow-hidden rounded-md border border-border bg-surface text-left"
              >
                {entry.thumb ? (
                  <img src={entry.thumb} alt="" loading="lazy" referrerPolicy="no-referrer"
                       className="aspect-square w-full bg-bg-muted object-cover" />
                ) : (
                  <span aria-hidden="true" className="block aspect-square w-full bg-bg-muted" />
                )}
                <span lang={chinese ? 'zh-Hans' : undefined}
                      className="line-clamp-2 block px-2.5 py-2 text-xs font-medium text-fg">
                  {entry.title.en ?? entry.title.zh}
                </span>
              </button>
            </li>
          );
        })}
      </ul>
    </section>
  );
}
