import { useEffect, useState } from 'react';
import { rowNumber } from '../../lib/batch-table';

/** One row as it will run: its prompt, and the image each slot gets. */
export interface PreviewRow {
  index: number;
  /** The rendered prompt; null when the template cannot be rendered. */
  prompt: string | null;
  images: { slot: string; file: File | null }[];
}

/** A chosen file as a small picture. The link made to show it is released when the file changes or the picture goes. */
export function FileThumb({ file, alt, className = '' }: { file: File; alt: string; className?: string }) {
  const [url, setUrl] = useState<string | null>(null);
  useEffect(() => {
    const made = URL.createObjectURL(file);
    setUrl(made);
    return () => URL.revokeObjectURL(made);
  }, [file]);
  return url
    ? <img src={url} alt={alt} className={`bg-bg-muted object-cover ${className}`} />
    : <span className={`block bg-bg-muted ${className}`} />;
}

/**
 * The first rows as they will run: each one's prompt, rendered from the
 * template with the row's values, and the image each input slot gets.
 */
export default function BatchPreview({ rows, total }: { rows: PreviewRow[]; total: number }) {
  return (
    <section aria-labelledby="batch-preview">
      <h2 id="batch-preview" className="text-sm font-semibold text-fg">Preview</h2>
      <p className="mt-1 text-sm text-fg-muted">
        {total > rows.length
          ? `The first ${rows.length} of ${total.toLocaleString('en-US')} rows, as they will run.`
          : 'The rows as they will run.'}
      </p>
      <ol aria-label="Preview of the first rows" className="mt-3 space-y-3">
        {rows.map((row) => (
          <li key={row.index} className="embedded-surface p-4">
            <p className="text-xs font-semibold text-fg-subtle">Row {rowNumber(row.index)}</p>
            {row.prompt === null ? (
              <p className="mt-1 text-sm text-danger">This recipe's template can't be rendered. Open it in the Studio to fix it.</p>
            ) : (
              <p className="mt-1 whitespace-pre-wrap break-words text-sm text-fg">{row.prompt}</p>
            )}
            {row.images.length > 0 && (
              <div className="mt-3 flex flex-wrap gap-3">
                {row.images.map((image) => (
                  <figure key={image.slot} className="w-20">
                    {image.file ? (
                      <FileThumb file={image.file} alt={`${image.slot}: ${image.file.name}`}
                                 className="aspect-square w-full rounded-md" />
                    ) : (
                      <div className="flex aspect-square w-full items-center justify-center rounded-md border border-dashed border-border-strong p-1 text-center text-[10px] text-warning">
                        No image
                      </div>
                    )}
                    <figcaption className="mt-1 truncate text-[11px] leading-tight text-fg-subtle"
                                title={image.file ? image.file.name : undefined}>
                      {image.slot}
                    </figcaption>
                  </figure>
                ))}
              </div>
            )}
          </li>
        ))}
      </ol>
    </section>
  );
}
