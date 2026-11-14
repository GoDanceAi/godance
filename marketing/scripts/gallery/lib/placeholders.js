/**
 * Templated prompts. Some lists mark a blank as
 *
 *   {argument name="lantern color" default="warm red"}
 *
 * (with the quotes escaped when the prompt is itself JSON). Each becomes a
 * {{lantern color}} marker in the recipe's prompt.template, with its default
 * in prompt.variables (templatePlaceholders); filled with the defaults, the
 * template is the prompt the run was made with (resolvePlaceholders).
 * Other blanks, like [CITY NAME], carry no default and cannot be resolved.
 */
import { NAME_MAX_LENGTH, checkTemplate, isVariableName } from '../../../src/lib/gallery-template.js';

// The quote is " or \" and must be the same one throughout a placeholder.
const ARGUMENT = /\{argument\s+name=(\\?")(.*?)\1\s+default=\1(.*?)\1\s*\}/g;

/** Unescape a value taken from inside a JSON string. */
const plain = (value, quote) => (quote === '\\"' ? value.replace(/\\(["\\/])/g, '$1') : value);

/**
 * Fill every {argument …} with its default.
 * Returns the text, the variables in the order they first appear, and any
 * blank that is left: a malformed {argument …} or one with an empty default.
 */
export function resolvePlaceholders(text) {
  const variables = [];
  const unresolved = [];
  const resolved = text.replace(ARGUMENT, (whole, quote, name, fallback) => {
    if (fallback.trim() === '') {
      unresolved.push(whole);
      return whole;
    }
    const variable = { name: plain(name, quote).trim(), default: plain(fallback, quote) };
    if (!variables.some((v) => v.name === variable.name && v.default === variable.default)) variables.push(variable);
    return fallback;
  });
  for (const left of resolved.match(/\{argument\b[^}]*\}?/g) ?? []) {
    if (!unresolved.includes(left)) unresolved.push(left);
  }
  return { text: resolved, variables, unresolved };
}

/**
 * A name the template rules accept, made from the one the list gave: other
 * characters become spaces, accents are dropped, and it is cut to length.
 * A name with nothing usable in it, such as one in Chinese, is "variable".
 */
export function fitVariableName(name) {
  if (isVariableName(name)) return name;
  const trim = (text) => text.replace(/^[^A-Za-z0-9]+|[^A-Za-z0-9]+$/g, '');
  const plainLetters = name.normalize('NFKD').replace(/[\u0300-\u036f]/g, '');
  const fitted = trim(trim(plainLetters.replace(/[^A-Za-z0-9 _-]+/g, ' ').replace(/\s+/g, ' ')).slice(0, NAME_MAX_LENGTH));
  return fitted === '' ? 'variable' : fitted;
}

/** `base` with a number after it, within the length a name may have: "hair color 2". */
function numbered(base, n) {
  const suffix = ` ${n}`;
  return `${base.slice(0, NAME_MAX_LENGTH - suffix.length).replace(/[^A-Za-z0-9]+$/, '')}${suffix}`;
}

/**
 * Keep every {argument …} as a {{name}} marker instead of filling it.
 *
 * Returns the template; the variables in the order they first appear, each
 * with the default exactly as the prompt carries it, so that rendering the
 * template with the defaults gives back resolvePlaceholders(text).text; and
 * what had to change on the way, for source.changes:
 *
 *  - a name the template rules refuse is replaced by one they accept;
 *  - a name the list uses with two different defaults becomes two
 *    variables, the later one numbered, since a variable has one default.
 *
 * `faults` says why no template can be kept: a blank that is left, or a
 * "{{" of the author's own that is not one of these markers.
 *
 * @param {string} text
 * @returns {{ template: string, variables: { name: string, default: string }[], changes: string[], faults: string[] }}
 */
export function templatePlaceholders(text) {
  const variables = [];
  const changes = [];
  const faults = [];
  const taken = new Set();
  // The list's own name and a default, to the variable that stands for them.
  const assigned = new Map();

  const template = text.replace(ARGUMENT, (whole, quote, rawName, fallback) => {
    if (fallback.trim() === '') {
      if (!faults.includes('a blank has no default')) faults.push('a blank has no default');
      return whole;
    }
    const listName = plain(rawName, quote).trim();
    const key = JSON.stringify([listName, fallback]);
    let name = assigned.get(key);
    if (name === undefined) {
      const fitted = fitVariableName(listName);
      // The same fitted name with this default is the same variable; with another default, a new one.
      const same = variables.find((variable) => variable.default === fallback && variable.listNames.has(fitted));
      if (same) {
        name = same.name;
      } else {
        name = fitted;
        for (let n = 2; taken.has(name); n++) name = numbered(fitted, n);
        taken.add(name);
        variables.push({ name, default: fallback, listNames: new Set([fitted]) });
        if (name !== listName) {
          changes.push(name === fitted
            ? `placeholder "${listName}" renamed "${name}"`
            : `placeholder "${listName}" has more than one default; the one that defaults to "${fallback}" is "${name}"`);
        }
      }
      assigned.set(key, name);
    }
    return `{{${name}}}`;
  });

  if (/\{argument\b/.test(template) && faults.length === 0) faults.push('a blank is malformed');
  const names = variables.map((variable) => variable.name);
  for (const problem of checkTemplate(template, names)) {
    if (problem.kind !== 'unused') faults.push(`the prompt has a "{{" of its own: ${problem.message}`);
  }
  return { template, variables: variables.map(({ name, default: fallback }) => ({ name, default: fallback })), changes, faults };
}

/** Words a blank is usually named with: [CITY NAME], [BRAND], [BACKGROUND COLOR]. */
const BLANK_WORD =
  /\b(NAME|CITY|COUNTRY|BRAND|COLOU?R|SUBJECT|LOCATION|DESTINATION|LANDMARK|PRODUCT|TEXT|TOPIC|YOUR|INSERT|XXX|FOOD|CLOTHING|ANIMAL|OBJECT|PLAYER|TEAM|DATE|YEAR|NUMBER|TITLE|LOGO|THEME|PLACE|PERSON|PROFESSION|SCENERY|HUMAN|PLANT|FLOWER)\b/;

/**
 * Blanks with no default: [CITY NAME], {{product}}, <YOUR TEXT>, [insert …].
 * A bracketed word in capitals that opens a line is a section label
 * ("[SCENE] A quiet harbour…"), not a blank, unless it is named like one.
 */
export function findBlanks(text) {
  const blanks = new Set();
  for (const line of text.split('\n')) {
    for (const match of line.matchAll(/\[([A-Z][A-Z0-9 _/&'-]{2,40})\]/g)) {
      const opensLine = line.slice(0, match.index).trim() === '';
      if (opensLine && !BLANK_WORD.test(match[1])) continue;
      blanks.add(match[0]);
    }
    for (const match of line.matchAll(/\{\{\s*[^{}]{1,40}\s*\}\}/g)) blanks.add(match[0]);
    for (const match of line.matchAll(/<(?:YOUR|INSERT|ENTER|ADD)[A-Z0-9 _-]{0,40}>/g)) blanks.add(match[0]);
    for (const match of line.matchAll(/\[(?:insert|your|enter|add|replace with)\b[^\]]{0,60}\]/gi)) blanks.add(match[0]);
  }
  return [...blanks];
}
