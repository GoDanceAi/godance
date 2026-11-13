/**
 * fetch: the two adapters against invented lists in the shape of the real
 * ones, the licence check, de-duplication and the removal list.
 */
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import * as imageAdapter from './adapters/gpt-image-2.js';
import * as videoAdapter from './adapters/seedance-2.js';
import { detectLicence } from './lib/licence.js';
import { postKey } from './lib/text.js';
import { createListReader, fetchEntries } from './stages/fetch.js';
import { CC0_TEXT, CC_BY_TEXT, IMAGE_LIST, SOURCES, VIDEO_LIST, fakeWorld, tempDir } from './testing.js';

const textFiles = (list, adapter) => Object.fromEntries(adapter.wanted(Object.keys(list)).map((file) => [file, list[file]]));
const lists = { 'invented/image-prompts': IMAGE_LIST, 'invented/video-prompts': VIDEO_LIST };

describe('the image list adapter', () => {
  const entries = imageAdapter.parse(textFiles(IMAGE_LIST, imageAdapter));
  const byTitle = (title) => entries.find((entry) => entry.title === title);

  test('reads only the case files: the English ones and their Chinese siblings', () => {
    assert.deepEqual(imageAdapter.wanted(Object.keys(IMAGE_LIST)), ['cases/poster.md', 'cases/poster_zh-CN.md']);
  });

  test('finds every case, in order', () => {
    assert.deepEqual(entries.map((entry) => entry.title), ['Harbour Poster', 'Tea Label', 'Broken One', 'Portrait From Photo', 'Harbour Poster Again', 'City Card', 'Lighthouse Print']);
  });

  test('reads a case whose heading links the post and the author', () => {
    const entry = byTitle('Harbour Poster');
    assert.equal(entry.original_url, 'https://x.com/inventedone/status/1001');
    assert.equal(entry.author, 'inventedone');
    assert.equal(entry.author_url, 'https://x.com/inventedone');
    assert.equal(entry.category, 'poster');
    assert.equal(entry.prompt, 'A flat poster of a small harbour at dawn, {argument name="boat colour" default="red"} boats, 3:4 ratio.');
    assert.equal(entry.lang, 'en');
    assert.deepEqual(entry.variants, { en: entry.prompt });
    assert.deepEqual(entry.problems, []);
  });

  test('reads a case that names its source on a line of its own', () => {
    const entry = byTitle('Tea Label');
    assert.equal(entry.original_url, 'https://x.com/inventedtwo/status/1002');
    assert.equal(entry.author, 'inventedtwo');
    assert.equal(entry.author_url, 'https://x.com/inventedtwo');
    assert.equal(entry.lang, 'zh');
    assert.deepEqual(Object.keys(entry.variants), ['zh']);
    assert.deepEqual(entry.problems, []);
  });

  test('takes the Chinese title from the Chinese sibling, and nothing else', () => {
    assert.equal(byTitle('Harbour Poster').title_zh, '港口海报');
    assert.equal(byTitle('Lighthouse Print').title_zh, undefined);
  });

  test('a case whose code block never closes is reported, and does not swallow the next case', () => {
    assert.deepEqual(byTitle('Broken One').problems, ['its code block never closes']);
    assert.equal(byTitle('Broken One').prompt, '');
    assert.equal(byTitle('Portrait From Photo').prompt, 'Turn the uploaded photo into a watercolour portrait.');
    assert.deepEqual(byTitle('Portrait From Photo').problems, []);
  });

  test('says when a prompt needs an input image', () => {
    assert.equal(byTitle('Portrait From Photo').needs_input, 'image');
    assert.equal(byTitle('Harbour Poster').needs_input, null);
  });

  test('a case with several code blocks, or none, is reported rather than guessed', () => {
    const [two, none] = imageAdapter.parse({
      'cases/ui.md': '### Case 1: [Two](https://x.com/a/status/1) (by [@a](https://x.com/a))\n```\none\n```\n```\ntwo\n```\n### Case 2: [None](https://x.com/b/status/2) (by [@b](https://x.com/b))\nno block here\n',
    });
    assert.deepEqual(two.problems, ['it has 2 code blocks']);
    assert.deepEqual(none.problems, ['it has no prompt block']);
  });

  test('a case with no link to its post is reported', () => {
    const [entry] = imageAdapter.parse({ 'cases/ui.md': '### Case 1: No Source\n```\na prompt\n```\n' });
    assert.deepEqual(entry.problems, ['it has no link to the original post']);
  });
});

