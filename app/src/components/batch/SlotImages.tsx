import { useId, useState } from 'react';
import type { RecipeInputSlot } from '../../lib/api';
import { rowList, type SlotPlan } from '../../lib/batch-table';
import type { Uploader } from '../../lib/batch-uploads';
import { INPUT_ACCEPT, ROLE_LABEL } from '../../lib/inputs';

const ACCEPTED = INPUT_ACCEPT.split(',');
/** How many file names a sentence lists before it counts the rest. */
const NAMES_SHOWN = 5;

function plural(n: number, one: string, many = `${one}s`): string {
  return `${n.toLocaleString('en-US')} ${n === 1 ? one : many}`;
}

function names(files: readonly File[]): string {
  const shown = files.slice(0, NAMES_SHOWN).map((file) => file.name).join(', ');
  return files.length > NAMES_SHOWN ? `${shown} and ${files.length - NAMES_SHOWN} more` : shown;
}

/**
 * The images of a batch: for each input slot of the recipe, many files
 * chosen or dropped at once and given to rows, by the file names in a
 * column where the table has one, otherwise in name order. Says which rows
 * still have no image and which files match no row, and shows the uploads
 * as they go.
 */
export default function SlotImages({
  slots, header, hasTable, rowCount, files, plans, assignment, needed, uploader, held, mayAdd, onAdd, onClear, onPlan,
}: {
  slots: readonly RecipeInputSlot[];
  header: readonly string[];
  hasTable: boolean;
  rowCount: number;
  /** The files chosen for each slot. */
  files: Readonly<Record<string, readonly File[]>>;
  plans: Readonly<Record<string, SlotPlan>>;
  /** For each slot, the file each row gets, or null. */
  assignment: Readonly<Record<string, readonly (File | null)[]>>;
  /** Every file some row uses, each once: what is uploaded. */
  needed: readonly File[];
  uploader: Uploader;
  /** Why nothing is uploaded yet, when the table has to change first. */
  held: string | null;
  /** False when the user may not add an image yet: the input terms open instead. */
  mayAdd: () => boolean;
  onAdd: (slot: string, files: File[]) => void;
  onClear: (slot: string) => void;
  onPlan: (slot: string, plan: SlotPlan) => void;
}) {
  const baseId = useId();
  const [refused, setRefused] = useState<Record<string, string>>({});
  const [over, setOver] = useState<string | null>(null);

  const add = (slot: string, list: FileList | null | undefined) => {
    const chosen = Array.from(list ?? []);
    if (chosen.length === 0 || !mayAdd()) return;
    // Some systems give a dropped file no type: then its name decides.
    const images = chosen.filter((file) => (file.type === ''
      ? /\.(png|jpe?g|webp)$/i.test(file.name)
      : ACCEPTED.includes(file.type)));
    const others = chosen.length - images.length;
    setRefused((current) => ({
      ...current,
      [slot]: others === 0 ? '' : `${plural(others, 'file')} left out: an input is a PNG, JPEG or WebP image.`,
    }));
    if (images.length > 0) onAdd(slot, images);
  };

  const { wanted, done, failed } = uploader.counts;
  // The files that could not be stored, by the reason each was given.
  const failures = new Map<string, File[]>();
  for (const file of needed) {
    const upload = uploader.of(file);
    if (upload?.status === 'failed') failures.set(upload.error, [...(failures.get(upload.error) ?? []), file]);
  }
  const uploading = wanted > 0 && done + failed < wanted;
  // Said to a screen reader in steps, not once per file.
  const step = wanted === 0 ? 0 : Math.floor((done / wanted) * 10);
  const spoken = wanted === 0 ? ''
    : done === wanted ? `All ${plural(wanted, 'image')} uploaded.`
    : step > 0 ? `Uploading images: about ${step * 10}% done.`
    : 'Uploading images.';

  return (
    <section aria-labelledby="batch-images">
      <h2 id="batch-images" className="text-sm font-semibold text-fg">Images</h2>
      <p className="mt-1 text-sm text-fg-muted">
        This recipe takes {slots.length === 1 ? 'an image' : `${slots.length} images`} for every row.
        Choose or drop all of them at once.
        {!hasTable && ' With no table, each image is one row.'}
      </p>

      <div className="mt-3 space-y-4">
        {slots.map((slot, i) => {
          const mine = files[slot.slot] ?? [];
          const plan = plans[slot.slot] ?? { by: 'order' };
          const given = assignment[slot.slot] ?? [];
          const missing = given.flatMap((file, row) => (file === null ? [row] : []));
          const used = new Set(given.filter((file): file is File => file !== null));
          const unused = mine.filter((file) => !used.has(file));
          const id = `${baseId}-${i}`;
          return (
            <div
              key={slot.slot}
              data-batch-slot={slot.slot}
              onDragOver={(e) => {
                e.preventDefault();
                setOver(slot.slot);
              }}
              onDragLeave={() => setOver((current) => (current === slot.slot ? null : current))}
              onDrop={(e) => {
                e.preventDefault();
                setOver(null);
                add(slot.slot, e.dataTransfer.files);
              }}
              className={`card ${over === slot.slot ? 'border-brand-500' : ''}`}
            >
              <p className="text-sm font-medium text-fg">
                <span className="font-mono text-[13px]">{slot.slot}</span>
                <span className="ml-1.5 text-xs font-normal text-fg-subtle">· {ROLE_LABEL[slot.role]}</span>
              </p>
              {slot.hint && <p className="mt-0.5 text-xs text-fg-subtle">{slot.hint}</p>}

              <div className="mt-2 flex flex-wrap items-center gap-2">
                {/* The input itself takes the focus and the keys; the label is what is seen and clicked. */}
                <label htmlFor={`${id}-files`} className="btn btn-secondary btn-sm cursor-pointer focus-within:ring-2 focus-within:ring-brand-500">
                  Choose images
                  <input
                    id={`${id}-files`}
                    type="file"
                    multiple
                    accept={INPUT_ACCEPT}
                    className="sr-only"
                    aria-label={`Images for ${slot.slot}`}
                    onClick={(e) => { if (!mayAdd()) e.preventDefault(); }}
                    onChange={(e) => {
                      const list = e.target.files;
                      add(slot.slot, list);
                      e.target.value = '';   // so the same files can be chosen again
                    }}
                  />
                </label>
                <span className="text-xs text-fg-faint">or drop them here</span>
                {mine.length > 0 && (
                  <>
                    <span className="text-xs text-fg-muted">{plural(mine.length, 'image')} chosen</span>
                    <button type="button" className="btn btn-ghost btn-sm" onClick={() => onClear(slot.slot)}
                            aria-label={`Remove the images for ${slot.slot}`}>
                      Remove them
                    </button>
                  </>
                )}
              </div>
              {refused[slot.slot] && <p role="alert" className="mt-1.5 text-xs text-danger">{refused[slot.slot]}</p>}

              {hasTable && (
                <div className="mt-3">
                  <label htmlFor={`${id}-plan`} className="block text-xs font-medium text-fg">
                    Match images for <span className="font-mono">{slot.slot}</span>
                  </label>
                  <select
                    id={`${id}-plan`}
                    value={plan.by === 'name' ? String(plan.column) : 'order'}
                    onChange={(e) => onPlan(slot.slot, e.target.value === 'order'
                      ? { by: 'order' }
                      : { by: 'name', column: Number(e.target.value) })}
                    className="input mt-1"
                  >
                    <option value="order">In name order: the first image to row 1</option>
                    {header.map((name, at) => (
                      // eslint-disable-next-line react/no-array-index-key -- a column is its place in the header
                      <option key={at} value={at}>By the file names in “{name}”</option>
                    ))}
                  </select>
                </div>
              )}
              <p className="mt-1 text-xs text-fg-subtle">
                {plan.by === 'name' && hasTable
                  ? 'A row gets the image its cell names. Folders and upper or lower case do not matter, and one image can serve many rows.'
                  : 'Images are taken in name order, so “photo 2” comes before “photo 10”.'}
              </p>

              {mine.length > 0 && rowCount > 0 && (
                <div className="mt-2 space-y-1 text-xs">
                  {missing.length === 0 ? (
                    <p className="text-success">Every row has an image.</p>
                  ) : (
                    <p className="text-warning" data-missing-images>
                      {plural(missing.length, 'row has', 'rows have')} no image for “{slot.slot}”: {rowList(missing)}.
                    </p>
                  )}
                  {unused.length > 0 && (
                    <p className="text-fg-subtle" data-unused-images>
                      {plural(unused.length, 'image matches', 'images match')} no row and {unused.length === 1 ? 'is' : 'are'} not
                      uploaded: {names(unused)}.
                    </p>
                  )}
                </div>
              )}
              {mine.length === 0 && rowCount > 0 && (
                <p className="mt-2 text-xs text-warning" data-missing-images>
                  {plural(rowCount, 'row needs', 'rows need')} an image for “{slot.slot}”.
                </p>
              )}
            </div>
          );
        })}
      </div>

      {held && <p className="mt-3 text-sm text-warning">{held}</p>}

      {wanted > 0 && (
        <div className="mt-3">
          <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1 text-xs">
            <p className="font-medium text-fg">
              {done === wanted
                ? `All ${plural(wanted, 'image')} uploaded`
                : `Uploaded ${done.toLocaleString('en-US')} of ${plural(wanted, 'image')}`}
            </p>
            {uploading && <p className="text-fg-subtle">Uploading…</p>}
          </div>
          <div
            role="progressbar"
            aria-label="Images uploaded"
            aria-valuemin={0}
            aria-valuemax={wanted}
            aria-valuenow={done}
            aria-valuetext={`${done} of ${wanted} images uploaded`}
            className="mt-1.5 h-2 overflow-hidden rounded-full bg-bg-muted"
          >
            <div className="h-full rounded-full bg-brand-600 transition-[width]" style={{ width: `${(done / wanted) * 100}%` }} />
          </div>
          <p role="status" aria-live="polite" className="sr-only">{spoken}</p>

          {failed > 0 && !uploading && (
            <div role="alert" className="mt-2 text-sm text-danger">
              <p>{plural(failed, 'image')} could not be uploaded.</p>
              <ul className="mt-1 space-y-0.5">
                {[...failures].map(([error, files]) => (
                  <li key={error}><span className="font-medium">{names(files)}</span>: {error}</li>
                ))}
              </ul>
              <button type="button" className="btn btn-secondary btn-sm mt-2" onClick={uploader.retry}>
                Try the upload again
              </button>
            </div>
          )}
        </div>
      )}
    </section>
  );
}
