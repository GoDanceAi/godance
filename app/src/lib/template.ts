/**
 * Recipe templates: the rules, as the round 10 coordination notes fix them.
 * The API renders by the same rules (backend `RecipeTemplate`) and so does
 * the gallery (marketing `gallery-template.js`); the three are kept line for
 * line. Nothing here touches the network, storage or the page, so the tests
 * run it directly.
 *
 * Syntax
 *   1. A variable is written {{name}}.
 *   2. A name is 1–40 ASCII letters, digits, spaces, hyphens or underscores,
 *      starting and ending with a letter or digit.
 *
 * Rendering
 *   3. Each {{name}} is replaced by the value given, or by the variable's
 *      default when no value is given for it. An empty string is a value.
 *   4. It is one pass: a value that contains {{…}} is inserted as it is.
 *   5. Every {{ in a template must begin a declared variable. A stray }} is
 *      allowed, so JSON-shaped prompts work.
 *   6. Every declared variable appears in the template at least once.
 */

/** The longest a variable name can be. */
export const NAME_MAX_LENGTH = 40;
/** The API's limits on a recipe (backend `RecipeService`). */
export const TEMPLATE_MAX_LENGTH = 32_000;
export const MAX_VARIABLES = 20;
export const VALUE_MAX_LENGTH = 2_000;

/** A declared variable: its name, and the value it has when none is given. */
export interface TemplateVariable {
  name: string;
  default: string;
}

export type TemplatePart = { text: string } | { name: string };

export interface TemplateProblem {
  kind: 'stray-open' | 'undeclared' | 'unused';
  message: string;
  name?: string;    // the variable, for `undeclared` and `unused`
  index?: number;   // where in the template, for `stray-open`
}

/** Rule 2, as a pattern for a whole name. */
const VARIABLE_NAME = /^[A-Za-z0-9](?:[A-Za-z0-9 _-]{0,38}[A-Za-z0-9])?$/;
/** Rules 1 and 2 at one position: a name and its closing braces, right after a "{{". */
const NAME_THEN_CLOSE = /([A-Za-z0-9](?:[A-Za-z0-9 _-]{0,38}[A-Za-z0-9])?)\}\}/y;

/** Rule 2. */
export function isVariableName(name: unknown): name is string {
  return typeof name === 'string' && VARIABLE_NAME.test(name);
}

/**
 * A template cut into the text between its markers and the markers
 * themselves, read left to right. `strays` are the positions of every "{{"
 * that does not begin a marker (rule 5); such a "{{" stays in the text. A
 * "}}" on its own is ordinary text: nothing is escaped and nothing nests.
 */
export function parseTemplate(template: string): { parts: TemplatePart[]; strays: number[] } {
  const parts: TemplatePart[] = [];
  const strays: number[] = [];
  let text = '';
  let at = 0;
  while (at < template.length) {
    const open = template.indexOf('{{', at);
    if (open === -1) break;
    text += template.slice(at, open);
    NAME_THEN_CLOSE.lastIndex = open + 2;
    const marker = NAME_THEN_CLOSE.exec(template);
    if (marker) {
      if (text !== '') parts.push({ text });
      text = '';
      parts.push({ name: marker[1] });
      at = NAME_THEN_CLOSE.lastIndex;
    } else {
      // Not a marker. Move past one brace only, so "{{{name}}" reports the
      // first "{{" and still finds the marker that starts at its second brace.
      strays.push(open);
      text += '{';
      at = open + 1;
    }
  }
  text += template.slice(at);
  if (text !== '') parts.push({ text });
  return { parts, strays };
}

/** The names a template's markers use, in order, repeats included. */
export function markersOf(template: string): string[] {
  return parseTemplate(template).parts.flatMap((part) => ('name' in part ? [part.name] : []));
}

/**
 * A template checked against the variables declared for it: rules 5 and 6.
 * Every problem is returned; an empty list means it can be rendered.
 */
export function checkTemplate(template: string, declared: readonly string[]): TemplateProblem[] {
  const problems: TemplateProblem[] = [];
  const { parts, strays } = parseTemplate(template);
  for (const index of strays) {
    problems.push({
      kind: 'stray-open',
      index,
      message: `The “{{” at position ${index + 1} doesn't begin a variable. Write {{name}}: a name is `
        + `1–${NAME_MAX_LENGTH} letters, digits, spaces, hyphens or underscores, starting and ending with a letter or digit.`,
    });
  }
  const used = new Set<string>();
  for (const part of parts) {
    if (!('name' in part)) continue;
    if (!declared.includes(part.name) && !used.has(part.name)) {
      problems.push({ kind: 'undeclared', name: part.name, message: `{{${part.name}}} is not a declared variable.` });
    }
    used.add(part.name);
  }
  for (const name of declared) {
    if (!used.has(name)) {
      problems.push({ kind: 'unused', name, message: `The variable “${name}” is declared but the template never uses it.` });
    }
  }
  return problems;
}

