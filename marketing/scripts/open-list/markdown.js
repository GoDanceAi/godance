// @ts-check
/**
 * Markdown for the open list, written so that nothing in a recipe can be read
 * as syntax by GitHub: text is escaped, code is fenced with more backticks
 * than it contains, and a link's target cannot end early.
 */

/**
 * The characters escaped in running text: those that can start emphasis,
 * code, a link, HTML, an entity, a table cell, strikethrough, a heading's
 * closing marks, or GitHub's maths ($).
 */
const SPECIAL = /[\\`*_[\]<>&|~#$]/g;

/**
 * Text on one line, as written: every run of white space becomes one space,
 * and every character Markdown could read as syntax is escaped.
 * @param {string} value
 */
export function text(value) {
  return String(value).replace(/\s+/g, ' ').trim().replace(SPECIAL, '\\$&');
}

/** The longest run of backticks in a text. @param {string} value */
const longestTicks = (value) => Math.max(0, ...(value.match(/`+/g) ?? []).map((run) => run.length));

/**
 * Inline code on one line. In a table cell, a pipe is escaped too: GitHub
 * splits cells before it reads code.
 * @param {string} value
 * @param {{ inTable?: boolean }} [options]
 */
export function code(value, { inTable = false } = {}) {
  let content = String(value).replace(/[\r\n]+/g, ' ');
  if (content === '') return '` `';
  const ticks = '`'.repeat(longestTicks(content) + 1);
  // A space each side keeps a backtick at either end, or spaces at both, as they are.
  const pad = /^[`\s]|[`\s]$/.test(content) && /\S/.test(content) ? ' ' : '';
  if (inTable) content = content.replace(/\|/g, '\\|');
  return `${ticks}${pad}${content}${pad}${ticks}`;
}

/**
 * A fenced block holding the text exactly. The fence is longer than any run
 * of backticks inside it, so no line of the text can close it.
 * @param {string} value
 * @param {string} [info]
 */
export function codeBlock(value, info = 'text') {
  const fence = '`'.repeat(Math.max(3, longestTicks(value) + 1));
  return `${fence}${info}\n${value}\n${fence}`;
}

/**
 * A URL as the target of a Markdown link: backslashes and parentheses are
 * escaped, so the target ends where it should, and spaces are encoded.
 * @param {string} href
 */
export function url(href) {
  return String(href).replace(/[\\()]/g, '\\$&').replace(/ /g, '%20');
}

/**
 * A link. `label` is Markdown already: pass it through text() or code().
 * @param {string} label
 * @param {string} href
 */
export const link = (label, href) => `[${label}](${url(href)})`;

/**
 * An image, by its absolute URL.
 * @param {string} alt
 * @param {string} src
 */
export const image = (alt, src) => `![${text(alt)}](${url(src)})`;

/**
 * A value for an HTML attribute in double quotes. A pipe is encoded as well,
 * so the attribute can stand in a table cell.
 * @param {string} value
 */
export function attr(value) {
  return String(value)
    .replace(/&/g, '&amp;')
    .replace(/"/g, '&quot;')
    .replace(/</g, '&lt;')
    .replace(/>/g, '&gt;')
    .replace(/\|/g, '&#124;');
}

/**
 * Text for HTML content, such as a <summary>.
 * @param {string} value
 */
export function html(value) {
  return String(value).replace(/&/g, '&amp;').replace(/</g, '&lt;').replace(/>/g, '&gt;');
}

/**
 * A table, from its header cells and rows of cells that are Markdown already.
 * @param {string[]} header
 * @param {string[][]} rows
 */
export function table(header, rows) {
  const line = (/** @type {string[]} */ cells) => `| ${cells.join(' | ')} |`;
  return [line(header), line(header.map(() => '---')), ...rows.map(line)].join('\n');
}
