// @ts-check
/**
 * The open list, built from the gallery's recipes: README.md and
 * README_zh.md, data/recipes.json, LICENSE.md and CONTRIBUTING.md.
 *
 * Pure: the same recipes and settings give the same bytes. Nothing here
 * reads a clock, the environment or the file system, and nothing is sorted
 * by locale; the only date in the list that is not a recipe's own is "last
 * updated", the newest published_on.
 *
 * Media is only ever linked, by its absolute URL on the media host.
 */
import { LICENCE_URLS, creditMarkdown, creditOf } from './credit.js';
import { attr, code, codeBlock, html, image, link, table, text } from './markdown.js';

/** @typedef {import('../../src/lib/gallery-schema.js').Recipe} Recipe */
/** @typedef {Recipe['runs'][number]} Run */
/** @typedef {Run['outputs'][number]} Output */
/** @typedef {NonNullable<Recipe['inputs']>[number]} InputSlot */
/** @typedef {'en' | 'zh'} Lang */

/**
 * @typedef {object} Settings
 * @property {string} mediaBaseUrl The media base, ending in a slash.
 * @property {string} removalContact An email address, or an http(s) URL.
 * @property {string} outputLicence The licence statement for the outputs; may be empty.
 * @property {boolean} runThis Whether the recipe pages show "Run this".
 * @property {string} site The site's origin, without a trailing slash.
 * @property {string} app The app's origin, without a trailing slash.
 */

/** What the list is made of. publish.js copies these files, and only these. */
export const FILES = Object.freeze(['README.md', 'README_zh.md', 'data/recipes.json', 'LICENSE.md', 'CONTRIBUTING.md']);

// ---------------------------------------------------------------------------
// As the gallery pages do it (src/lib/gallery-recipes.ts and the recipe page)
// ---------------------------------------------------------------------------

/** The public URL of a media file: each part of its path encoded. @param {string} base Ends in a slash. @param {string} file */
export const mediaUrl = (base, file) => base + file.split('/').map(encodeURIComponent).join('/');

/** @param {Output} output @returns {output is Extract<Output, { duration_seconds: number }>} */
const isVideoOutput = (output) => 'duration_seconds' in output;

/** The still for an output: an image's thumbnail, a video's poster, or null. @param {Output} output */
const stillOf = (output) => (isVideoOutput(output) ? output.poster ?? null : output.thumb);

/** "$0.06" for 6: the cost as it was recorded, never recomputed. @param {number} cents */
export const formatCost = (cents) => `$${(cents / 100).toFixed(2)}`;

/** A parameter's value as text: strings as they are, everything else as JSON. @param {unknown} value */
const formatParam = (value) => (typeof value === 'string' ? value : JSON.stringify(value));

/** Code-point order, the same on every machine. @param {string} a @param {string} b */
const compare = (a, b) => (a < b ? -1 : a > b ? 1 : 0);

/** The gallery's order: newest first, then by slug. @param {Recipe} a @param {Recipe} b */
export const galleryOrder = (a, b) => compare(b.published_on, a.published_on) || compare(a.slug, b.slug);

/**
 * How many recipes have each value, most first, then in code-point order.
 * @param {Recipe[]} recipes
 * @param {(recipe: Recipe) => string[]} valuesOf
 */
function tally(recipes, valuesOf) {
  /** @type {Map<string, number>} */
  const counts = new Map();
  for (const recipe of recipes) {
    for (const value of new Set(valuesOf(recipe))) counts.set(value, (counts.get(value) ?? 0) + 1);
  }
  return [...counts]
    .map(([value, count]) => ({ value, count }))
    .sort((a, b) => b.count - a.count || compare(a.value, b.value));
}

/** The query every link from the list carries, naming the recipe it came from. @param {string} campaign */
const tracking = (campaign) => `utm_source=github&utm_medium=open-list&utm_campaign=${encodeURIComponent(campaign)}`;

