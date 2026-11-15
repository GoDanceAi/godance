import { useId } from 'react';
import type { RecipeVariable } from '../../lib/api';
import type { Mapping } from '../../lib/batch-table';

function shortened(text: string, max = 48): string {
  return text.length > max ? `${text.slice(0, max - 1)}…` : text;
}

/**
 * Which column each variable reads. A variable takes the column with its
 * name by itself; any other column can be chosen for it, or none, and then
 * every row uses its default, which is said beside it. Columns that no
 * variable and no image slot uses are listed as left out.
 */
export default function ColumnMapping({ header, variables, mapping, unused, onChoose }: {
  header: readonly string[];
  variables: readonly RecipeVariable[];
  mapping: Mapping;
  /** The columns nothing reads, as indexes into the header. */
  unused: readonly number[];
  onChoose: (variable: string, column: number | null) => void;
}) {
  const baseId = useId();

  return (
    <section aria-labelledby="batch-columns">
      <h2 id="batch-columns" className="text-sm font-semibold text-fg">Columns</h2>
      {variables.length === 0 ? (
        <p className="mt-1 text-sm text-fg-muted">
          This recipe has no variables, so every row runs the same prompt.
        </p>
      ) : (
        <>
          <p className="mt-1 text-sm text-fg-muted">
            Each variable reads the column that has its name. Choose another column for it, or none to use its default.
          </p>
          <div className="mt-3 card grid gap-x-6 gap-y-4 sm:grid-cols-2">
            {variables.map((variable, i) => {
              const column = mapping[variable.name] ?? null;
              const fallback = variable.default === '' ? 'empty' : `“${shortened(variable.default)}”`;
              return (
                <div key={variable.name} className="min-w-0">
                  <label htmlFor={`${baseId}-${i}`} className="block text-xs font-medium text-fg">
                    Column for <span className="font-mono">{variable.name}</span>
                  </label>
                  <select
                    id={`${baseId}-${i}`}
                    value={column === null ? '' : String(column)}
                    onChange={(e) => onChoose(variable.name, e.target.value === '' ? null : Number(e.target.value))}
                    className="input mt-1"
                  >
                    <option value="">No column: use the default</option>
                    {header.map((name, at) => (
                      // eslint-disable-next-line react/no-array-index-key -- a column is its place in the header
                      <option key={at} value={at}>{name}</option>
                    ))}
                  </select>
                  <p className="mt-1 break-words text-xs text-fg-subtle">
                    {column === null
                      ? <>No column: every row uses the default, {fallback}.</>
                      : <>An empty cell uses the default, {fallback}.</>}
                  </p>
                </div>
              );
            })}
          </div>
        </>
      )}
      {unused.length > 0 && (
        <p className="mt-2 text-xs text-fg-subtle">
          Not used: {unused.map((at, i) => (
            <span key={at}>
              {i > 0 && ', '}
              <span className="font-mono text-fg-muted">{header[at]}</span>
            </span>
          ))}
          . {unused.length === 1 ? 'This column is' : 'These columns are'} left out unless a variable above reads {unused.length === 1 ? 'it' : 'them'}.
        </p>
      )}
    </section>
  );
}
