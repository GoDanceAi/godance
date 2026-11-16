import CopyButton from './CopyButton';

/**
 * A block of code with its title, a Copy button, and a line to read with
 * it. `prose` is for text that is not code (the prompt for an AI assistant):
 * its lines wrap, and a long one scrolls inside the block.
 */
export default function CodeFigure({ title, code, note, prose = false }: {
  title: string;
  code: string;
  note?: string;
  prose?: boolean;
}) {
  return (
    <figure aria-label={title}>
      <div className="flex items-center justify-between gap-2">
        <figcaption className="text-xs font-semibold text-fg-subtle">{title}</figcaption>
        <CopyButton text={code} className="btn btn-ghost btn-sm" />
      </div>
      {prose ? (
        <pre tabIndex={0} className="embedded-surface mt-1.5 max-h-[26rem] overflow-y-auto whitespace-pre-wrap break-words p-4 font-mono text-[12px] leading-relaxed text-fg">
          {code}
        </pre>
      ) : (
        <pre className="code-block mt-1.5"><code>{code}</code></pre>
      )}
      {note && <p className="mt-1.5 text-xs text-fg-faint">{note}</p>}
    </figure>
  );
}