/**
 * Where an entry links: its recipe page, and "Run this" into the app, or
 * "Get an API key" when the pages do not show "Run this" yet.
 * @param {Recipe} recipe
 * @param {Settings} settings
 */
function linksOf(recipe, settings) {
  const query = tracking(recipe.slug);
  const kind = recipe.modality === 'VIDEO' ? 'video' : 'image';
  return {
    recipe: `${settings.site}/gallery/${recipe.slug}?${query}`,
    ...(settings.runThis
      ? { run_this: `${settings.app}/create/${kind}?example=${encodeURIComponent(recipe.slug)}&${query}` }
      : { get_api_key: `${settings.app}/signup?${query}` }),
  };
}

/** The newest published_on, or null for no recipes. @param {Recipe[]} recipes */
const lastUpdated = (recipes) => recipes.map((recipe) => recipe.published_on).sort(compare).at(-1) ?? null;

// ---------------------------------------------------------------------------
// Words
// ---------------------------------------------------------------------------

const WORDS = {
  en: {
    file: 'README.md',
    title: 'Godance open recipe list',
    languages: 'English · [中文](README_zh.md)',
    image: 'Image',
    video: 'Video',
    imageAnchor: '#image',
    videoAnchor: '#video',
    contents: 'Contents',
    statistics: 'Statistics',
    licence: 'Licence',
    removal: 'Removal',
    prompt: 'Prompt',
    langName: { en: 'English', zh: 'Chinese' },
    viewRecipe: 'View recipe',
    runThis: 'Run this',
    getKey: 'Get an API key',
    role: { image: 'Image', first_frame: 'First frame', last_frame: 'Last frame', reference_image: 'Reference image' },
  },
  zh: {
    file: 'README_zh.md',
    title: 'Godance 开放配方清单',
    languages: '[English](README.md) · 中文',
    image: '图片',
    video: '视频',
    imageAnchor: '#图片',
    videoAnchor: '#视频',
    contents: '目录',
    statistics: '统计',
    licence: '许可',
    removal: '移除',
    prompt: '提示词',
    langName: { en: '英文', zh: '中文' },
    viewRecipe: '查看配方',
    runThis: '运行这个配方',
    getKey: '获取 API 密钥',
    role: { image: '图片', first_frame: '首帧', last_frame: '尾帧', reference_image: '参考图' },
  },
};

/** A recipe's title in a language: the Chinese one when it has one. @param {Recipe} recipe @param {Lang} lang */
const titleOf = (recipe, lang) => (lang === 'zh' ? recipe.title.zh ?? recipe.title.en : recipe.title.en);

/** The removal contact as a link: mailto for an address. @param {string} contact */
const contactLink = (contact) => link(text(contact), /^https?:/i.test(contact) ? contact : `mailto:${contact}`);

/** Models as inline code, joined: "`a`", "`a` and `b`", "`a`, `b` and `c`". @param {string[]} models @param {Lang} lang */
function modelList(models, lang) {
  const shown = models.map((model) => `<code>${html(model)}</code>`);
  if (shown.length === 1) return shown[0];
  return `${shown.slice(0, -1).join(lang === 'zh' ? '、' : ', ')}${lang === 'zh' ? '和' : ' and '}${shown.at(-1)}`;
}

// ---------------------------------------------------------------------------
// One entry
// ---------------------------------------------------------------------------

/**
 * The output's picture, linked to the recipe page, and for a video a line
 * that says what it is.
 * @param {Recipe} recipe @param {Settings} settings @param {Lang} lang
 */
