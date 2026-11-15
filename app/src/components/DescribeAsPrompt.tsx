import { useEffect, useState } from 'react';
import type { ModelEntry } from '../lib/api';
import { formatCents, useAuth } from '../lib/auth';
import { describeAsPrompt, describeCostCents, describeModel, type DescribeSource } from '../lib/describe';
import { inputErrorMessage } from '../lib/inputs';
import { loadCatalog, modelName } from '../lib/media-models';
import { sendError } from '../lib/send-error';
import Dialog from './Dialog';
import { AcceptInputTerms, InputTermsText } from './InputTerms';

/**
 * The "Describe as prompt" dialog: says which model would look at the image
 * and about what it costs, asks it only when the user says so, and puts the
 * answer in the prompt box only when the user confirms. Nothing is sent by
 * opening it.
 */
export default function DescribeAsPrompt({ source, hasPrompt, onUse, onClose }: {
  source: DescribeSource;
  /** Whether the prompt box holds text the answer would replace. */
  hasPrompt: boolean;
  onUse: (prompt: string) => void;
  onClose: () => void;
}) {
  const { user, refresh } = useAuth();
  const [model, setModel] = useState<ModelEntry | null | undefined>(undefined);
  const [busy, setBusy] = useState(false);
  const [answer, setAnswer] = useState<string | null>(null);
  const [problem, setProblem] = useState<string | null>(null);

  useEffect(() => {
    let cancelled = false;
    loadCatalog().then(
      (catalog) => { if (!cancelled) setModel(describeModel(catalog) ?? null); },
      () => { if (!cancelled) setModel(null); },
    );
    return () => { cancelled = true; };
  }, []);

  // An image that is not one of the user's files yet is stored as one, which the input terms cover.
  const needsTerms = source.fileId === null && !user?.inputs?.accepted;

  const describe = async () => {
    if (!model) return;
    setBusy(true);
    setProblem(null);
    try {
      setAnswer(await describeAsPrompt(source, model.id));
    } catch (err) {
      const sent = sendError(err, '');
      setProblem(sent.outOfCredit
        ? 'There is not enough credit for this. Add credit and try again.'
        : inputErrorMessage(err, 'The image could not be described. Try again.'));
    } finally {
      setBusy(false);
      void refresh();
    }
  };

  return (
    <Dialog title="Describe as prompt" onClose={onClose}>
      <img src={source.url} alt="The image to describe" className="mx-auto max-h-40 rounded-md bg-bg-muted object-contain" />
      {model === undefined && <p className="mt-3 text-sm text-fg-muted">Loading…</p>}
      {model === null && (
        <p role="alert" className="mt-3 text-sm text-danger">No model that can look at an image is available right now.</p>
      )}
      {model && needsTerms && (
        <div className="mt-3">
          <InputTermsText />
          <div className="mt-4"><AcceptInputTerms /></div>
        </div>
      )}
      {model && !needsTerms && answer === null && (
        <>
          <p className="mt-3 text-sm text-fg-muted">
            {modelName(model)} looks at the image and writes a prompt that would reproduce it.
            It is a normal chat call, charged as one: about{' '}
            <span className="font-semibold tabular-nums text-fg" data-describe-cost>{formatCents(describeCostCents(model))}</span>.
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <button type="button" className="btn btn-primary btn-sm" onClick={() => void describe()} disabled={busy}>
              {busy ? 'Describing…' : 'Describe the image'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>Cancel</button>
          </div>
        </>
      )}
      {answer !== null && (
        <>
          <p className="mt-3 text-xs font-semibold text-fg-subtle">The description</p>
          <p className="mt-1 whitespace-pre-wrap rounded-md border border-border bg-surface-alt p-3 text-sm text-fg" data-describe-answer>
            {answer}
          </p>
          <div className="mt-4 flex flex-wrap gap-2">
            <button type="button" className="btn btn-primary btn-sm" onClick={() => onUse(answer)}>
              {hasPrompt ? 'Replace the prompt with this' : 'Use as the prompt'}
            </button>
            <button type="button" className="btn btn-ghost btn-sm" onClick={onClose}>
              {hasPrompt ? 'Keep my prompt' : 'Cancel'}
            </button>
          </div>
        </>
      )}
      {problem && <p role="alert" className="mt-3 text-sm text-danger">{problem}</p>}
    </Dialog>
  );
}
