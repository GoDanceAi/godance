// @ts-check
/**
 * The gallery recipe format, in code. This is the only definition of it:
 * the content collection (src/content.config.ts), the `validate` command
 * and the import pipeline all read it from here.
 *
 * A recipe is one JSON file at <gallery folder>/<slug>.json holding a
 * prompt, one or more runs of it, and where the prompt came from.
 *
 * Plain ES module with no imports beyond Zod (through Astro, which already
 * ships it), so `node` can import it directly as well as Astro.
 */
import { z } from 'astro/zod';
import { NAME_MAX_LENGTH, checkTemplate, defaultsIn, isVariableName, renderTemplate } from './gallery-template.js';

/** Environment variable that points the gallery at another folder, e.g. fixtures. */
export const GALLERY_DIR_ENV = 'GALLERY_CONTENT_DIR';

/** Where recipes live, relative to the marketing project root. */
export const DEFAULT_GALLERY_DIR = 'src/content/gallery';

/** Licences a source list may carry. */
export const LICENCES = /** @type {const} */ (['CC0-1.0', 'CC-BY-4.0', 'MIT', 'Apache-2.0']);

/** Lowercase letters, digits and hyphens. */
export const SLUG_PATTERN = /^[a-z0-9-]+$/;

/** The most input slots one recipe may have. */
export const MAX_INPUT_SLOTS = 4;

/**
 * What an input slot may be to a recipe, by the recipe's modality: an image
 * recipe works from images; a video starts from a frame, ends on one, or
 * follows reference images. These are the API's own role names.
 */
export const INPUT_ROLES = Object.freeze({
  IMAGE: /** @type {const} */ (['image']),
  VIDEO: /** @type {const} */ (['first_frame', 'last_frame', 'reference_image']),
});

/**
 * Every rule a recipe can break, by the id that problems are reported under.
 * The last three are about the folder, not one recipe, and are raised by the
 * folder reader in scripts/gallery-schema/.
 */
export const RULES = Object.freeze({
  'slug-format': 'slug uses lowercase letters, digits and hyphens only',
  'slug-file-name': 'slug equals the file name: the recipe lives at <slug>.json',
  'prompt-languages': 'prompt.en and prompt.zh are both present and not empty',
  'template-languages': 'prompt.template, when a recipe has one, has both "en" and "zh", and neither is empty',
  'template-variables': 'a template comes with the variables it uses, and zh_default belongs to a template variable',
  'variable-name': `a template variable's name is 1–${NAME_MAX_LENGTH} ASCII letters, digits, spaces, hyphens or underscores, starting and ending with a letter or digit`,
  'variable-unique': 'no two template variables share a name',
  'template-markers': 'every "{{" in a template begins a declared variable, and each template uses every variable',
  'template-render': 'a template rendered with its defaults is the prompt in that language, exactly',
  'runs-min': 'a recipe has at least one run',
  'run-cost': 'every run states cost_cents',
  'run-outputs': 'every run has at least one output',
  'video-seconds': 'a video run states params.seconds',
  'input-slots': `a recipe with inputs has 1–${MAX_INPUT_SLOTS} slots, each named by the variable naming rule, and no two share a name`,
  'input-role': 'a slot\'s role fits the recipe: "image" for an image recipe; "first_frame", "last_frame" or "reference_image" for a video, with at most one first and one last frame',
  'input-sample': 'every slot has a sample input, an image generated on Godance, whose generated_from states the model, prompt, params, cost_cents and date',
  'input-runs': 'every run of a recipe with inputs names, for each slot, the sample it was made with; a recipe without inputs has runs without them',
  'media-path': 'media paths are relative: no scheme, no leading slash, no ".."',
  licence: `source.licence is one of ${LICENCES.join(', ')}`,
  'https-url': 'source.original_url and source.author_url are https URLs',
  'iso-date': 'dates are ISO dates, YYYY-MM-DD',
  shape: 'everything else in the format: keys, types and allowed values',
  json: 'a recipe file is valid JSON',
  'duplicate-slug': 'no two recipes share a slug',
  folder: 'the gallery folder exists',
});

/** @typedef {keyof typeof RULES} RuleId */

/**
 * One way a recipe breaks the format.
 * @typedef {object} RecipeProblem
 * @property {RuleId} rule
 * @property {string} path Where in the recipe, e.g. `runs[0].outputs[0].file`; empty for the recipe as a whole.
 * @property {string} message
 */

