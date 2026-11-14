import type { JobRecipe } from '../lib/api';

/**
 * The value each variable had in a run of one of the user's recipes. Says so
 * when the values were deleted with the prompt, and when the version had no
 * variables.
 */
export default function VariableValues({ recipe, className = '' }: { recipe: JobRecipe; className?: string }) {
  if (recipe.variables === null) {
    return <p className={`text-xs italic text-fg-subtle ${className}`}>Values deleted with the prompt</p>;
  }
  const entries = Object.entries(recipe.variables);
  if (entries.length === 0) return <p className={`text-xs text-fg-subtle ${className}`}>No variables</p>;
  return (
    <dl className={`grid grid-cols-[auto_minmax(0,1fr)] gap-x-3 gap-y-1 text-xs ${className}`}>
      {entries.map(([name, value]) => (
        <div key={name} className="contents">
          <dt className="font-mono text-fg-subtle">{name}</dt>
          <dd className="whitespace-pre-wrap break-words text-fg">
            {value === '' ? <span className="italic text-fg-subtle">empty</span> : value}
          </dd>
        </div>
      ))}
    </dl>
  );
}