describe('the video list adapter', () => {
  const entries = videoAdapter.parse(textFiles(VIDEO_LIST, videoAdapter));
  const byTitle = (title) => entries.find((entry) => entry.title === title);

  test('reads only the English and Chinese READMEs', () => {
    assert.deepEqual(videoAdapter.wanted(Object.keys(VIDEO_LIST)), ['README.md', 'README_zh.md']);
  });

  test('finds featured and plain entries, and nothing from other sections', () => {
    assert.deepEqual(entries.map((entry) => entry.title), ['Paper Boat In The Rain', 'Kite Over The Salt Flat', 'Dancer From The Reference', 'Lantern Festival']);
  });

  test('reads a featured entry: bulleted author, source and date', () => {
    const entry = byTitle('Paper Boat In The Rain');
    assert.equal(entry.author, 'Invented Seven');
    assert.equal(entry.author_url, 'https://x.com/inventedseven');
    assert.equal(entry.original_url, 'https://x.com/inventedseven/status/2001');
    assert.equal(entry.published, '2026-03-15');
    assert.deepEqual(entry.problems, []);
  });

  test('reads a plain entry: author, source and date on one line', () => {
    const entry = byTitle('Kite Over The Salt Flat');
    assert.equal(entry.author, '虚构作者');
    assert.equal(entry.original_url, 'https://x.com/inventedeight/status/2002');
    assert.equal(entry.published, '2026-11-12');
  });

  test('a heading inside a prompt is part of the prompt', () => {
    assert.equal(byTitle('Paper Boat In The Rain').prompt, 'A paper boat drifts down a rainy gutter, slow tracking shot.\n\n### Shot notes\nKeep the camera low.');
  });

  test('carries both languages, joined across the two files, and the Chinese title', () => {
    const entry = byTitle('Paper Boat In The Rain');
    assert.equal(entry.title_zh, '雨中的纸船');
    assert.match(entry.variants.zh, /^一只纸船/);
    assert.match(entry.variants.en, /^A paper boat/);
  });

  test("the prompt is the author's text, in the language the badge names", () => {
    assert.equal(byTitle('Paper Boat In The Rain').lang, 'en');
    assert.match(byTitle('Paper Boat In The Rain').prompt, /^A paper boat/);
    assert.equal(byTitle('Kite Over The Salt Flat').lang, 'zh');
    assert.match(byTitle('Kite Over The Salt Flat').prompt, /^正午时分/);
    assert.equal(byTitle('Lantern Festival').lang, 'other');
  });

  test('says when a prompt needs an input, and how long a clip it names', () => {
    assert.equal(byTitle('Dancer From The Reference').needs_input, 'image');
    assert.equal(byTitle('Dancer From The Reference').stated_seconds, 15);
    assert.equal(byTitle('Kite Over The Salt Flat').stated_seconds, 8);
    assert.equal(byTitle('Paper Boat In The Rain').stated_seconds, null);
  });

  test('reads both date forms and refuses anything else', () => {
    assert.equal(videoAdapter.isoDate('March 15, 2026'), '2026-03-15');
    assert.equal(videoAdapter.isoDate('Nov 12, 2026'), '2026-11-12');
    assert.equal(videoAdapter.isoDate('Feb 30, 2026'), null);
    assert.equal(videoAdapter.isoDate('2026年11月12日'), null);
  });
});