// ---------------------------------------------------------------------------
// Building blocks. Each message is tagged with its rule ("rule-id: text") so
// that checkRecipe can report which rule was broken, not only where.
// ---------------------------------------------------------------------------

/**
 * @param {RuleId} rule
 * @param {string} text
 */
const tag = (rule, text) => `${rule}: ${text}`;

/**
 * An error for a missing or wrongly typed value that leaves every other
 * problem with it (an unknown key, say) to Zod's own wording.
 * @param {string} message
 * @returns {(issue: { code?: string }) => string | undefined}
 */
const whenMissing = (message) => (issue) => (issue.code === 'invalid_type' ? message : undefined);

/** Text that is present and not blank. @param {RuleId} [rule] */
const text = (rule = 'shape') => {
  const message = tag(rule, 'required, and must not be empty');
  return z.string({ error: message }).regex(/\S/, message);
};

/** A whole number no smaller than `min`. @param {number} min @param {string} message */
const wholeNumber = (min, message) => z.int({ error: message }).min(min, message);

const isoDate = () => z.iso.date({ error: tag('iso-date', 'must be an ISO date, YYYY-MM-DD') });

/** @param {string} value */
function isHttpsUrl(value) {
  // The prefix check keeps out forms URL() would quietly repair, such as "https:host".
  if (!/^https:\/\/\S+$/i.test(value)) return false;
  try {
    const url = new URL(value);
    return url.protocol === 'https:' && url.hostname !== '';
  } catch {
    return false;
  }
}

const httpsUrl = () =>
  z
    .string({ error: tag('https-url', 'required: an https URL') })
    .refine(isHttpsUrl, tag('https-url', 'must be an https URL'));

/**
 * Why a media path is not a plain relative path, or null when it is one.
 * A backslash is refused as well: browsers read it as a slash, so it could
 * stand in for a leading slash.
 * @param {string} value
 */
function mediaPathFault(value) {
  if (!/\S/.test(value)) return 'is empty';
  if (/^[a-z][a-z0-9+.-]*:/i.test(value)) return 'has a scheme';
  if (/^[\\/]/.test(value)) return 'starts with a slash';
  if (value.includes('..')) return 'contains ".."';
  if (value.includes('\\')) return 'contains a backslash';
  return null;
}

const mediaPath = () =>
  z.string({ error: tag('media-path', 'required: a path relative to the media root') }).refine(
    (value) => mediaPathFault(value) === null,
    {
      error: (issue) =>
        tag('media-path', `must be a relative path, but it ${mediaPathFault(String(issue.input))}`),
    },
  );

/** @param {'image' | 'video'} family */
const contentType = (family) => {
  const message = tag('shape', `must be a media type starting with "${family}/"`);
  return z.string({ error: message }).regex(new RegExp(`^${family}/[a-z0-9][a-z0-9.+-]*$`), message);
};

const pixels = () => wholeNumber(1, tag('shape', 'must be a whole number of pixels, at least 1'));

// ---------------------------------------------------------------------------
// The prompt, and its template when it has one
// ---------------------------------------------------------------------------

/** Where two texts first differ, quoted for a message. @param {string} a @param {string} b */
function firstDifference(a, b) {
  let at = 0;
  while (at < a.length && at < b.length && a[at] === b[at]) at++;
  const quote = (/** @type {string} */ text) => JSON.stringify(text.slice(at, at + 24));
  return `at character ${at}: the template gives ${quote(a)} where the prompt has ${quote(b)}`;
}

/**
 * The checks that tie a template to its variables and to the prompt. They
 * run once the prompt's own shape is right, so a missing key is reported
 * once, as what it is.
 *
 * A recipe published before templates has variables and no template: the
 * blanks its source had, with the values that filled them. That stays valid,
 * so nothing here applies until a recipe has a template or a zh_default.
 *
 * @param {{ en: string, zh: string, template?: { en: string, zh: string }, variables?: { name: string, default: string, zh_default?: string }[] }} prompt
 * @param {{ addIssue: (issue: { code: 'custom', message: string, path: (string | number)[] }) => void }} ctx
 */
