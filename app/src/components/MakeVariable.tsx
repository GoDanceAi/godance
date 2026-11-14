import { useState } from 'react';
import type { RefObject } from 'react';
import { NAME_MAX_LENGTH } from '../lib/template';

const EXCERPT = 40;

function excerpt(text: string): string {
  const oneLine = text.replace(/\s+/g, ' ').trim();
  return oneLine.length > EXCERPT ? `${oneLine.slice(0, EXCERPT - 1)}…` : oneLine;
}

/**
 * Turns the text selected in a template's text box into a variable. The
 * button reads the selection when it is pressed, then asks for the
 * variable's name; `onMake` makes it, or answers why it can't.
 *
 * `field` is the text box the template is edited in.
 */
export default function MakeVariable({ field, onMake, disabled = false }: {
  field: RefObject<HTMLTextAreaElement | null>;
  onMake: (start: number, end: number, name: string) => string | null;   // null once made; else the reason it was refused
  disabled?: boolean;
}) {
  const [picked, setPicked] = useState<{ start: number; end: number; text: string } | null>(null);
  const [name, setName] = useState('');
  const [problem, setProblem] = useState<string | null>(null);

  const pick = () => {
    const box = field.current;
    const start = box?.selectionStart ?? 0;
    const end = box?.selectionEnd ?? 0;
    if (!box || start >= end) {
      setPicked(null);
      setProblem('Select some of the text above first, then choose this again.');
      return;
    }
    setProblem(null);
    setName('');
    setPicked({ start, end, text: box.value.slice(start, end) });
  };

  const make = () => {
    if (!picked) return;
    const refused = onMake(picked.start, picked.end, name);
    if (refused !== null) {
      setProblem(refused);
      return;
    }
    setPicked(null);
    setProblem(null);
  };

  const cancel = () => {
    setPicked(null);
    setProblem(null);
  };

  return (
    <div className="mt-1.5">
      {!picked && (
        <button type="button" onClick={pick} disabled={disabled} className="btn btn-ghost btn-sm px-0"
                title="Select text above, then turn it into a blank you can fill in differently on each run">
          Make the selection a variable
        </button>
      )}
      {picked && (
        <div role="group" aria-label="New variable" className="rounded-md border border-border bg-surface-alt p-3">
          <p className="text-xs text-fg-muted">
            <span className="break-words font-medium text-fg">“{excerpt(picked.text)}”</span> becomes a variable,
            and stays as its value.
          </p>
          <div className="mt-2 flex flex-wrap items-end gap-2">
            <div className="min-w-0 flex-1">
              <label htmlFor="studio-variable-name" className="block text-xs font-medium text-fg">Variable name</label>
              <input
                id="studio-variable-name"
                type="text"
                value={name}
                onChange={(e) => setName(e.target.value)}
                // Enter adds the variable; it must not submit the form around it, which would generate.
                onKeyDown={(e) => {
                  if (e.key === 'Enter') {
                    e.preventDefault();
                    make();
                  }
                }}
                maxLength={NAME_MAX_LENGTH}
                placeholder="color"
                autoComplete="off"
                autoFocus
                className="input mt-1"
              />
            </div>
            <button type="button" onClick={make} className="btn btn-secondary btn-sm">Add variable</button>
            <button type="button" onClick={cancel} className="btn btn-ghost btn-sm">Cancel</button>
          </div>
        </div>
      )}
      {problem && <p role="alert" className="mt-1.5 text-xs text-danger">{problem}</p>}
    </div>
  );
}
