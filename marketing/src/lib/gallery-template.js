// @ts-check
/**
 * Recipe templates: the one place their rules are written down for the
 * marketing site. The schema's checks, the import tool and the gallery
 * pages all render through here. The backend implements the same rules
 * (round 10 brief 03), so each rule below is stated the way the round-10
 * coordination notes state it, in the same order.
 *
 * Plain ES module with no imports, so `node` and Astro both load it.
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

/** Rule 2, as a pattern for a whole name. */
export const VARIABLE_NAME = /^[A-Za-z0-9](?:[A-Za-z0-9 _-]{0,38}[A-Za-z0-9])?$/;

/** Rules 1 and 2 at one position: a name and its closing braces, right after a "{{". */
const NAME_THEN_CLOSE = /([A-Za-z0-9](?:[A-Za-z0-9 _-]{0,38}[A-Za-z0-9])?)\}\}/y;

/** Rule 2. @param {unknown} name */
export function isVariableName(name) {
  return typeof name === 'string' && VARIABLE_NAME.test(name);
}

/**
 * A template cut into the text between its markers and the markers
 * themselves, read left to right. `strays` are the positions of every "{{"
 * that does not begin a marker (rule 5); such a "{{" stays in the text.
 *
 * A "}}" on its own is ordinary text. So is everything else: nothing is
 * escaped and nothing nests.
 *
 * @param {string} template
 * @returns {{ parts: Array<{ text: string } | { name: string }>, strays: number[] }}
 */
export function parseTemplate(template) {
  /** @type {Array<{ text: string } | { name: string }>} */
  const parts = [];
  /** @type {number[]} */
  const strays = [];
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

/** The names a template's markers use, in order, repeats included. @param {string} template */
export function markersOf(template) {
  return parseTemplate(template).parts.flatMap((part) => ('name' in part ? [part.name] : []));
}

/**
 * One way a template breaks the rules.
 * @typedef {object} TemplateProblem
 * @property {'stray-open' | 'undeclared' | 'unused'} kind
 * @property {string} message
 * @property {string} [name] The variable, for `undeclared` and `unused`.
 * @property {number} [index] Where in the template, for `stray-open`.
 */

/**
 * Check a template against the variables declared for it: rules 5 and 6.
 * Returns every problem; an empty list means it can be rendered.
 *
 * @param {string} template
 * @param {ReadonlyArray<string>} declared The declared variable names.
 * @returns {TemplateProblem[]}
 */
export function checkTemplate(template, declared) {
  /** @type {TemplateProblem[]} */
  const problems = [];
  const { parts, strays } = parseTemplate(template);
  for (const index of strays) {
    problems.push({
      kind: 'stray-open',
      index,
      message: `the "{{" at position ${index} does not begin a variable: write {{name}}, with a name of 1–${NAME_MAX_LENGTH} ASCII letters, digits, spaces, hyphens or underscores that starts and ends with a letter or digit`,
    });
  }
  const used = new Set();
  for (const part of parts) {
    if (!('name' in part)) continue;
    if (!declared.includes(part.name) && !used.has(part.name)) {
      problems.push({ kind: 'undeclared', name: part.name, message: `{{${part.name}}} is not a declared variable` });
    }
    used.add(part.name);
  }
  for (const name of declared) {
    if (!used.has(name)) problems.push({ kind: 'unused', name, message: `the variable "${name}" is declared but the template never uses it` });
  }
  return problems;
}

/** Raised by {@link renderTemplate} for a template that breaks the rules. */
export class TemplateError extends Error {
  /** @param {TemplateProblem[]} problems */
  constructor(problems) {
    super(problems.map((problem) => problem.message).join('; '));
    this.name = 'TemplateError';
    this.problems = problems;
  }
}

/**
 * Render a template: rules 3 and 4.
 *
 * `values` holds a value for some, all or none of the variables. A variable
 * with no value gets its default. An empty string is a value and is used.
 * What is inserted is never read again, so a value that contains "{{…}}"
 * stays exactly as it is.
 *
 * @param {string} template
 * @param {ReadonlyArray<{ name: string, default: string }>} variables The declared variables, with their defaults.
 * @param {Record<string, string>} [values]
 * @returns {string}
 * @throws {TemplateError} When the template breaks rule 5 or 6.
 */
export function renderTemplate(template, variables, values = {}) {
  const problems = checkTemplate(template, variables.map((variable) => variable.name));
  if (problems.length > 0) throw new TemplateError(problems);
  const defaults = new Map(variables.map((variable) => [variable.name, variable.default]));
  let out = '';
  for (const part of parseTemplate(template).parts) {
    if ('text' in part) out += part.text;
    else out += Object.hasOwn(values, part.name) ? values[part.name] : defaults.get(part.name);
  }
  return out;
}

/**
 * A recipe's variables with the default each has in one language: Chinese
 * uses `zh_default` where a variable has one, and `default` otherwise.
 *
 * @param {ReadonlyArray<{ name: string, default: string, zh_default?: string }>} variables
 * @param {'en' | 'zh'} lang
 * @returns {{ name: string, default: string }[]}
 */
export function defaultsIn(variables, lang) {
  return variables.map((variable) => ({
    name: variable.name,
    default: lang === 'zh' && variable.zh_default !== undefined ? variable.zh_default : variable.default,
  }));
}