describe('the licence check', () => {
  test('recognises the licences a recipe may carry', () => {
    assert.equal(detectLicence(CC0_TEXT), 'CC0-1.0');
    assert.equal(detectLicence(CC_BY_TEXT), 'CC-BY-4.0');
    assert.equal(detectLicence('MIT License\n\nPermission is hereby granted, free of charge, to any person…'), 'MIT');
    assert.equal(detectLicence('Apache License\nVersion 2.0, January 2004'), 'Apache-2.0');
  });

  test('refuses a missing licence, an unknown one, and one that restricts reuse', () => {
    assert.equal(detectLicence(''), null);
    assert.equal(detectLicence(undefined), null);
    assert.equal(detectLicence('All rights reserved.'), null);
    assert.equal(detectLicence('Creative Commons Attribution-NonCommercial 4.0 International\nhttps://creativecommons.org/licenses/by-nc/4.0/'), null);
    assert.equal(detectLicence('Creative Commons Attribution 4.0 International … NoDerivatives'), null);
  });

  test('a list with no licence file is refused, and nothing is read from it', async () => {
    const { LICENSE: _removed, ...unlicensed } = IMAGE_LIST;
    const world = fakeWorld({ lists: { 'invented/image-prompts': unlicensed } });
    const result = await fetchEntries({ sources: [SOURCES[0]], reader: createListReader({ fetch: world.fetch }) });
    assert.equal(result.sources[0].refused, 'it has no licence file');
    assert.deepEqual(result.entries, []);
    assert.equal(world.requests.filter((request) => request.url.includes('/cases/')).length, 0);
  });

  test('a list under another licence than the one configured is refused', async () => {
    const world = fakeWorld({ lists: { 'invented/image-prompts': { ...IMAGE_LIST, LICENSE: CC_BY_TEXT } } });
    const result = await fetchEntries({ sources: [SOURCES[0]], reader: createListReader({ fetch: world.fetch }) });
    assert.match(result.sources[0].refused, /grants CC-BY-4\.0, not the CC0-1\.0/);
    assert.deepEqual(result.entries, []);
  });

  test('a source must be pinned to a full commit', async () => {
    const world = fakeWorld({ lists });
    await assert.rejects(
      fetchEntries({ sources: [{ ...SOURCES[0], commit: 'main' }], reader: createListReader({ fetch: world.fetch }) }),
      /must be pinned to a full 40-character commit/,
    );
  });
});

