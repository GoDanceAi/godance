import { useState } from 'react';
import { Link } from 'react-router-dom';
import { ApiError, type JobView } from '../lib/api';
import { canSave, pastRetention, RETENTION_DAYS, SAVED_VIEW, saveJob, unsaveJob, withSaved } from '../lib/jobs';
import { Icon } from './Brand';

const AT_THE_LIMIT = "You've saved as many generations as an account can keep. Un-save one to save another.";

type Problem =
  | { kind: 'limit'; detail: string }   // the account has saved as many as it can
  | { kind: 'gone' }                    // the job can no longer be saved
  | { kind: 'failed'; saving: boolean };

/**
 * Saves a generation, so it is kept past its deletion date, and un-saves it.
 * Shows which it is and toggles it; `onChange` hears the job as it now stands.
 * Nothing is rendered for a job that cannot be saved: one that failed, is
 * still running, or whose files are already deleted.
 *
 * Renders a button and, when a save is refused, a message that takes a row
 * of its own: place it in a wrapping flex row.
 */
export default function SaveJobButton({ job, onChange, className = 'btn btn-secondary btn-sm' }: {
  job: JobView;
  onChange: (job: JobView) => void;
  className?: string;
}) {
  const [busy, setBusy] = useState(false);
  const [problem, setProblem] = useState<Problem | null>(null);

  if (!canSave(job)) return null;
  const saved = job.saved === true;

  const toggle = async () => {
    // Past the retention period only saving keeps the files: say what un-saving does.
    if (saved && pastRetention(job) && !window.confirm(
      `This generation is more than ${RETENTION_DAYS} days old. If you un-save it, its files and prompt `
        + 'will be deleted at the next nightly clean-up. Un-save it?')) {
      return;
    }
    setBusy(true);
    setProblem(null);
    try {
      if (saved) await unsaveJob(job.id);
      else await saveJob(job.id);
      onChange(withSaved(job, !saved));
    } catch (err) {
      const code = err instanceof ApiError ? err.code : undefined;
      // The API's own sentence names the limit; without one, ours.
      const said = err instanceof ApiError && !err.message.startsWith('HTTP ') ? err.message : AT_THE_LIMIT;
      setProblem(
        code === 'saved_limit_reached' ? { kind: 'limit', detail: said }
          : code === 'job_not_saveable' ? { kind: 'gone' }
          : { kind: 'failed', saving: !saved },
      );
    } finally {
      setBusy(false);
    }
  };

  return (
    <>
      <button
        type="button"
        aria-pressed={saved}
        title={saved ? 'Kept until you un-save it. Select to un-save.' : 'Keep this past its deletion date'}
        onClick={() => void toggle()}
        disabled={busy}
        className={className}
      >
        <Icon name={saved ? 'bookmarkFilled' : 'bookmark'} size={14} />
        {saved ? 'Saved' : 'Save'}
      </button>
      {problem && (
        <p role="alert" className="relative z-10 basis-full text-xs text-danger">
          {problem.kind === 'limit' && (
            <>
              {problem.detail}{' '}
              <Link to={SAVED_VIEW} className="font-semibold underline">See your saved generations</Link>
            </>
          )}
          {problem.kind === 'gone' && "This generation can't be saved: its files are no longer kept."}
          {problem.kind === 'failed' && `Couldn't ${problem.saving ? 'save' : 'un-save'} this. Try again.`}
        </p>
      )}
    </>
  );
}
