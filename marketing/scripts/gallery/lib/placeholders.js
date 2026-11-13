/**
 * Templated prompts. Some lists mark a blank as
 *
 *   {argument name="lantern color" default="warm red"}
 *
 * (with the quotes escaped when the prompt is itself JSON). Those resolve to
 * their default, and the blanks are kept as the recipe's prompt.variables.
 * Other blanks, like [CITY NAME], carry no default and cannot be resolved.
 */

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