describe('fetch', () => {
  test('reads both lists, keyed by original post', async () => {
    const world = fakeWorld({ lists });
    const result = await fetchEntries({ sources: SOURCES, reader: createListReader({ fetch: world.fetch }) });
    assert.deepEqual(result.sources.map(({ id, read, kept, duplicates, removed, refused }) => ({ id, read, kept, duplicates, removed, refused })), [
      { id: 'gpt-image-2', read: 7, kept: 6, duplicates: 1, removed: 0, refused: null },
      { id: 'seedance-2', read: 4, kept: 4, duplicates: 0, removed: 0, refused: null },
    ]);
    const harbour = result.entries.find((entry) => entry.title === 'Harbour Poster');
    assert.equal(harbour.id, 'x-1001');
    assert.deepEqual(
      { source: harbour.source, list: harbour.list, list_commit: harbour.list_commit, licence: harbour.licence, modality: harbour.modality, model: harbour.model },
      { source: 'gpt-image-2', list: 'invented/image-prompts', list_commit: 'a'.repeat(40), licence: 'CC0-1.0', modality: 'IMAGE', model: 'gpt-image-2' },
    );
  });

  test('drops a post that is listed twice, whatever host or query its link has', async () => {
    const world = fakeWorld({ lists });
    const result = await fetchEntries({ sources: [SOURCES[0]], reader: createListReader({ fetch: world.fetch }) });
    assert.equal(result.entries.filter((entry) => entry.id === 'x-1001').length, 1);
    assert.deepEqual(result.dropped.map(({ reason, original_url }) => [reason, original_url]), [['duplicate', 'https://twitter.com/inventedone/status/1001?s=20']]);
  });

  test('drops a post another list already supplied', async () => {
    const copy = { ...VIDEO_LIST, 'README.md': VIDEO_LIST['README.md'].replace('inventedeight/status/2002', 'inventedone/status/1001') };
    const world = fakeWorld({ lists: { ...lists, 'invented/video-prompts': copy } });
    const result = await fetchEntries({ sources: SOURCES, reader: createListReader({ fetch: world.fetch }) });
    assert.equal(result.sources[1].duplicates, 1);
    assert.equal(result.entries.filter((entry) => entry.id === 'x-1001').length, 1);
    assert.equal(result.entries.find((entry) => entry.id === 'x-1001').source, 'gpt-image-2');
  });

  test('drops anything on the removal list', async () => {
    const world = fakeWorld({ lists });
    const result = await fetchEntries({
      sources: SOURCES,
      removed: [{ original_url: 'https://twitter.com/inventedsix/status/1006', slug: 'lighthouse-print', removed_on: '2026-11-13' }],
      reader: createListReader({ fetch: world.fetch }),
    });
    assert.equal(result.sources[0].removed, 1);
    assert.equal(result.entries.some((entry) => entry.title === 'Lighthouse Print'), false);
    assert.deepEqual(result.dropped.filter((item) => item.reason === 'removed').map((item) => item.original_url), ['https://x.com/inventedsix/status/1006']);
  });

  test("never asks for a list's images or videos", async () => {
    const world = fakeWorld({ lists });
    await fetchEntries({ sources: SOURCES, reader: createListReader({ fetch: world.fetch }) });
    const asked = world.requests.map((request) => request.url);
    assert.equal(asked.some((url) => /\.(jpe?g|png|gif|webp|mp4|mov|webm)(\?|$)/i.test(url)), false, asked.join('\n'));
    await assert.rejects(createListReader({ fetch: world.fetch }).text(SOURCES[0], 'images/poster_case1/output.jpg'), /only text files are read/);
  });

  test('reads from the cache on a later run, and writes it only when told to', async (t) => {
    const cacheDir = await tempDir(t);
    const first = fakeWorld({ lists });
    await fetchEntries({ sources: SOURCES, reader: createListReader({ fetch: first.fetch, cacheDir, save: false }) });
    const dry = fakeWorld({ lists });
    await fetchEntries({ sources: SOURCES, reader: createListReader({ fetch: dry.fetch, cacheDir, save: false }) });
    assert.ok(dry.requests.length > 0, 'a dry run saved nothing, so the next run asks again');

    await fetchEntries({ sources: SOURCES, reader: createListReader({ fetch: first.fetch, cacheDir, save: true }) });
    const later = fakeWorld({ lists });
    const result = await fetchEntries({ sources: SOURCES, reader: createListReader({ fetch: later.fetch, cacheDir, save: false }) });
    assert.equal(later.requests.length, 0);
    assert.equal(result.entries.length, 10);
  });
});

describe('the key for an original post', () => {
  test('a post on X is keyed by its status id', () => {
    assert.equal(postKey('https://x.com/someone/status/123'), 'x-123');
    assert.equal(postKey('https://twitter.com/Someone/status/123?s=20'), 'x-123');
    assert.equal(postKey('https://mobile.twitter.com/someone/status/123/photo/1'), 'x-123');
  });

  test('any other address is keyed by host and path', () => {
    assert.equal(postKey('https://example.com/Post/1/'), postKey('https://EXAMPLE.com/post/1?utm=x'));
    assert.notEqual(postKey('https://example.com/post/1'), postKey('https://example.com/post/2'));
    assert.equal(postKey('not a url'), null);
  });
});