function pictureOf(recipe, settings, lang) {
  const recipeUrl = linksOf(recipe, settings).recipe;
  const run = recipe.runs[0];
  const output = run.outputs[0];
  const still = stillOf(output);
  const title = titleOf(recipe, lang);
  const blocks = [];
  if (still !== null) {
    const alt = lang === 'zh' ? `${title}，由 ${run.model} 生成` : `${title}, generated with ${run.model}`;
    blocks.push(link(image(alt, mediaUrl(settings.mediaBaseUrl, still)), recipeUrl));
  }
  if (isVideoOutput(output)) {
    const size = `${output.width}×${output.height}`;
    blocks.push(lang === 'zh'
      ? `▶ 视频，${output.duration_seconds} 秒，${size}：${link('在配方页观看', recipeUrl)}`
      : `▶ Video, ${output.duration_seconds} s, ${size}: ${link('watch it on the recipe page', recipeUrl)}`);
  }
  return blocks;
}

/**
 * The prompt in the README's language, and the other language's prompt too
 * when a run sent that one, so every prompt a run sent is in each README.
 * @param {Recipe} recipe @param {Lang} lang
 */
function promptOf(recipe, lang) {
  const other = lang === 'zh' ? 'en' : 'zh';
  const machine = lang === 'zh' && recipe.prompt.zh_origin === 'machine';
  const blocks = [
    `**${WORDS[lang].prompt}**${machine ? '（机器翻译）' : ''}`,
    codeBlock(recipe.prompt[lang]),
  ];
  const sentOther = recipe.runs.filter((run) => run.prompt_lang === other).map((run) => run.model);
  if (sentOther.length > 0) {
    const models = modelList([...new Set(sentOther)], lang);
    const summary = lang === 'zh'
      ? `英文提示词：${models} 的运行发送的是这一版`
      : `The Chinese prompt, which the ${models} run${sentOther.length > 1 ? 's' : ''} sent${recipe.prompt.zh_origin === 'machine' ? ' (a machine translation)' : ''}`;
    blocks.push(`<details>\n<summary>${summary}</summary>\n\n${codeBlock(recipe.prompt[other])}\n\n</details>`);
  }
  return blocks;
}

/**
 * The template with its variables, or for a recipe published before
 * templates, the placeholders it had and the values used.
 * @param {Recipe} recipe @param {Lang} lang
 */
function templateOf(recipe, lang) {
  const { template } = recipe.prompt;
  const variables = recipe.prompt.variables ?? [];
  const zh = lang === 'zh';
  const shown = (/** @type {string} */ value) => (value === '' ? (zh ? '（空）' : '_(empty)_') : text(value));
  if (template) {
    return [
      zh
        ? '**模板**：可以把空白处换成你自己的内容。上面的提示词就是这个模板填入默认值后的结果。'
        : '**Template.** Fill the blanks with your own values. The prompt above is this template with its defaults.',
      codeBlock(template[lang]),
      table(
        zh ? ['变量', '默认值', '中文默认值'] : ['Variable', 'Default', 'Default in Chinese'],
        variables.map((variable) => [
          code(variable.name, { inTable: true }),
          shown(variable.default),
          variable.zh_default === undefined ? (zh ? '与默认值相同' : '_the same_') : shown(variable.zh_default),
        ]),
      ),
    ];
  }
  if (variables.length === 0) return [];
  return [
    zh
      ? '**变量**：原提示词是一个模板，下面是它的占位符和这里使用的值。'
      : '**Variables.** The original prompt is a template. These are its placeholders, with the values used here.',
    variables.map((variable) => `- ${code(variable.name)}${zh ? '：' : ': '}${shown(variable.default)}`).join('\n'),
  ];
}

/**
 * A recipe's input slots, each with its sample, and how the samples were made.
 * @param {Recipe} recipe @param {Settings} settings @param {Lang} lang
 */