function checkTemplateOf(prompt, ctx) {
  /** @param {RuleId} rule @param {(string | number)[]} path @param {string} text */
  const problem = (rule, path, text) => ctx.addIssue({ code: 'custom', message: tag(rule, text), path });
  const variables = prompt.variables ?? [];

  if (!prompt.template) {
    variables.forEach((variable, i) => {
      if (variable.zh_default !== undefined) {
        problem('template-variables', ['variables', i, 'zh_default'],
          'zh_default is the Chinese default of a template variable, and this prompt has no template');
      }
    });
    return;
  }
  if (variables.length === 0) {
    problem('template-variables', ['variables'], 'a template comes with variables: list each {{name}} it uses, with its default');
    return;
  }

  let namesAreSound = true;
  const seen = new Set();
  variables.forEach((variable, i) => {
    if (!isVariableName(variable.name)) {
      namesAreSound = false;
      problem('variable-name', ['variables', i, 'name'],
        `must be 1–${NAME_MAX_LENGTH} ASCII letters, digits, spaces, hyphens or underscores, starting and ending with a letter or digit`);
    } else if (seen.has(variable.name)) {
      namesAreSound = false;
      problem('variable-unique', ['variables', i, 'name'], `"${variable.name}" is declared more than once`);
    }
    seen.add(variable.name);
  });
  // The markers can only be judged against names that are themselves sound.
  if (!namesAreSound) return;

  const names = variables.map((variable) => variable.name);
  for (const lang of /** @type {const} */ (['en', 'zh'])) {
    // A missing or blank template is already reported as that.
    if (typeof prompt.template[lang] !== 'string' || !/\S/.test(prompt.template[lang])) continue;
    const faults = checkTemplate(prompt.template[lang], names);
    for (const fault of faults) problem('template-markers', ['template', lang], fault.message);
    if (faults.length > 0) continue;
    const rendered = renderTemplate(prompt.template[lang], defaultsIn(variables, lang));
    if (rendered !== prompt[lang]) {
      problem('template-render', ['template', lang],
        `rendered with its defaults it must be prompt.${lang} exactly; they differ ${firstDifference(rendered, prompt[lang])}`);
    }
  }
}

// ---------------------------------------------------------------------------
// Outputs and runs. An image recipe and a video recipe differ only in their
// outputs and in what their params must state.
// ---------------------------------------------------------------------------

const imageOutput = z.strictObject({
  file: mediaPath(),
  thumb: mediaPath(),
  content_type: contentType('image'),
  width: pixels(),
  height: pixels(),
});

const videoOutput = z.strictObject({
  file: mediaPath(),
  poster: mediaPath().optional(),
  content_type: contentType('video'),
  duration_seconds: z
    .number({ error: tag('shape', 'must be a number of seconds greater than 0') })
    .positive(tag('shape', 'must be a number of seconds greater than 0')),
  width: pixels(),
  height: pixels(),
});

/** Request fields as the API names them. */
const imageParams = z.record(z.string(), z.unknown(), {
  error: tag('shape', 'required: the request fields the run was made with'),
});

/** The same, and a video run always states how long the clip is. */
const videoParams = z.looseObject(
  {
    seconds: wholeNumber(1, tag('video-seconds', 'a video run states seconds, a whole number of at least 1')),
  },
  { error: tag('shape', 'required: the request fields the run was made with') },
);

/**
 * One run of the prompt. Keys are in the order the format lists them, which
 * is also the order problems are reported in.
 *
 * @template {z.ZodType} Params
 * @template {z.ZodType} Output
 * @param {Params} params
 * @param {Output} output
 */
const runOf = (params, output) =>
  z.strictObject({
    model: text(),
    prompt_lang: z.enum(['en', 'zh'], { error: tag('shape', 'must be "en" or "zh"') }),
    params,
    // For a recipe with inputs: the file each slot was given for this run, by
    // slot name. It is always the slot's sample (checked by checkInputsOf).
    inputs: z
      .record(z.string(), z.string(), {
        error: tag('input-runs', 'must be { "<slot name>": "<the sample file it was made with>" }'),
      })
      .optional(),
    // The job's actualCostCents at the time it was generated.
    cost_cents: wholeNumber(0, tag('run-cost', 'every run states its cost, a whole number of cents')),
    generated_on: isoDate(),
    // The day the operator approved this run, for a run added to a recipe that
    // was already published (the import tool's rerun stage). A run published
    // with its recipe has none: review.approved_on covers it.
    approved_on: isoDate().optional(),
    outputs: z
      .array(output, { error: tag('run-outputs', 'every run has at least one output') })
      .min(1, tag('run-outputs', 'every run has at least one output')),
  });

