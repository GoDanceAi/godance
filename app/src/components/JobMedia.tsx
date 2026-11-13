import type { JobView } from '../lib/api';
import { isTerminal, outputItems } from '../lib/jobs';

function truncate(s: string, max: number): string {
  return s.length > max ? `${s.slice(0, max - 1)}…` : s;
}

/** Every file a job produced, each with a Download link. */
export function JobOutputs({ job, className = '' }: { job: JobView; className?: string }) {
  if (job.purged) {
    return (
      <p className={`text-sm text-fg-muted ${className}`}>
        The files for this generation were deleted at the end of the retention period.
      </p>
    );
  }
  const items = outputItems(job);
  if (items.length === 0) {
    return <p className={`text-sm text-fg-muted ${className}`}>No files came back for this generation.</p>;
  }

  return (
    <ul className={`grid gap-4 ${items.length > 1 ? 'sm:grid-cols-2' : ''} ${className}`}>
      {items.map((item, i) => {
        const kind = item.kind === 'video' ? 'video' : 'image';
        const which = items.length > 1 ? `${kind} ${i + 1} of ${items.length}` : kind;
        const name = `Generated ${which}${job.prompt ? `: ${truncate(job.prompt, 120)}` : ''}`;
        return (
          <li key={item.key}>
            <figure>
              {item.kind === 'video' ? (
                <video
                  src={item.url}
                  controls
                  playsInline
                  preload="metadata"
                  aria-label={name}
                  className="w-full rounded-md bg-black"
                />
              ) : (
                <img src={item.url} alt={name} className="w-full rounded-md bg-bg-muted" />
              )}
              <figcaption className="mt-2 flex items-start justify-between gap-3">
                <span className="text-xs text-fg-subtle">
                  {item.revisedPrompt && <>Revised prompt: {item.revisedPrompt}</>}
                </span>
                {/* A signed link on the storage host: cross-origin, so the
                    browser may open it in a tab rather than save it. */}
                <a
                  href={item.url}
                  download
                  target="_blank"
                  rel="noopener noreferrer"
                  aria-label={`Download ${which}`}
                  className="btn btn-secondary btn-sm shrink-0"
                >
                  Download
                </a>
              </figcaption>
            </figure>
          </li>
        );
      })}
    </ul>
  );
}

function thumbText(job: JobView): string {
  if (job.purged) return 'Files deleted';
  if (!isTerminal(job.status)) return 'In progress…';
  if (job.status === 'FAILED') return 'Failed';
  if (job.status === 'CANCELLED') return 'Cancelled';
  return 'No preview';
}

/**
 * A job's first output as a square thumbnail. Images load lazily; a video
 * loads only its metadata, enough to show a frame.
 */
export function JobThumb({ job }: { job: JobView }) {
  const first = outputItems(job)[0];
  return (
    <div className="aspect-square overflow-hidden bg-bg-muted">
      {first?.kind === 'video' ? (
        <video
          src={`${first.url}#t=0.1`}
          preload="metadata"
          muted
          playsInline
          aria-hidden="true"
          tabIndex={-1}
          className="h-full w-full object-cover"
        />
      ) : first ? (
        <img src={first.url} alt="" loading="lazy" decoding="async" className="h-full w-full object-cover" />
      ) : (
        <div className="flex h-full items-center justify-center p-3 text-center text-xs text-fg-faint">
          {thumbText(job)}
        </div>
      )}
    </div>
  );
}