function inputsOf(recipe, settings, lang) {
  const slots = recipe.inputs ?? [];
  if (slots.length === 0) return [];
  const zh = lang === 'zh';
  const one = slots.length === 1;
  const intro = zh
    ? `**输入**：可以换成你自己的图片来运行这个配方。下面的示例图就是这些运行所用的输入。`
    : `**Input${one ? '' : 's'}.** Run this recipe with ${one ? 'an image' : 'images'} of your own in ${one ? 'its slot' : 'its slots'}. The ${one ? 'sample below is the one' : 'samples below are the ones'} its runs used.`;
  const rows = slots.map((slot) => [
    code(slot.slot, { inTable: true }),
    WORDS[lang].role[slot.role],
    text(zh ? slot.hint.zh ?? slot.hint.en : slot.hint.en),
    `<img src="${attr(mediaUrl(settings.mediaBaseUrl, slot.sample.file))}" alt="${attr(zh ? `示例输入：${slot.slot}` : `Sample input for ${slot.slot}`)}" width="160">`,
  ]);
  const made = slots.map((slot) => {
    const from = slot.sample.generated_from;
    const params = Object.entries(from.params).map(([key, value]) => code(`${key}=${formatParam(value)}`)).join(' · ');
    const facts = zh
      ? `${code(slot.slot)}：由 Godance 使用 ${code(from.model)}${params ? `（${params}）` : ''}于 ${from.generated_on} 生成，费用 ${text(formatCost(from.cost_cents))}，提示词如下：`
      : `${code(slot.slot)}: generated on Godance with ${code(from.model)}${params ? ` (${params})` : ''} for ${text(formatCost(from.cost_cents))} on ${from.generated_on}, from this prompt:`;
    return `${facts}\n\n${codeBlock(from.prompt)}`;
  });
  const summary = zh
    ? '示例输入是怎样生成的（单独计费，不含在运行费用中）'
    : `How the sample${one ? ' was' : 's were'} made (charged on ${one ? 'its' : 'their'} own, apart from the run)`;
  return [
    intro,
    table(zh ? ['位置', '用途', '应放入的内容', '示例'] : ['Slot', 'Role', 'What to put in it', 'Sample'], rows),
    `<details>\n<summary>${summary}</summary>\n\n${made.join('\n\n')}\n\n</details>`,
  ];
}

/**
 * Every run: its model, its parameters, what it cost, the prompt it sent and
 * when it was made.
 * @param {Recipe} recipe @param {Lang} lang
 */
function runsOf(recipe, lang) {
  const zh = lang === 'zh';
  const withInputs = (recipe.inputs?.length ?? 0) > 0;
  return table(
    zh ? ['模型', '参数', '费用', '发送的提示词', '生成日期'] : ['Model', 'Parameters', 'Cost', 'Prompt sent', 'Generated'],
    recipe.runs.map((run) => {
      const params = Object.entries(run.params).map(([key, value]) => code(`${key}=${formatParam(value)}`, { inTable: true }));
      const prompt = WORDS[lang].langName[run.prompt_lang];
      return [
        code(run.model, { inTable: true }),
        params.length > 0 ? params.join(' · ') : '—',
        text(formatCost(run.cost_cents)),
        withInputs ? (zh ? `${prompt}，加上示例输入` : `${prompt}, with the sample input${recipe.inputs?.length === 1 ? '' : 's'}`) : prompt,
        run.generated_on,
      ];
    }),
  );
}

/**
 * One recipe's entry: anchor and title, picture, tags, prompt, template,
 * inputs, runs, links and credit.
 * @param {Recipe} recipe @param {Settings} settings @param {Lang} lang
 */
function entryOf(recipe, settings, lang) {
  const words = WORDS[lang];
  const links = linksOf(recipe, settings);
  const actions = 'run_this' in links
    ? [`**${link(words.runThis, links.run_this)}**`, `**${link(words.viewRecipe, links.recipe)}**`]
    : [`**${link(words.viewRecipe, links.recipe)}**`, `**${link(words.getKey, links.get_api_key)}**`];
  const tags = recipe.tags.map((tag) => code(tag)).join(' · ');
  return [
    // The slug as the entry's anchor, the same in both languages, in the
    // form GitHub documents for a custom anchor: <a name="…"></a>.
    `### <a name="${recipe.slug}"></a>${text(titleOf(recipe, lang))}`,
    ...pictureOf(recipe, settings, lang),
    ...(tags ? [`${lang === 'zh' ? '标签：' : 'Tags: '}${tags}`] : []),
    ...promptOf(recipe, lang),
    ...templateOf(recipe, lang),
    ...inputsOf(recipe, settings, lang),
    runsOf(recipe, lang),
    actions.join(' · '),
    creditMarkdown(recipe, lang),
  ].join('\n\n');
}

