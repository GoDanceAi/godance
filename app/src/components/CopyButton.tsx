import { useEffect, useState } from 'react';
import { Icon } from './Brand';

/** Copies `text`, and says so for a moment. */
export default function CopyButton({ text, label = 'Copy', className = 'btn btn-secondary btn-sm' }: {
  text: string;
  label?: string;
  className?: string;
}) {
  const [state, setState] = useState<'idle' | 'copied' | 'failed'>('idle');

  useEffect(() => {
    if (state === 'idle') return;
    const timer = setTimeout(() => setState('idle'), 2000);
    return () => clearTimeout(timer);
  }, [state]);

  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
      setState('copied');
    } catch {
      setState('failed');   // no clipboard access, e.g. outside a secure context
    }
  };

  return (
    <button type="button" onClick={() => void copy()} className={className}>
      <Icon name="copy" size={14} />
      {state === 'copied' ? 'Copied' : state === 'failed' ? 'Copy failed' : label}
    </button>
  );
}
