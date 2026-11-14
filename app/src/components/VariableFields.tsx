import { VALUE_MAX_LENGTH, type FormTemplate } from '../lib/template';

/** A value this long, or one with a line break, gets a box that shows more of it. */
const ONE_LINE_MAX = 60;

/**
 * One field per variable of the form's template, each holding the value the
 * prompt is filled in with. The fields are named by the variables, in the
 * order the template first uses them.
 */
export default function VariableFields({ template, onValue, disabled = false, lang }: {
  template: FormTemplate;
  onValue: (name: string, value: string) => void;
  disabled?: boolean;
  lang?: string;   // the language of the values, when it is not the page's
}) {
  if (template.variables.length === 0) return null;
  return (
    <fieldset className="rounded-md border border-border p-3">
      <legend className="px-1 text-sm font-medium text-fg">Variables</legend>
      <div className="space-y-3">
        {template.variables.map((variable, i) => {
          const id = `studio-variable-${i}`;
          const value = template.values[variable.name] ?? '';
          const long = variable.default.length > ONE_LINE_MAX || variable.default.includes('\n') || value.includes('\n');
          return (
            <div key={variable.name}>
              <label htmlFor={id} className="block font-mono text-xs text-fg-muted">{variable.name}</label>
              {long ? (
                <textarea
                  id={id}
                  lang={lang}
                  value={value}
                  onChange={(e) => onValue(variable.name, e.target.value)}
                  maxLength={VALUE_MAX_LENGTH}
                  rows={3}
                  disabled={disabled}
                  className="input mt-1 resize-y"
                />
              ) : (
                <input
                  id={id}
                  type="text"
                  lang={lang}
                  value={value}
                  onChange={(e) => onValue(variable.name, e.target.value)}
                  maxLength={VALUE_MAX_LENGTH}
                  disabled={disabled}
                  autoComplete="off"
                  className="input mt-1"
                />
              )}
            </div>
          );
        })}
      </div>
    </fieldset>
  );
}