// ---------------------------------------------------------------------------
// Input slots (round 11). A recipe may ask for images: a slot says what kind,
// in words a visitor can act on, and carries a sample generated on Godance,
// so the page can show input and output side by side and "Run this" has
// something to start from. A sample is never taken from a source list.
// ---------------------------------------------------------------------------

/** How a sample was generated: the same facts a run states. */
const generatedFrom = z.strictObject(
  {
    model: text('input-sample'),
    prompt: text('input-sample'),
    params: z.record(z.string(), z.unknown(), {
      error: tag('input-sample', 'required: the request fields the sample was generated with'),
    }),
    cost_cents: wholeNumber(0, tag('input-sample', 'a sample states its cost, a whole number of cents')),
    generated_on: z.iso.date({ error: tag('input-sample', 'a sample states the ISO date it was generated on, YYYY-MM-DD') }),
  },
  {
    error: whenMissing(
      tag('input-sample', 'a sample says how it was generated on Godance: { model, prompt, params, cost_cents, generated_on }'),
    ),
  },
);

const sampleInput = z.strictObject(
  {
    file: mediaPath(),
    content_type: z
      .string({ error: tag('input-sample', 'a sample is an image: a media type starting with "image/"') })
      .regex(/^image\/[a-z0-9][a-z0-9.+-]*$/, tag('input-sample', 'a sample is an image: a media type starting with "image/"')),
    width: pixels(),
    height: pixels(),
    generated_from: generatedFrom,
  },
  { error: whenMissing(tag('input-sample', 'every slot has a sample input, generated on Godance')) },
);

/** @param {'IMAGE' | 'VIDEO'} modality */
const inputSlot = (modality) => {
  const roles = INPUT_ROLES[modality];
  const roleMessage = tag(
    'input-role',
    `must be ${roles.map((role) => `"${role}"`).join(', ')} in ${modality === 'VIDEO' ? 'a video' : 'an image'} recipe`,
  );
  const nameMessage = tag(
    'input-slots',
    `a slot's name is 1–${NAME_MAX_LENGTH} ASCII letters, digits, spaces, hyphens or underscores, starting and ending with a letter or digit`,
  );
  return z.strictObject({
    slot: z.string({ error: nameMessage }).refine(isVariableName, nameMessage),
    role: z.enum(roles, { error: roleMessage }),
    // What to put in the slot, in words a visitor can act on.
    hint: z.strictObject(
      { en: text(), zh: text().optional() },
      { error: whenMissing(tag('shape', 'required: { "en": … }, with an optional "zh"')) },
    ),
    sample: sampleInput,
  });
};

/** @param {'IMAGE' | 'VIDEO'} modality */
const inputSlots = (modality) => {
  const message = tag('input-slots', `a recipe with inputs has 1–${MAX_INPUT_SLOTS} slots`);
  return z.array(inputSlot(modality), { error: message }).min(1, message).max(MAX_INPUT_SLOTS, message);
};

/**
 * The checks across a recipe's slots and its runs. They run once the shape
 * is right, so a missing key is reported once, as what it is.
 *
 * @param {{ inputs?: { slot: string, role: string, sample: { file: string } }[], runs: { inputs?: Record<string, string> }[] }} recipe
 * @param {{ addIssue: (issue: { code: 'custom', message: string, path: (string | number)[] }) => void }} ctx
 */
