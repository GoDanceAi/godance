/**
 * retemplate: give a recipe that is already published its template.
 *
 * A recipe published before templates had its blanks filled and kept only
 * the result. This reads the entry it was made from, in its list at the
 * commit the recipe pins, builds the template the way `translate` does, and
 * adds it to the recipe.
 *
 * It writes only when the template, filled with its defaults, is the
 * published prompt exactly, in English and in Chinese. So the prompt the
 * outputs were made from, the outputs, their costs and the review all stay
 * true, and nothing here needs a model: no paid call is made, ever.
 *
 * The language the list does not supply was machine-translated when the
 * recipe was made. Its template is made from the published text, which
 * works when each default stands in it as written: a number, a size, a
 * brand name. A default that was translated cannot be found again without
 * translating, so such a recipe is left as it is, and the report says so.
 */
import { readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { SLUG_PATTERN, checkRecipe, formatProblem, recipeFileName } from '../../../src/lib/gallery-schema.js';
import { defaultsIn, markersOf, renderTemplate } from '../../../src/lib/gallery-template.js';
import { templatePlaceholders } from '../lib/placeholders.js';
import { oneLine, postKey } from '../lib/text.js';
import { ADAPTERS } from './fetch.js';
import { RESOLVED, REWRITTEN } from './translate.js';

const LANGUAGE = { en: 'English', zh: 'Chinese' };
const FULL_COMMIT = /^[0-9a-f]{40}$/;

/** Where two texts first differ, for a report. */
function difference(made, published) {
  let at = 0;
  while (at < made.length && at < published.length && made[at] === published[at]) at++;
  const quote = (text) => JSON.stringify(text.slice(at, at + 24));
  return `at character ${at}: the template gives ${quote(made)} where the recipe has ${quote(published)}`;
}

/** How many times `needle` stands in `text`, not overlapping. */
function occurrences(text, needle) {
  let count = 0;
  for (let at = text.indexOf(needle); at !== -1; at = text.indexOf(needle, at + needle.length)) count++;
  return count;
}

/**
 * The template of a language the list does not supply, made from the
 * published text: each default is put back as its marker. Possible only
 * when every default stands in the text as written, exactly as many times
 * as the list's own template uses its marker.
 *
 * @returns {{ template: string } | { why: string }}
 */
export function templateFromPublished(published, listTemplate, variables, lang) {
  const uses = {};
  for (const name of markersOf(listTemplate)) uses[name] = (uses[name] ?? 0) + 1;

  // The text in pieces: what is still literal, and the markers put back so far.
  let pieces = [{ text: published }];
  // Longest default first, so a default that is part of another is not taken for it.
  for (const variable of [...variables].sort((a, b) => b.default.length - a.default.length)) {
    if (variable.default === '') return { why: `the default of {{${variable.name}}} is empty, so where it stood in the ${LANGUAGE[lang]} prompt cannot be found` };
    const found = pieces.reduce((sum, piece) => sum + ('text' in piece ? occurrences(piece.text, variable.default) : 0), 0);
    if (found !== uses[variable.name]) {
      return {
        why: found === 0
          ? `the ${LANGUAGE[lang]} prompt does not contain the default of {{${variable.name}}} as written ("${oneLine(variable.default, 40)}"): it was translated, and a ${LANGUAGE[lang]} template cannot be made without translating again`
          : `the ${LANGUAGE[lang]} prompt has "${oneLine(variable.default, 40)}" ${found} time${found === 1 ? '' : 's'}, where the template uses {{${variable.name}}} ${uses[variable.name]} time${uses[variable.name] === 1 ? '' : 's'}: which of them is the blank cannot be told`,
      };
    }
    pieces = pieces.flatMap((piece) => {
      if (!('text' in piece)) return [piece];
      const parts = piece.text.split(variable.default);
      return parts.flatMap((text, i) => (i === 0 ? [{ text }] : [{ name: variable.name }, { text }]));
    });
  }
  return { template: pieces.map((piece) => ('name' in piece ? `{{${piece.name}}}` : piece.text)).join('') };
}

/**
 * The recipe with its template, or why it is left as it is. Reads nothing
 * and writes nothing.
 *
 * @param {object} recipe The published recipe.
 * @param {{ lang: string, variants: Record<string, string> }} entry The entry it was made from, as the list has it at the recipe's commit.
 * @returns {{ recipe: object, variables: string[] } | { why: string }}
 */
export function templateFor(recipe, entry) {
  if (recipe.prompt?.template) return { why: 'it already has a template' };

  const built = {};
  for (const lang of ['en', 'zh']) {
    if (entry.variants?.[lang]) built[lang] = templatePlaceholders(entry.variants[lang]);
  }
  const own = built[entry.lang];
  if (!own || own.variables.length === 0) return { why: 'its source prompt has no blanks' };
  if (own.faults.length > 0) return { why: `no template can be made from its source prompt: ${own.faults.join('; ')}` };

  // The list's own language first: its template must give back the published prompt.
  const made = renderTemplate(own.template, own.variables);
  if (made !== recipe.prompt[entry.lang]) {
    return { why: `the ${LANGUAGE[entry.lang]} prompt is not the source prompt with its blanks filled; they differ ${difference(made, recipe.prompt[entry.lang])}` };
  }

  const other = entry.lang === 'en' ? 'zh' : 'en';
  const template = { [entry.lang]: own.template };
  let variables = own.variables;
  if (built[other]) {
    // The list supplies both languages: each has its own defaults.
    const theirs = built[other];
    const sameBlanks = theirs.faults.length === 0 && theirs.variables.length === own.variables.length
      && own.variables.every((variable) => theirs.variables.some((v) => v.name === variable.name));
    if (!sameBlanks) return { why: "the list's English and Chinese prompts do not have the same blanks" };
    template[other] = theirs.template;
    variables = built.en.variables.map((variable) => {
      const zh = built.zh.variables.find((v) => v.name === variable.name).default;
      return zh === variable.default ? variable : { ...variable, zh_default: zh };
    });
  } else {
    const derived = templateFromPublished(recipe.prompt[other], own.template, own.variables, other);
    if ('why' in derived) return derived;
    template[other] = derived.template;
  }
  const rendered = renderTemplate(template[other], defaultsIn(variables, other));
  if (rendered !== recipe.prompt[other]) {
    return { why: `the ${LANGUAGE[other]} prompt is not its template with the blanks filled; they differ ${difference(rendered, recipe.prompt[other])}` };
  }

  // Only the prompt's template and variables, and what source.changes says of the blanks, change.
  const changes = recipe.source.changes.includes(RESOLVED)
    ? recipe.source.changes.flatMap((change) => (change === RESOLVED ? [REWRITTEN, ...own.changes] : [change]))
    : [REWRITTEN, ...own.changes, ...recipe.source.changes];
  const { variables: _filled, ...prompt } = recipe.prompt;
  const next = {
    ...recipe,
    prompt: { ...prompt, template: { en: template.en, zh: template.zh }, variables },
    source: { ...recipe.source, changes },
  };
  const problems = checkRecipe(next, { file: recipeFileName(recipe.slug) });
  if (problems.length > 0) return { why: `the recipe with its template would not be valid: ${problems.map(formatProblem).join('; ')}` };
  return { recipe: next, variables: variables.map((variable) => variable.name) };
}

/**
 * Every recipe named, or every recipe in the folder, with what was done and
 * why. Reads the lists through `reader`; writes a recipe only with `apply`.
 *
 * @param {object} options
 * @param {string[] | null} options.slugs The recipes to do, or null for all of them.
 * @param {string} options.contentDir
 * @param {object[]} options.sources config/sources.json: which adapter reads which list.
 * @param {{ paths: Function, text: Function }} options.reader From createListReader.
 * @param {boolean} [options.apply]
 * @returns {Promise<{ slug: string, added: boolean, why: string, variables: string[] }[]>}
 */
export async function retemplate({ slugs = null, contentDir, sources, reader, apply = false }) {
  let names = slugs;
  if (names === null) {
    const files = await readdir(contentDir).catch((error) => {
      if (error.code === 'ENOENT') return [];
      throw error;
    });
    names = files.filter((file) => file.endsWith('.json')).map((file) => file.slice(0, -'.json'.length)).sort();
  }

  // One read of each list at each commit, however many recipes came from it.
  const lists = new Map();
  async function entriesOf(source, commit) {
    const key = `${source.repo}@${commit}`;
    if (!lists.has(key)) {
      const pinned = { ...source, commit };
      const adapter = ADAPTERS[source.adapter];
      lists.set(key, (async () => {
        const files = {};
        for (const filePath of adapter.wanted(await reader.paths(pinned))) files[filePath] = await reader.text(pinned, filePath);
        return adapter.parse(files);
      })());
    }
    return lists.get(key);
  }

  const results = [];
  for (const slug of names) {
    const kept = (why) => results.push({ slug, added: false, why, variables: [] });
    if (typeof slug !== 'string' || !SLUG_PATTERN.test(slug)) {
      kept('it is not a slug');
      continue;
    }
    const file = path.join(contentDir, recipeFileName(slug));
    let recipe;
    try {
      recipe = JSON.parse(await readFile(file, 'utf8'));
    } catch (error) {
      kept(error.code === 'ENOENT' ? `there is no recipe ${recipeFileName(slug)} in the gallery folder` : `${recipeFileName(slug)} could not be read: ${error.message}`);
      continue;
    }
    const before = checkRecipe(recipe, { file: recipeFileName(slug) });
    if (before.length > 0) {
      kept(`it is not a valid recipe as it stands: ${before.map(formatProblem).join('; ')}`);
      continue;
    }
    if (recipe.prompt.template) {
      kept('it already has a template');
      continue;
    }
    const source = sources.find((candidate) => candidate.repo === recipe.source.list && ADAPTERS[candidate.adapter]);
    if (!source) {
      kept(`its list, ${recipe.source.list}, is not one of the configured sources`);
      continue;
    }
    if (!FULL_COMMIT.test(recipe.source.list_commit)) {
      kept('it does not pin its list to a full commit, so the entry it came from cannot be read again');
      continue;
    }
    let entries;
    try {
      entries = await entriesOf(source, recipe.source.list_commit);
    } catch (error) {
      kept(`its list could not be read at ${recipe.source.list_commit.slice(0, 12)}: ${error.message}`);
      continue;
    }
    const key = postKey(recipe.source.original_url);
    const entry = entries.find((candidate) => candidate.original_url && postKey(candidate.original_url) === key);
    if (!entry) {
      kept(`the entry it came from is not in ${recipe.source.list} at ${recipe.source.list_commit.slice(0, 12)}`);
      continue;
    }

    const made = templateFor(recipe, entry);
    if ('why' in made) {
      kept(made.why);
      continue;
    }
    if (apply) await writeFile(file, `${JSON.stringify(made.recipe, null, 2)}\n`);
    results.push({ slug, added: true, why: 'the template, filled with its defaults, is the published prompt in both languages', variables: made.variables });
  }
  return results;
}
