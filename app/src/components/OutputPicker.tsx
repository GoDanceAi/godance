import { useEffect, useState } from 'react';
import type { JobView } from '../lib/api';
import type { FormInput } from '../lib/inputs';
import { listJobs, outputItems } from '../lib/jobs';
import Dialog from './Dialog';

/** How many recent image generations the picker offers. */
const RECENT = 12;

/**
 * Picks one of the user's own recent images as an input: an output of an
 * image job, named to the API by its job and index, with no upload.
 */
export default function OutputPicker({ title, onPick, onClose }: {
  title: string;
  onPick: (input: FormInput) => void;
  onClose: () => void;
}) {
  const [jobs, setJobs] = useState<JobView[] | null>(null);
  const [failed, setFailed] = useState(false);

  useEffect(() => {
    const controller = new AbortController();
    listJobs({ limit: RECENT, modality: 'IMAGE' }, controller.signal)
      .then(setJobs)
      .catch((err) => {
        if (!controller.signal.aborted) {
          console.warn('Could not list recent images', err);
          setFailed(true);
        }
      });
    return () => controller.abort();
  }, []);

  const images = (jobs ?? [])
    .filter((job) => job.status === 'DONE')
    .flatMap((job) => outputItems(job)
      .filter((item) => item.kind === 'image')
      .map((item) => ({ job, item })));

  return (
    <Dialog title={title} onClose={onClose}>
      {failed && <p role="alert" className="text-sm text-danger">Couldn't load your images. Close this and try again.</p>}
      {!failed && jobs === null && <p className="text-sm text-fg-muted">Loading your images…</p>}
      {!failed && jobs !== null && images.length === 0 && (
        <p className="text-sm text-fg-muted">You have no finished images yet. Make one in the image Studio, or choose a file.</p>
      )}
      {images.length > 0 && (
        <ul aria-label="Your recent images" className="grid grid-cols-3 gap-3">
          {images.map(({ job, item }) => {
            const index = Number(item.key);
            const what = job.prompt ? job.prompt.slice(0, 80) : `image made with ${job.model}`;
            return (
              <li key={`${job.id}-${item.key}`}>
                <button
                  type="button"
                  onClick={() => onPick({
                    ref: { job_output: { job_id: job.id, index } },
                    preview: item.url,
                    label: 'One of your images',
                  })}
                  aria-label={`Use ${what}`}
                  className="block w-full overflow-hidden rounded-md border border-border hover:border-brand-500 focus-visible:border-brand-500"
                >
                  <img src={item.url} alt="" className="aspect-square w-full bg-bg-muted object-cover" />
                </button>
              </li>
            );
          })}
        </ul>
      )}
    </Dialog>
  );
}