function checkInputsOf(recipe, ctx) {
  /** @param {RuleId} rule @param {(string | number)[]} path @param {string} text */
  const problem = (rule, path, text) => ctx.addIssue({ code: 'custom', message: tag(rule, text), path });
  const slots = recipe.inputs ?? [];
  // An empty list of slots is already reported as that; the runs cannot be judged against it.
  if (recipe.inputs !== undefined && slots.length === 0) return;

  let namesAreSound = true;
  const seen = new Set();
  const single = new Set();
  slots.forEach((slot, i) => {
    if (seen.has(slot.slot)) {
      namesAreSound = false;
      problem('input-slots', ['inputs', i, 'slot'], `"${slot.slot}" names more than one slot`);
    }
    seen.add(slot.slot);
    // A video starts from one frame and ends on one; reference images may be several.
    if (slot.role === 'first_frame' || slot.role === 'last_frame') {
      if (single.has(slot.role)) {
        problem('input-role', ['inputs', i, 'role'], `a video has at most one "${slot.role}"`);
      }
      single.add(slot.role);
    }
  });

  recipe.runs.forEach((run, i) => {
    if (slots.length === 0) {
      if (run.inputs !== undefined) {
        problem('input-runs', ['runs', i, 'inputs'], 'the recipe has no input slots, so a run names no inputs');
      }
      return;
    }
    if (run.inputs === undefined) {
      problem('input-runs', ['runs', i, 'inputs'],
        'the recipe has input slots, so every run names the sample each slot was given: { "<slot name>": "<sample file>" }');
      return;
    }
    // The runs can only be judged against slot names that are themselves sound.
    if (!namesAreSound) return;
    const faults = [];
    for (const slot of slots) {
      const used = run.inputs[slot.slot];
      if (used === undefined) faults.push(`it names nothing for "${slot.slot}"`);
      else if (used !== slot.sample.file) faults.push(`"${slot.slot}" must be its sample, ${slot.sample.file}`);
    }
    for (const name of Object.keys(run.inputs)) {
      if (!seen.has(name)) faults.push(`"${name}" is not a slot of this recipe`);
    }
    if (faults.length > 0) problem('input-runs', ['runs', i, 'inputs'], faults.join('; '));
  });
}

// ---------------------------------------------------------------------------
// The recipe
// ---------------------------------------------------------------------------

/**
 * A recipe of one modality: one prompt, and one or more runs of it. The
 * first run is the one the gallery shows by default.
 *
 * @template {'IMAGE' | 'VIDEO'} Modality
 * @template {z.ZodType} Run
 * @param {Modality} modality
 * @param {Run} run
 */
const recipeOf = (modality, run) =>
  z.strictObject({
    slug: z
      .string({ error: tag('slug-format', 'required: lowercase letters, digits and hyphens') })
      .regex(SLUG_PATTERN, tag('slug-format', 'must use lowercase letters, digits and hyphens only')),

    title: z.strictObject(
      { en: text(), zh: text().optional() },
      { error: whenMissing(tag('shape', 'required: { "en": … }, with an optional "zh"')) },
    ),

    modality: z.literal(modality),

    tags: z.array(text(), { error: tag('shape', 'required: a list of tags, which may be empty') }),

    prompt: z
      .strictObject(
        {
          en: text('prompt-languages'),
          zh: text('prompt-languages'),
          // "list": the source list supplied the Chinese text. "machine": it was machine-translated.
          zh_origin: z.enum(['list', 'machine'], { error: tag('shape', 'must be "list" or "machine"') }),
          // The prompt with its blanks kept as {{name}} markers, in both languages
          // (src/lib/gallery-template.js). Rendered with the defaults below, each
          // gives the prompt in its language exactly.
          template: z
            .strictObject(
              { en: text('template-languages'), zh: text('template-languages') },
              { error: whenMissing(tag('template-languages', 'must be { "en", "zh" }, with both')) },
            )
            .optional(),
          // The blanks of a templated prompt. `default` produced prompt.en; `zh_default`,
          // where the Chinese prompt uses other words, produced prompt.zh.
          variables: z
            .array(
              z.strictObject({
                name: text(),
                default: z.string({ error: tag('shape', 'required, as text') }),
                zh_default: z.string({ error: tag('shape', 'must be text') }).optional(),
              }),
              { error: tag('shape', 'must be a list of { "name", "default" }, with an optional "zh_default"') },
            )
            .optional(),
        },
        { error: whenMissing(tag('prompt-languages', 'required, with both "en" and "zh"')) },
      )
      .superRefine(checkTemplateOf),

    // The images this recipe works from, when it needs any: up to four slots,
    // each with a sample generated on Godance. Absent for a recipe that runs
    // from its prompt alone.
    inputs: inputSlots(modality).optional(),

    runs: z
      .array(run, { error: tag('runs-min', 'a recipe has at least one run') })
      .min(1, tag('runs-min', 'a recipe has at least one run')),

    source: z.strictObject(
      {
        list: text(),
        list_commit: text(),
        licence: z.enum(LICENCES, { error: tag('licence', `must be one of ${LICENCES.join(', ')}`) }),
        author: text(),
        author_url: httpsUrl(),
        original_url: httpsUrl(),
        // What was changed from the original, as CC BY requires.
        changes: z.array(text(), {
          error: tag('shape', 'required: a list of what was changed from the original, which may be empty'),
        }),
      },
      { error: whenMissing(tag('shape', 'required: where the prompt came from')) },
    ),

    review: z.strictObject(
      {
        approved_on: isoDate(),
        standard_version: wholeNumber(1, tag('shape', 'must be a whole number, at least 1')),
      },
      { error: whenMissing(tag('shape', 'required: { "approved_on", "standard_version" }')) },
    ),

    published_on: isoDate(),
  }).superRefine(checkInputsOf);

