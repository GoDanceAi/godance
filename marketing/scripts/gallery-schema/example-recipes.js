/**
 * Invented recipes for the tests in this folder. Each call returns a fresh
 * copy, so a test can break one without affecting the next.
 */
import { mkdtemp, mkdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

/** The example from the round-8 coordination notes, key for key. */
export const imageRecipe = () => ({
  slug: 'paper-lantern-street-at-dusk',
  title: { en: 'Paper lantern street at dusk', zh: '黄昏的纸灯笼街' },
  modality: 'IMAGE',
  tags: ['street', 'photography'],
  prompt: {
    en: '…',
    zh: '…',
    zh_origin: 'machine',
    variables: [{ name: 'lantern color', default: 'warm red' }],
  },
  runs: [
    {
      model: 'gpt-image-2',
      prompt_lang: 'en',
      params: { size: '1024x1024', n: 1 },
      cost_cents: 6,
      generated_on: '2026-11-20',
      outputs: [
        {
          file: 'paper-lantern-street-at-dusk/gpt-image-2-0.webp',
          thumb: 'paper-lantern-street-at-dusk/gpt-image-2-0-480.webp',
          content_type: 'image/webp',
          width: 1024,
          height: 1024,
        },
      ],
    },
  ],
  source: {
    list: 'Evolink-AI/awesome-gpt-image-2-API-Prompts',
    list_commit: '<commit sha the entry was read at>',
    licence: 'CC0-1.0',
    author: 'example_author',
    author_url: 'https://x.com/example_author',
    original_url: 'https://x.com/example_author/status/0000000000000000000',
    changes: ['placeholders resolved', 'translated to Chinese'],
  },
  review: { approved_on: '2026-11-21', standard_version: 1 },
  published_on: '2026-11-21',
});

/**
 * The same recipe with its template kept, as the round-10 coordination notes
 * show it: {{name}} markers in both languages, and a Chinese default.
 */
export const templatedRecipe = () => {
  const recipe = imageRecipe();
  recipe.prompt = {
    en: 'A warm red paper lantern over a narrow street at dusk, 35mm photo.',
    zh: '黄昏时分，狭窄街道上方一盏暖红色的纸灯笼，35毫米胶片照片。',
    zh_origin: 'machine',
    template: {
      en: 'A {{lantern color}} paper lantern over a narrow street at dusk, {{lens}} photo.',
      zh: '黄昏时分，狭窄街道上方一盏{{lantern color}}的纸灯笼，{{lens}}胶片照片。',
    },
    variables: [
      { name: 'lantern color', default: 'warm red', zh_default: '暖红色' },
      // Not words: the Chinese prompt uses the same default, so it has no zh_default... except here,
      // where the Chinese writes the unit differently.
      { name: 'lens', default: '35mm', zh_default: '35毫米' },
    ],
  };
  recipe.source.changes = ['placeholders rewritten as {{name}}', 'translated to Chinese'];
  return recipe;
};

/** A video recipe in the same format: no Chinese title, no variables, a poster. */
export const videoRecipe = () => ({
  slug: 'kite-over-a-salt-flat',
  title: { en: 'Kite over a salt flat' },
  modality: 'VIDEO',
  tags: [],
  prompt: {
    en: 'A red kite drifting over a white salt flat at noon.',
    zh: '正午时分，一只红色风筝飘过白色盐滩。',
    zh_origin: 'list',
  },
  runs: [
    {
      model: 'seedance-2.0',
      prompt_lang: 'zh',
      params: { seconds: 5, size: '1280x720' },
      cost_cents: 80,
      generated_on: '2026-11-20',
      outputs: [
        {
          file: 'kite-over-a-salt-flat/seedance-2.0-0.mp4',
          poster: 'kite-over-a-salt-flat/seedance-2.0-0-poster.webp',
          content_type: 'video/mp4',
          duration_seconds: 5,
          width: 1280,
          height: 720,
        },
      ],
    },
  ],
  source: {
    list: 'example-org/example-video-prompts',
    list_commit: '0000000000000000000000000000000000000000',
    licence: 'CC-BY-4.0',
    author: 'another_author',
    author_url: 'https://x.com/another_author',
    original_url: 'https://x.com/another_author/status/1111111111111111111',
    changes: [],
  },
  review: { approved_on: '2026-11-21', standard_version: 1 },
  published_on: '2026-11-22',
});

/**
 * An image recipe with one input slot, as round 11 has them: the slot, a
 * sample input generated on Godance, and a run that says it used that sample.
 */
export const inputRecipe = () => {
  const recipe = imageRecipe();
  recipe.slug = 'product-on-a-marble-counter';
  recipe.title = { en: 'Product on a marble counter', zh: '大理石台面上的产品' };
  recipe.prompt = {
    en: 'Place the product from the uploaded photo on a marble counter, soft window light.',
    zh: '将上传照片中的产品放在大理石台面上，柔和的窗光。',
    zh_origin: 'machine',
  };
  recipe.inputs = [
    {
      slot: 'product photo',
      role: 'image',
      hint: { en: 'One product on a plain background', zh: '纯色背景上的一件产品' },
      sample: {
        file: 'product-on-a-marble-counter/input-0.webp',
        content_type: 'image/webp',
        width: 1024,
        height: 1024,
        generated_from: {
          model: 'gpt-image-2',
          prompt: 'A plain ceramic bottle on a white background, product photo.',
          params: { size: '1024x1024', n: 1 },
          cost_cents: 6,
          generated_on: '2026-11-19',
        },
      },
    },
  ];
  recipe.runs[0].inputs = { 'product photo': 'product-on-a-marble-counter/input-0.webp' };
  recipe.runs[0].outputs[0].file = 'product-on-a-marble-counter/gpt-image-2-0.webp';
  recipe.runs[0].outputs[0].thumb = 'product-on-a-marble-counter/gpt-image-2-0-480.webp';
  return recipe;
};

/** A video recipe that starts from a first frame. */
export const videoInputRecipe = () => {
  const recipe = videoRecipe();
  recipe.slug = 'paper-boat-sets-off';
  recipe.title = { en: 'Paper boat sets off' };
  recipe.inputs = [
    {
      slot: 'first frame',
      role: 'first_frame',
      hint: { en: 'The still the clip starts from' },
      sample: {
        file: 'paper-boat-sets-off/input-0.webp',
        content_type: 'image/webp',
        width: 1280,
        height: 720,
        generated_from: {
          model: 'gpt-image-2',
          prompt: 'A paper boat on a still pond, early morning.',
          params: { size: '1536x1024', n: 1 },
          cost_cents: 6,
          generated_on: '2026-11-19',
        },
      },
    },
  ];
  recipe.runs[0].inputs = { 'first frame': 'paper-boat-sets-off/input-0.webp' };
  return recipe;
};

/**
 * Make a temporary gallery folder holding `files`: a map of path (relative
 * to the folder) to contents, where an object is written as JSON and a
 * string as it is. Removed again when the test ends.
 *
 * @param {import('node:test').TestContext} t
 * @param {Record<string, unknown>} [files]
 */
export async function galleryFolder(t, files = {}) {
  const dir = await mkdtemp(path.join(os.tmpdir(), 'godance-gallery-'));
  t.after(() => rm(dir, { recursive: true, force: true }));
  for (const [name, contents] of Object.entries(files)) {
    const target = path.join(dir, name);
    await mkdir(path.dirname(target), { recursive: true });
    await writeFile(target, typeof contents === 'string' ? contents : JSON.stringify(contents, null, 2));
  }
  return dir;
}
