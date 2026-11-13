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
