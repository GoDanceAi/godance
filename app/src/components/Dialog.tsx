import { useEffect, useId, useRef } from 'react';
import type { ReactNode } from 'react';

/**
 * A modal dialog: a titled panel over the page. Escape and the Close button
 * close it; focus moves into it when it opens and back to where it was when
 * it closes.
 */
export default function Dialog({ title, onClose, children }: {
  title: string;
  onClose: () => void;
  children: ReactNode;
}) {
  const titleId = useId();
  const panel = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const before = document.activeElement as HTMLElement | null;
    panel.current?.focus();
    const onKey = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onClose();
    };
    document.addEventListener('keydown', onKey);
    return () => {
      document.removeEventListener('keydown', onKey);
      before?.focus?.();
    };
    // Runs once for the life of the dialog: onClose may be a new function on each render.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  return (
    <div className="fixed inset-0 z-40 flex items-center justify-center p-4" role="dialog" aria-modal="true"
         aria-labelledby={titleId}>
      <div className="absolute inset-0 bg-black/30" onClick={onClose} />
      <div ref={panel} tabIndex={-1}
           className="relative z-50 max-h-[90vh] w-full max-w-lg overflow-y-auto rounded-md border border-border bg-surface p-5 shadow-xl outline-none">
        <div className="flex items-start justify-between gap-4">
          <h2 id={titleId} className="text-base font-semibold text-fg">{title}</h2>
          <button type="button" className="btn btn-sm btn-ghost" onClick={onClose} aria-label="Close">✕</button>
        </div>
        <div className="mt-4">{children}</div>
      </div>
    </div>
  );
}