const imageRecipe = recipeOf('IMAGE', runOf(imageParams, imageOutput));
const videoRecipe = recipeOf('VIDEO', runOf(videoParams, videoOutput));

/**
 * The recipe format. Unknown keys are refused everywhere except in a run's
 * `params`, which carries whatever request fields the API takes.
 *
 * It cannot see the file a recipe was read from, so "slug equals the file
 * name" is checked by `checkRecipe`.
 */
export const recipeSchema = z.discriminatedUnion('modality', [imageRecipe, videoRecipe], {
  error: (issue) => {
    if (issue.code === 'invalid_union') return tag('shape', 'must be "IMAGE" or "VIDEO"');
    if (issue.code === 'invalid_type') return tag('shape', 'a recipe is a JSON object');
    return undefined;
  },
});

/** @typedef {z.infer<typeof recipeSchema>} Recipe */
/** @typedef {z.infer<typeof imageRecipe>} ImageRecipe */
/** @typedef {z.infer<typeof videoRecipe>} VideoRecipe */

// ---------------------------------------------------------------------------
// Checking a recipe
// ---------------------------------------------------------------------------

/** The file a recipe with this slug lives in, relative to the gallery folder. @param {string} slug */
export const recipeFileName = (slug) => `${slug}.json`;

/** @param {ReadonlyArray<PropertyKey>} path */
function formatPath(path) {
  let out = '';
  for (const part of path) {
    if (typeof part === 'number') out += `[${part}]`;
    else out += out === '' ? String(part) : `.${String(part)}`;
  }
  return out;
}

/** @param {string} value @returns {value is RuleId} */
const isRuleId = (value) => Object.hasOwn(RULES, value);

/**
 * Check one recipe against the format. Returns every problem found; an empty
 * list means the recipe is valid.
 *
 * Pass `file`, the recipe's path relative to the gallery folder with forward
 * slashes, to also check that the slug equals the file name.
 *
 * @param {unknown} data The parsed JSON.
 * @param {{ file?: string }} [options]
 * @returns {RecipeProblem[]}
 */
export function checkRecipe(data, { file } = {}) {
  /** @type {RecipeProblem[]} */
  const problems = [];

  const result = recipeSchema.safeParse(data);
  if (!result.success) {
    for (const issue of result.error.issues) {
      const tagged = /^([a-z-]+): ([\s\S]*)$/.exec(issue.message);
      const path = formatPath(issue.path);
      if (tagged && isRuleId(tagged[1])) {
        problems.push({ rule: tagged[1], path, message: tagged[2] });
      } else {
        problems.push({ rule: 'shape', path, message: issue.message });
      }
    }
  }

  const slug = typeof data === 'object' && data !== null && 'slug' in data ? data.slug : undefined;
  if (file !== undefined && typeof slug === 'string' && file !== recipeFileName(slug)) {
    problems.push({
      rule: 'slug-file-name',
      path: 'slug',
      message: `"${slug}" must equal the file name: this recipe belongs at ${recipeFileName(slug)}, directly in the gallery folder`,
    });
  }

  return problems;
}

/**
 * One line for a problem: `[rule] path: message`.
 * @param {RecipeProblem} problem
 */
export function formatProblem({ rule, path, message }) {
  return path === '' ? `[${rule}] ${message}` : `[${rule}] ${path}: ${message}`;
}

/**
 * The gallery folder as configured: `GALLERY_CONTENT_DIR` when set, the
 * default otherwise. A relative path is relative to the marketing project root.
 * @param {Record<string, string | undefined>} [env]
 */
export function galleryContentDir(env = globalThis.process?.env ?? {}) {
  const override = env[GALLERY_DIR_ENV]?.trim();
  return override ? override : DEFAULT_GALLERY_DIR;
}