/**
 * The template rendered: rules 3 and 4. `values` holds a value for some, all
 * or none of the variables; a variable with no value gets its default, and
 * an empty string is a value. What is inserted is never read again. Null
 * when the template breaks rule 5 or 6 ({@link checkTemplate} says how).
 */
export function renderTemplate(
  template: string,
  variables: readonly TemplateVariable[],
  values: Readonly<Record<string, string>> = {},
): string | null {
  if (checkTemplate(template, variables.map((v) => v.name)).length > 0) return null;
  const defaults = new Map(variables.map((v) => [v.name, v.default]));
  let out = '';
  for (const part of parseTemplate(template).parts) {
    if ('text' in part) out += part.text;
    else out += Object.hasOwn(values, part.name) ? values[part.name] : defaults.get(part.name) ?? '';
  }
  return out;
}

// ===== Editing a template in the Studio =====

/** The template a form holds: its text, its variables, and the value each has in the form. */
export interface FormTemplate {
  text: string;
  variables: TemplateVariable[];
  values: Record<string, string>;
}

/** A template as loaded, each variable at its default. */
export function formTemplate(text: string, variables: readonly TemplateVariable[]): FormTemplate {
  return {
    text,
    variables: variables.map((v) => ({ name: v.name, default: v.default })),
    values: Object.fromEntries(variables.map((v) => [v.name, v.default])),
  };
}

/** The form's prompt: the template with the form's values. Null while the template can't be rendered. */
export function renderForm(template: FormTemplate): string | null {
  return renderTemplate(template.text, template.variables, template.values);
}

/**
 * The template after its text was edited: its variables are the markers the
 * text now has, in the order they first appear. A marker typed in is a new
 * variable with an empty value; a variable whose marker is gone is dropped
 * with its value. So rule 6 always holds, and rule 5 fails only on a "{{"
 * that begins no marker.
 */
export function withText(template: FormTemplate, text: string): FormTemplate {
  const names = [...new Set(markersOf(text))];
  const known = new Map(template.variables.map((v) => [v.name, v]));
  return {
    text,
    variables: names.map((name) => known.get(name) ?? { name, default: '' }),
    values: Object.fromEntries(names.map((name) => [name, template.values[name] ?? known.get(name)?.default ?? ''])),
  };
}

/** The template with one variable's value changed. */
export function withValue(template: FormTemplate, name: string, value: string): FormTemplate {
  return { ...template, values: { ...template.values, [name]: value.slice(0, VALUE_MAX_LENGTH) } };
}

/** The variables as a recipe keeps them: each one's value in the form is its default. */
export function variablesToSave(template: FormTemplate): TemplateVariable[] {
  return template.variables.map((v) => ({ name: v.name, default: template.values[v.name] ?? v.default }));
}

export type MakeVariable =
  | { template: FormTemplate }
  | { refused: string };

/**
 * Turns the text from `start` to `end` into a variable called `name`: the
 * selection becomes the marker, and is the variable's default and its value.
 * `template` is the form's template, or a plain prompt as a template with no
 * variables. Refused, with the reason, when the name or the selection can't
 * be a variable.
 */
export function makeVariable(template: FormTemplate, start: number, end: number, rawName: string): MakeVariable {
  const name = rawName.trim();
  const selected = template.text.slice(start, end);
  if (start >= end || selected === '') return { refused: 'Select the text to turn into a variable first.' };
  if (!isVariableName(name)) {
    return {
      refused: `A name is 1–${NAME_MAX_LENGTH} letters, digits, spaces, hyphens or underscores, `
        + 'starting and ending with a letter or digit.',
    };
  }
  if (template.variables.some((v) => v.name === name)) return { refused: `There is already a variable called “${name}”.` };
  if (template.variables.length >= MAX_VARIABLES) return { refused: `A recipe has at most ${MAX_VARIABLES} variables.` };
  if (selected.length > VALUE_MAX_LENGTH) {
    return { refused: `A variable's value is at most ${VALUE_MAX_LENGTH.toLocaleString('en-US')} characters.` };
  }
  // A selection that cuts through a marker, or holds one, would leave braces behind.
  if (cutsMarker(template.text, start, end)) return { refused: 'The selection overlaps a variable. Select plain text.' };

  const text = `${template.text.slice(0, start)}{{${name}}}${template.text.slice(end)}`;
  const next = withText({
    text: template.text,
    variables: [...template.variables, { name, default: selected }],
    values: { ...template.values, [name]: selected },
  }, text);
  // The new marker must read as written: a "{" just before it would make it a stray.
  if (!next.variables.some((v) => v.name === name) || parseTemplate(text).strays.length > parseTemplate(template.text).strays.length) {
    return { refused: 'A variable can\'t start right after a “{”. Select a little less.' };
  }
  return { template: next };
}

/** Whether [start, end) touches a marker of the template. */
function cutsMarker(text: string, start: number, end: number): boolean {
  let at = 0;
  for (const part of parseTemplate(text).parts) {
    const length = 'text' in part ? part.text.length : part.name.length + 4;
    if ('name' in part && start < at + length && end > at) return true;
    at += length;
  }
  return false;
}