// ---------------------------------------------------------------------------
// The READMEs
// ---------------------------------------------------------------------------

/** @param {Recipe[]} recipes @param {Settings} settings @param {Lang} lang */
function readme(recipes, settings, lang) {
  const zh = lang === 'zh';
  const words = WORDS[lang];
  const images = recipes.filter((recipe) => recipe.modality === 'IMAGE');
  const videos = recipes.filter((recipe) => recipe.modality === 'VIDEO');
  const models = tally(recipes, (recipe) => recipe.runs.map((run) => run.model));
  const tags = tally(recipes, (recipe) => recipe.tags);
  const licences = tally(recipes, (recipe) => [recipe.source.licence]);
  const runs = recipes.reduce((sum, recipe) => sum + recipe.runs.length, 0);
  const spent = recipes.reduce((sum, recipe) => sum + recipe.runs.reduce((cents, run) => cents + run.cost_cents, 0), 0);
  const updated = lastUpdated(recipes) ?? '—';
  const gallery = `${settings.site}/gallery?${tracking('readme')}`;
  const pricing = `${settings.site}/pricing?${tracking('readme')}`;
  const sections = /** @type {{ heading: string, anchor: string, recipes: Recipe[] }[]} */ ([
    { heading: words.image, anchor: words.imageAnchor, recipes: images },
    { heading: words.video, anchor: words.videoAnchor, recipes: videos },
  ]).filter((section) => section.recipes.length > 0);
  const entryLink = (/** @type {Recipe} */ recipe) => link(text(titleOf(recipe, lang)), `#${recipe.slug}`);
  const count = (/** @type {number} */ n) => (zh ? `（${n}）` : ` (${n})`);

  const head = zh
    ? [
        `这里的提示词都来自开放许可的提示词清单，并且每一个都通过 API 在 ${link('Godance', gallery)} 上实际运行过。与其他清单相比：`,
        [
          '- **可复现。** 每个条目都给出生成该结果的请求所用的模型和参数。',
          `- **明码标价。** 每次运行都写明它的实际花费，即生成当天的扣费（${link('当前价格', pricing)}）。`,
          '- **多个模型。** 同一个提示词可以带有多个模型的运行结果。',
        ].join('\n'),
        '每个条目都链接到它的配方页，页面上有可以直接复现结果的 API 请求；每个条目也都注明了提示词的作者、原帖和来源清单。',
        `**共 ${recipes.length} 个配方** · ${models.length} 个模型 · 最后更新：${updated}`,
        '> 本文件由 Godance 图库自动生成，直接修改会在下次生成时被覆盖，详见 [CONTRIBUTING.md](CONTRIBUTING.md)。',
      ]
    : [
        `Prompts from open-licensed prompt lists, each one run through the API on ${link('Godance', gallery)}. What sets this list apart:`,
        [
          '- **Reproducible.** Every entry gives the model and the parameters of the request that made its output.',
          `- **Priced.** Every run states what it cost, as charged on the day it was generated (${link('current prices', pricing)}).`,
          '- **Multiple models.** A prompt can carry runs on more than one model.',
        ].join('\n'),
        "Each entry links to its recipe page, which has the API call that reproduces it, and credits the prompt's author, the original post and the list it came from.",
        `**${recipes.length} recipe${recipes.length === 1 ? '' : 's'}** · ${models.length} model${models.length === 1 ? '' : 's'} · last updated ${updated}`,
        '> This file is generated from the Godance gallery. Edits to it are overwritten: see [CONTRIBUTING.md](CONTRIBUTING.md).',
      ];

  const byTag = tags.map(({ value }) => {
    const tagged = recipes.filter((recipe) => recipe.tags.includes(value)).map(entryLink).join(' · ');
    return `- ${code(value)}${zh ? '：' : ': '}${tagged}`;
  });
  const contents = [
    `## ${words.contents}`,
    `**${zh ? '按类型：' : 'By type:'}** ${sections.map((section) => `${link(section.heading, section.anchor)}${count(section.recipes.length)}`).join(' · ')}`,
    ...(byTag.length > 0 ? [`**${zh ? '按标签：' : 'By tag:'}**`, byTag.join('\n')] : []),
    `**${zh ? '其他：' : 'Also:'}** ${[words.statistics, words.licence, words.removal].map((heading) => link(heading, `#${zh ? heading : heading.toLowerCase()}`)).join(' · ')}`,
  ];

  const entries = sections.flatMap((section) => [
    `## ${section.heading}`,
    ...section.recipes.map((recipe) => entryOf(recipe, settings, lang)),
  ]);

  const list = (/** @type {{ value: string, count: number }[]} */ items, /** @type {(value: string) => string} */ show) =>
    items.map(({ value, count: n }) => `${show(value)}${count(n)}`).join(' · ');
  const statistics = [
    `## ${words.statistics}`,
    (zh
      ? [
          `- **配方：** ${recipes.length} 个（图片 ${images.length} 个，视频 ${videos.length} 个）`,
          `- **运行：** ${runs} 次，共花费 ${text(formatCost(spent))}`,
          `- **模型（括号内为用到它的配方数）：** ${list(models, (model) => code(model))}`,
          `- **提示词许可：** ${list(licences, (licence) => link(text(licence), LICENCE_URLS[/** @type {keyof typeof LICENCE_URLS} */ (licence)]))}`,
          `- **最后更新：** ${updated}`,
        ]
      : [
          `- **Recipes:** ${recipes.length}, of which ${images.length} image and ${videos.length} video`,
          `- **Runs:** ${runs}, which cost ${text(formatCost(spent))} in all`,
          `- **Models**, with how many recipes have a run on each: ${list(models, (model) => code(model))}`,
          `- **Prompt licences:** ${list(licences, (licence) => link(text(licence), LICENCE_URLS[/** @type {keyof typeof LICENCE_URLS} */ (licence)]))}`,
          `- **Last updated:** ${updated}`,
        ]).join('\n'),
  ];

  const statement = settings.outputLicence.trim();
  const licence = [
    `## ${words.licence}`,
    zh
      ? `每个提示词沿用其来源清单的许可协议，各条目都注明并链接了该协议，并按协议要求署名。图片和视频由 Godance 生成，不存放在本仓库中，条目只提供链接。${statement ? `生成结果的使用条款：${text(statement)} ` : ''}详见 [LICENSE.md](LICENSE.md)，其中也说明了 Godance 自己撰写的文字的许可。`
      : `Each prompt keeps the licence of the list it came from: its entry names and links it, and gives the credit it asks for. The images and videos were generated by Godance and are not stored here; the entries link to them. ${statement ? `${text(statement)} ` : ''}See [LICENSE.md](LICENSE.md), which also covers Godance's own text in this list.`,
  ];

  const removal = [
    `## ${words.removal}`,
    zh
      ? `如果其中某个提示词是你的作品并希望移除，请写信至 ${contactLink(settings.removalContact)}，我们会将它从本清单和图库中下架。详见 [CONTRIBUTING.md](CONTRIBUTING.md)。`
      : `Is one of these prompts yours, and would you like it removed? Write to ${contactLink(settings.removalContact)} and we will take it down, here and in the gallery. See [CONTRIBUTING.md](CONTRIBUTING.md).`,
  ];

  return `${[
    '<!-- Generated from the Godance gallery by marketing/scripts/open-list. Do not edit: see CONTRIBUTING.md. -->',
    `# ${words.title}`,
    words.languages,
    ...head,
    ...contents,
    ...entries,
    ...statistics,
    ...licence,
    ...removal,
  ].join('\n\n')}\n`;
}

// ---------------------------------------------------------------------------
// The data, the licence and how to contribute
// ---------------------------------------------------------------------------

/** One output, by the absolute URLs of its files. @param {Output} output @param {Settings} settings */
function outputData(output, settings) {
  const media = (/** @type {string} */ file) => mediaUrl(settings.mediaBaseUrl, file);
  if (isVideoOutput(output)) {
    return {
      url: media(output.file),
      ...(output.poster ? { poster: media(output.poster) } : {}),
      content_type: output.content_type,
      duration_seconds: output.duration_seconds,
      width: output.width,
      height: output.height,
    };
  }
  return { url: media(output.file), thumbnail: media(output.thumb), content_type: output.content_type, width: output.width, height: output.height };
}

/** One recipe as data: what its entry shows, in full. @param {Recipe} recipe @param {Settings} settings */
function recipeData(recipe, settings) {
  const still = stillOf(recipe.runs[0].outputs[0]);
  const { prompt } = recipe;
  return {
    slug: recipe.slug,
    title: recipe.title.zh === undefined ? { en: recipe.title.en } : { en: recipe.title.en, zh: recipe.title.zh },
    modality: recipe.modality,
    tags: [...recipe.tags],
    published_on: recipe.published_on,
    links: linksOf(recipe, settings),
    thumbnail: still === null ? null : mediaUrl(settings.mediaBaseUrl, still),
    prompt: {
      en: prompt.en,
      zh: prompt.zh,
      zh_origin: prompt.zh_origin,
      ...(prompt.template ? { template: { en: prompt.template.en, zh: prompt.template.zh } } : {}),
      ...(prompt.variables
        ? {
            variables: prompt.variables.map((variable) => ({
              name: variable.name,
              default: variable.default,
              ...(variable.zh_default === undefined ? {} : { zh_default: variable.zh_default }),
            })),
          }
        : {}),
    },
    ...(recipe.inputs
      ? {
          inputs: recipe.inputs.map((slot) => ({
            slot: slot.slot,
            role: slot.role,
            hint: slot.hint.zh === undefined ? { en: slot.hint.en } : { en: slot.hint.en, zh: slot.hint.zh },
            sample: {
              url: mediaUrl(settings.mediaBaseUrl, slot.sample.file),
              content_type: slot.sample.content_type,
              width: slot.sample.width,
              height: slot.sample.height,
              generated_from: {
                model: slot.sample.generated_from.model,
                prompt: slot.sample.generated_from.prompt,
                params: slot.sample.generated_from.params,
                cost_cents: slot.sample.generated_from.cost_cents,
                generated_on: slot.sample.generated_from.generated_on,
              },
            },
          })),
        }
      : {}),
    runs: recipe.runs.map((run) => ({
      model: run.model,
      prompt_lang: run.prompt_lang,
      params: run.params,
      cost_cents: run.cost_cents,
      generated_on: run.generated_on,
      outputs: run.outputs.map((output) => outputData(output, settings)),
    })),
    credit: creditOf(recipe),
  };
}

/** @param {Recipe[]} recipes @param {Settings} settings */
function dataJson(recipes, settings) {
  const data = {
    name: 'Godance open recipe list',
    gallery: `${settings.site}/gallery`,
    last_updated: lastUpdated(recipes),
    licences: {
      prompts: "Each prompt keeps its source list's licence: see each recipe's credit.",
      outputs: settings.outputLicence.trim() || null,
    },
    removal_contact: settings.removalContact,
    recipes: recipes.map((recipe) => recipeData(recipe, settings)),
  };
  return `${JSON.stringify(data, null, 2)}\n`;
}

/** @param {Recipe[]} recipes @param {Settings} settings */
function licenceMd(recipes, settings) {
  const licences = tally(recipes, (recipe) => [recipe.source.licence]);
  const statement = settings.outputLicence.trim();
  return `${[
    '<!-- Generated from the Godance gallery by marketing/scripts/open-list. Do not edit: see CONTRIBUTING.md. -->',
    '# Licence',
    'This list is generated from the Godance gallery. It holds three kinds of material, each under its own terms.',
    '## The prompts',
    "Each prompt keeps the licence of the list it was taken from. Its entry, in README.md, README_zh.md and data/recipes.json, names that licence and links its text, and credits the prompt's author, the original post and the list, with what was changed from the original.",
    licences.length > 0
      ? table(['Licence', 'Prompts'], licences.map(({ value, count }) => [
          link(text(value), LICENCE_URLS[/** @type {keyof typeof LICENCE_URLS} */ (value)]),
          String(count),
        ]))
      : '_There are no prompts in the list yet._',
    '## The images and videos',
    'The images and videos were generated on Godance from those prompts. None of them is stored in this repository: each entry links to its files on the media host.',
    statement
      ? text(statement)
      : '> **TO BE FILLED IN BY THE OPERATOR: the terms for the generated images and videos** (`outputLicence` in marketing/src/lib/gallery-config.ts, which the recipe pages show too).',
    "## Godance's own text",
    "> **TO BE FILLED IN BY THE OPERATOR: the licence for Godance's own text in this list**: the introductions, headings and layout of README.md and README_zh.md, this file and CONTRIBUTING.md, and the shape of data/recipes.json. The prompts and the outputs keep their own terms, whatever is chosen here.",
  ].join('\n\n')}\n`;
}

/** @param {Settings} settings */
function contributingMd(settings) {
  const contact = contactLink(settings.removalContact);
  return `${[
    '<!-- Generated from the Godance gallery by marketing/scripts/open-list. Do not edit: see below. -->',
    '# Contributing',
    "This list is generated by a script from the Godance gallery, and published again each time the gallery changes. Every run rewrites the list, so pull requests that edit entries are not merged: the next run would undo them.",
    '## Report a problem',
    'Open an issue for a credit that is wrong or missing, a broken link, a model, parameter or cost that does not match the recipe page, or a translation that reads badly. Name the recipe by its slug: the part of its link after `#`.',
    '## Ask for a recipe to be removed',
    `If you wrote one of these prompts and want it taken down, or you believe a recipe should not be here, write to ${contact}. It is removed from the gallery and from this list, and its original post is recorded so that a later import cannot bring it back.`,
    '## 中文',
    `本清单由脚本根据 Godance 图库自动生成，图库每次更新都会重新生成并覆盖整个清单，因此修改条目的拉取请求不会被合并。发现问题请提交 issue，并写明配方的 slug（即条目链接中 \`#\` 后面的部分）。如果某个提示词是你的作品并希望移除，或你认为某个配方不应出现在这里，请写信至 ${contact}。我们会将它从图库和本清单中移除，并记录其原帖，以免以后再次导入。`,
  ].join('\n\n')}\n`;
}

/**
 * The whole list. `recipes` are the published recipes to list, already
 * without those on the removal list; they are put in the gallery's order here.
 *
 * @param {Recipe[]} recipes
 * @param {Settings} settings
 * @returns {Map<string, string>} Each of FILES, and its contents.
 */
export function buildList(recipes, settings) {
  const ordered = [...recipes].sort(galleryOrder);
  return new Map([
    ['README.md', readme(ordered, settings, 'en')],
    ['README_zh.md', readme(ordered, settings, 'zh')],
    ['data/recipes.json', dataJson(ordered, settings)],
    ['LICENSE.md', licenceMd(ordered, settings)],
    ['CONTRIBUTING.md', contributingMd(settings)],
  ]);
}
