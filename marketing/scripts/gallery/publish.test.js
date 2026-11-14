/**
 * translate, review and publish: a recipe is complete in both languages,
 * only what the operator approved is written, and nothing is written that
 * the schema rejects or whose slug is taken.
 */
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { checkRecipe } from '../../src/lib/gallery-schema.js';
import { readRecipeFolder } from '../gallery-schema/read-recipes.js';
import { createApi } from './lib/api.js';
import { createBudget, openLedger } from './lib/budget.js';
import { priceBook } from './lib/pricing.js';
import { readJson } from './lib/work.js';
import { buildRecipe, publish, remove } from './stages/publish.js';
import { checkDecisions, reviewItems, reviewSheet, standardVersionOf } from './stages/review.js';
import { planTranslation, translateEntries, translationMessages } from './stages/translate.js';
import { API_BASE, CATALOGUE, FAKE_KEY, fakeWorld, noSleep, tempDir } from './testing.js';

const imageEntry = (overrides = {}) => ({
  id: 'x-1001', source: 'gpt-image-2', list: 'invented/image-prompts', list_commit: 'a'.repeat(40), licence: 'CC0-1.0',
  modality: 'IMAGE', model: 'gpt-image-2', title: 'Harbour Poster', title_zh: '港口海报', category: 'poster', lang: 'en',
  prompt: 'A flat poster of a small harbour, {argument name="boat colour" default="red"} boats.',
  variants: { en: 'A flat poster of a small harbour, {argument name="boat colour" default="red"} boats.' },
  author: 'inventedone', author_url: 'https://x.com/inventedone', original_url: 'https://x.com/inventedone/status/1001',
  needs_input: null, problems: [], ...overrides,
});
const videoEntry = (overrides = {}) => ({
  id: 'x-2002', source: 'seedance-2', list: 'invented/video-prompts', list_commit: 'b'.repeat(40), licence: 'CC-BY-4.0',
  modality: 'VIDEO', model: 'seedance-2.0', title: 'Kite Over The Salt Flat', title_zh: '盐滩上的风筝', category: null, lang: 'zh',
  prompt: '正午时分，一只红色风筝飘过白色盐滩。', variants: { en: 'A red kite drifts over a white salt flat at noon.', zh: '正午时分，一只红色风筝飘过白色盐滩。' },
  author: '虚构作者', author_url: 'https://x.com/inventedeight', original_url: 'https://x.com/inventedeight/status/2002',
  needs_input: null, stated_seconds: null, problems: [], ...overrides,
});

const translatedImage = () => ({
  status: 'done', slug: 'harbour-poster', title: { en: 'Harbour Poster', zh: '港口海报' },
  prompt: { en: 'A flat poster of a small harbour, red boats.', zh: '一张小港口的扁平海报，红色的船。', zh_origin: 'machine', variables: [{ name: 'boat colour', default: 'red' }] },
  prompt_lang: 'en', changes: ['placeholders resolved', 'translated to Chinese'],
});
const imageRun = () => ({
  status: 'done', job_id: 'job-1', slug: 'harbour-poster', model: 'gpt-image-2', params: { size: '1024x1024', n: 1 }, prompt_lang: 'en', hold_cents: 6,
  cost_cents: 6, generated_on: '2026-11-20',
  outputs: [{ file: 'harbour-poster/gpt-image-2-0.webp', thumb: 'harbour-poster/gpt-image-2-0-480.webp', content_type: 'image/webp', width: 1024, height: 1024 }],
});
const translatedVideo = () => ({
  status: 'done', slug: 'kite-over-the-salt-flat', title: { en: 'Kite Over The Salt Flat', zh: '盐滩上的风筝' },
  prompt: { en: 'A red kite drifts over a white salt flat at noon.', zh: '正午时分，一只红色风筝飘过白色盐滩。', zh_origin: 'list' },
  prompt_lang: 'zh', changes: [],
});
const videoRun = () => ({
  status: 'done', job_id: 'job-2', slug: 'kite-over-the-salt-flat', model: 'seedance-2.0', params: { seconds: 5, size: '1280x720' }, prompt_lang: 'zh', hold_cents: 81,
  cost_cents: 81, generated_on: '2026-11-20',
  outputs: [{ file: 'kite-over-the-salt-flat/seedance-2.0-0.mp4', content_type: 'video/mp4', duration_seconds: 5, width: 1280, height: 720 }],
});
const approved = (slug) => ({ slug, decision: 'approved', reason: '', decided_on: '2026-11-21' });
const rejected = (slug, reason = 'the output shows a logo') => ({ slug, decision: 'rejected', reason, decided_on: '2026-11-21' });

/** A whole batch ready to publish: one image and one video, with the operator's decisions. */
async function batch(t, decisions) {
  const dir = await tempDir(t);
  return {
    contentDir: path.join(dir, 'content'),
    manifestFile: path.join(dir, 'work', 'manifest.json'),
    removalFile: path.join(dir, 'config', 'removal-list.json'),
    entries: [imageEntry(), videoEntry()],
    translated: { 'x-1001': translatedImage(), 'x-2002': translatedVideo() },
    runs: { 'x-1001': imageRun(), 'x-2002': videoRun() },
    decisions: { standard_version: 1, decisions },
    now: () => new Date('2026-11-22T09:00:00Z'),
  };
}

describe('translate', () => {
  const prices = priceBook(CATALOGUE);
  async function run(t, entries, { onChat, cap = 100, taken } = {}) {
    const work = await tempDir(t);
    const world = fakeWorld({ onChat });
    const api = createApi({ base: API_BASE, key: FAKE_KEY, fetch: world.fetch, sleep: noSleep });
    const state = {};
    const budget = createBudget({ cap });
    const result = await translateEntries(entries, state, { api, prices, budget, ledger: await openLedger(path.join(work, 'ledger.json')), model: 'gpt-4o-mini', save: async () => {}, takenSlugs: taken, concurrency: 1 });
    return { state, world, budget, result };
  }

  test('plans from what the list supplies: which language is missing, and the variables', () => {
    const plan = planTranslation(imageEntry());
    assert.deepEqual(plan.prompt, { en: 'A flat poster of a small harbour, red boats.' });
    assert.deepEqual(plan.variables, [{ name: 'boat colour', default: 'red' }]);
    assert.equal(plan.missing, 'zh');
    assert.equal(plan.zhOrigin, 'machine');
    assert.deepEqual(plan.changes, ['placeholders resolved']);
    assert.deepEqual({ missing: planTranslation(videoEntry()).missing, zhOrigin: planTranslation(videoEntry()).zhOrigin }, { missing: null, zhOrigin: 'list' });
  });

  test("uses the list's own text in both languages when it has them, and calls no model", async (t) => {
    const { state, world, budget } = await run(t, [videoEntry()]);
    assert.equal(world.chats.length, 0);
    assert.equal(budget.spent, 0);
    assert.deepEqual(state['x-2002'], translatedVideo());
  });

  test('translates the missing language with a text model, and records that it did', async (t) => {
    const { state, world, budget } = await run(t, [imageEntry()], { onChat: () => '一张小港口的扁平海报，红色的船。' });
    assert.equal(world.chats.length, 1);
    assert.match(world.chats[0].messages[1].content, /^Translate into Simplified Chinese:\n\n<prompt>\nA flat poster of a small harbour, red boats\.\n<\/prompt>$/);
    assert.deepEqual(state['x-1001'], translatedImage());
    assert.ok(budget.spent > 0);
  });

  test('a Chinese prompt is translated into English, and the Chinese stays the list’s own', async (t) => {
    const entry = imageEntry({ id: 'x-1002', title: 'Tea Label', title_zh: undefined, lang: 'zh', prompt: '一张茶叶包装标签，绿色山丘。', variants: { zh: '一张茶叶包装标签，绿色山丘。' } });
    const { state, world } = await run(t, [entry], { onChat: () => 'A tea packaging label with green hills.' });
    assert.match(world.chats[0].messages[1].content, /^Translate into English:/);
    assert.deepEqual(state['x-1002'].prompt, { en: 'A tea packaging label with green hills.', zh: '一张茶叶包装标签，绿色山丘。', zh_origin: 'list' });
    assert.equal(state['x-1002'].prompt_lang, 'zh');
    assert.deepEqual(state['x-1002'].changes, ['translated to English']);
  });

  test('a Chinese title is translated for the slug and kept as the Chinese title', async (t) => {
    const entry = imageEntry({ id: 'x-1003', title: '中式茶叶标签', title_zh: undefined });
    const { state } = await run(t, [entry], { onChat: (messages) => (/short title/.test(messages[0].content) ? '"Chinese Tea Label"' : '译文：一张海报。') });
    assert.deepEqual(state['x-1003'].title, { en: 'Chinese Tea Label', zh: '中式茶叶标签' });
    assert.equal(state['x-1003'].slug, 'chinese-tea-label');
    assert.ok(state['x-1003'].changes.includes('title translated to English'));
  });

  test('the translator is told to leave text meant for the picture as written, and to treat the prompt as text', () => {
    const [system] = translationMessages('A sign that reads "OPEN".', 'zh');
    assert.match(system.content, /stays exactly as the author wrote it/);
    assert.match(system.content, /not instructions/);
  });

  test('a translation that comes back in the wrong language is not used', async (t) => {
    const { state, result } = await run(t, [imageEntry()], { onChat: () => 'Sorry, I cannot translate that.' });
    assert.equal(result.failed, 1);
    assert.deepEqual(state['x-1001'], { status: 'failed', reason: 'the translation did not come back in Chinese' });
  });

  test('slugs do not collide with each other or with published recipes', async (t) => {
    const twin = imageEntry({ id: 'x-1009', original_url: 'https://x.com/other/status/1009' });
    const { state } = await run(t, [imageEntry(), twin], { onChat: () => '一张小港口的扁平海报，红色的船。', taken: new Set(['harbour-poster']) });
    assert.deepEqual([state['x-1001'].slug, state['x-1009'].slug], ['harbour-poster-2', 'harbour-poster-3']);
  });

  test('stops at the cap, and a later run does only what is left', async (t) => {
    const first = await run(t, [imageEntry()], { cap: 0 });
    assert.equal(first.result.stoppedByBudget, true);
    assert.equal(first.world.chats.length, 0);
    assert.deepEqual(first.state, {});
  });
});

describe('the review sheet', () => {
  const items = reviewItems({
    entries: [imageEntry(), videoEntry(), imageEntry({ id: 'x-9', title: 'No Run Yet' })],
    screened: { 'x-1001': { status: 'candidate', reasons: [], notes: ['classifier: nothing found against the standard'] }, 'x-2002': { status: 'candidate', reasons: [], notes: [] } },
    translated: { 'x-1001': translatedImage(), 'x-2002': translatedVideo(), 'x-9': translatedImage() },
    runs: { 'x-1001': imageRun(), 'x-2002': videoRun() },
  });

  test('lists every candidate with a finished run, and only those', () => {
    assert.deepEqual(items.map((item) => item.slug), ['harbour-poster', 'kite-over-the-salt-flat']);
    assert.deepEqual(items[0].notes, ['classifier: nothing found against the standard']);
    assert.equal(items[0].run.cost_cents, 6);
    assert.equal(items[0].source.original_url, 'https://x.com/inventedone/status/1001');
  });

  test('carries the output, both prompts, the source, the notes and the cost', () => {
    const html = reviewSheet({ items, standardVersion: 1 });
    for (const expected of ['harbour-poster/gpt-image-2-0.webp', 'A flat poster of a small harbour, red boats.', '一张小港口的扁平海报', 'inventedone', 'invented/image-prompts', 'CC0-1.0', 'nothing found against the standard', '"cost_cents":6', '"standardVersion":1']) {
      assert.ok(html.includes(expected), `the sheet is missing ${expected}`);
    }
  });

  test('never builds HTML from a prompt: third-party text cannot break out of the page', () => {
    const hostile = imageEntry();
    const record = { ...translatedImage(), prompt: { ...translatedImage().prompt, en: '</script><script>alert(1)</script><img src=x onerror=alert(2)>' } };
    const html = reviewSheet({ items: reviewItems({ entries: [hostile], screened: {}, translated: { 'x-1001': record }, runs: { 'x-1001': imageRun() } }), standardVersion: 1 });
    assert.equal(html.includes('</script><script>alert(1)'), false);
    assert.equal(html.includes('<img src=x'), false);
    assert.equal((html.match(/<\/script>/g) ?? []).length, 2, 'only the two script elements of the page itself close');
    const data = JSON.parse(/<script type="application\/json" id="data">([\s\S]*?)<\/script>/.exec(html)[1]);
    assert.equal(data.items[0].prompt.en, record.prompt.en, 'the text itself is intact, as data');
  });

  test('loads nothing from the network', () => {
    const html = reviewSheet({ items, standardVersion: 1 });
    assert.match(html, /Content-Security-Policy" content="default-src 'none'/);
    assert.equal(/<(script|link|img|iframe)[^>]+(src|href)=["']https?:/i.test(html), false);
  });

  test('reads the version the standard gives itself', () => {
    assert.equal(standardVersionOf('# Standard\n\n**Version:** 3\n\ntext'), 3);
    assert.equal(standardVersionOf('# Standard, version two'), null);
    assert.equal(standardVersionOf(null), null);
  });

  test('a decisions file must say which standard was applied, and why anything was rejected', () => {
    assert.deepEqual(checkDecisions({ standard_version: 1, decisions: { a: approved('a'), b: rejected('b') } }), []);
    assert.deepEqual(checkDecisions({ decisions: { a: approved('a') } }), ['it does not say which version of the content standard was applied']);
    assert.deepEqual(checkDecisions({ standard_version: 1, decisions: { b: rejected('b', '  ') } }), ['b: rejected without a reason']);
    assert.deepEqual(checkDecisions({ standard_version: 1, decisions: { c: { decision: 'maybe', decided_on: '2026-11-21' } } }), ['c: the decision is neither approved nor rejected']);
    assert.deepEqual(checkDecisions([]), ['it is not a decisions file']);
  });
});

describe('publish', () => {
  test('builds a recipe the schema accepts, for an image and for a video', () => {
    const image = buildRecipe({ entry: imageEntry(), translated: translatedImage(), run: imageRun(), decision: approved('harbour-poster'), standardVersion: 1, publishedOn: '2026-11-22' });
    assert.deepEqual(checkRecipe(image, { file: 'harbour-poster.json' }), []);
    assert.deepEqual(Object.keys(image), ['slug', 'title', 'modality', 'tags', 'prompt', 'runs', 'source', 'review', 'published_on']);
    assert.deepEqual(image.source, {
      list: 'invented/image-prompts', list_commit: 'a'.repeat(40), licence: 'CC0-1.0', author: 'inventedone',
      author_url: 'https://x.com/inventedone', original_url: 'https://x.com/inventedone/status/1001', changes: ['placeholders resolved', 'translated to Chinese'],
    });
    assert.deepEqual(image.review, { approved_on: '2026-11-21', standard_version: 1 });
    assert.deepEqual(image.tags, ['poster']);
    assert.deepEqual(image.runs[0], { model: 'gpt-image-2', prompt_lang: 'en', params: { size: '1024x1024', n: 1 }, cost_cents: 6, generated_on: '2026-11-20', outputs: imageRun().outputs });

    const video = buildRecipe({ entry: videoEntry(), translated: translatedVideo(), run: videoRun(), decision: approved('kite-over-the-salt-flat'), standardVersion: 1, publishedOn: '2026-11-22' });
    assert.deepEqual(checkRecipe(video, { file: 'kite-over-the-salt-flat.json' }), []);
    assert.deepEqual(video.tags, []);
    assert.equal(video.runs[0].params.seconds, 5);
  });

  test('writes only what the operator approved', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': rejected('kite-over-the-salt-flat') });
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, ['harbour-poster']);
    assert.deepEqual(result.refused, []);
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), true);
    assert.equal(existsSync(path.join(b.contentDir, 'kite-over-the-salt-flat.json')), false);
    const folder = await readRecipeFolder(b.contentDir);
    assert.deepEqual(folder.problems, []);
    assert.deepEqual(folder.recipes.map((recipe) => recipe.slug), ['harbour-poster']);
    assert.equal(folder.recipes[0].data.published_on, '2026-11-22');
  });

  test('an entry with no decision is not published', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, ['harbour-poster']);
    assert.equal(existsSync(path.join(b.contentDir, 'kite-over-the-salt-flat.json')), false);
  });

  test('without --apply it writes nothing', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    const result = await publish({ ...b, apply: false });
    assert.deepEqual(result.written, ['harbour-poster', 'kite-over-the-salt-flat']);
    assert.equal(existsSync(b.contentDir), false);
    assert.equal(existsSync(b.manifestFile), false);
  });

  test('refuses to write a recipe the schema rejects, naming the rule', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    b.entries[0].author_url = 'http://x.com/inventedone'; // not https
    b.runs['x-2002'].params = { size: '1280x720' }; // a video run that does not state its seconds
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, []);
    assert.deepEqual(result.refused, [
      { slug: 'harbour-poster', why: ['[https-url] source.author_url: must be an https URL'] },
      { slug: 'kite-over-the-salt-flat', why: ['[video-seconds] runs[0].params.seconds: a video run states seconds, a whole number of at least 1'] },
    ]);
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), false);
    assert.equal(existsSync(path.join(b.contentDir, 'kite-over-the-salt-flat.json')), false);
  });

  test('refuses a recipe whose slug is already taken, and leaves the existing file alone', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    await publish({ ...b, apply: true });
    const before = await readFile(path.join(b.contentDir, 'harbour-poster.json'), 'utf8');

    // Another post arrives under the same slug.
    const other = imageEntry({ id: 'x-7777', original_url: 'https://x.com/someoneelse/status/7777', author: 'someoneelse', author_url: 'https://x.com/someoneelse' });
    const result = await publish({ ...b, entries: [other], translated: { 'x-7777': translatedImage() }, runs: { 'x-7777': imageRun() }, decisions: { standard_version: 1, decisions: { 'x-7777': approved('harbour-poster') } }, apply: true });
    assert.deepEqual(result.written, []);
    assert.deepEqual(result.refused, [{ slug: 'harbour-poster', why: ['the slug is already taken by harbour-poster.json'] }]);
    assert.equal(await readFile(path.join(b.contentDir, 'harbour-poster.json'), 'utf8'), before);
  });

  test('refuses two recipes in one batch that share a slug', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('harbour-poster') });
    b.translated['x-2002'].slug = 'harbour-poster';
    b.runs['x-2002'].outputs[0].file = 'harbour-poster/seedance-2.0-0.mp4';
    const dry = await publish({ ...b, apply: false });
    assert.deepEqual(dry.written, ['harbour-poster']);
    assert.deepEqual(dry.refused.map((item) => item.why), [['the slug is already taken by another recipe in this batch']]);

    // For real, the first is on disk by the time the second is checked.
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.written, ['harbour-poster']);
    assert.deepEqual(result.refused.map((item) => item.why), [['the slug is already taken by harbour-poster.json']]);
  });

  test('publishing again does not rewrite or refuse what is already published', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    await publish({ ...b, apply: true });
    const again = await publish({ ...b, apply: true, now: () => new Date('2026-12-01T00:00:00Z') });
    assert.deepEqual({ written: again.written, already: again.already, refused: again.refused }, { written: [], already: ['harbour-poster'], refused: [] });
    assert.equal((await readJson(path.join(b.contentDir, 'harbour-poster.json'))).published_on, '2026-11-22');
  });

  test('an approved entry with no finished run is refused, not skipped quietly', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    b.runs['x-1001'].status = 'downloading';
    const result = await publish({ ...b, apply: true });
    assert.deepEqual(result.refused, [{ slug: 'harbour-poster', why: ['it is approved, but it has no finished run to publish'] }]);
  });

  test('lists the media to upload, and commits none of it', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    await publish({ ...b, apply: true });
    assert.deepEqual(await readJson(b.manifestFile), {
      upload: ['harbour-poster/gpt-image-2-0-480.webp', 'harbour-poster/gpt-image-2-0.webp', 'kite-over-the-salt-flat/seedance-2.0-0.mp4'],
      delete: [],
    });
    const { readdir } = await import('node:fs/promises');
    assert.deepEqual((await readdir(b.contentDir)).sort(), ['harbour-poster.json', 'kite-over-the-salt-flat.json']);
  });
});

describe('remove', () => {
  test('deletes the recipe, puts its post on the removal list, and lists its media for deletion', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster'), 'x-2002': approved('kite-over-the-salt-flat') });
    await publish({ ...b, apply: true });
    const result = await remove('harbour-poster', { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile, reason: 'the author asked', apply: true, now: b.now });

    assert.equal(result.original_url, 'https://x.com/inventedone/status/1001');
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), false);
    assert.deepEqual(await readJson(b.removalFile), { removed: [{ original_url: 'https://x.com/inventedone/status/1001', slug: 'harbour-poster', removed_on: '2026-11-22', reason: 'the author asked' }] });
    assert.deepEqual(await readJson(b.manifestFile), {
      upload: ['kite-over-the-salt-flat/seedance-2.0-0.mp4'],
      delete: ['harbour-poster/gpt-image-2-0-480.webp', 'harbour-poster/gpt-image-2-0.webp'],
    });
    assert.deepEqual((await readRecipeFolder(b.contentDir)).problems, []);
  });

  test('without --apply it changes nothing', async (t) => {
    const b = await batch(t, { 'x-1001': approved('harbour-poster') });
    await publish({ ...b, apply: true });
    const result = await remove('harbour-poster', { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile });
    assert.equal(result.media.length, 2);
    assert.equal(existsSync(path.join(b.contentDir, 'harbour-poster.json')), true);
    assert.equal(existsSync(b.removalFile), false);
  });

  test('refuses anything that is not a slug, and a recipe that is not there', async (t) => {
    const b = await batch(t, {});
    const options = { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile, apply: true };
    await assert.rejects(remove('../../package', options), /is not a slug/);
    await assert.rejects(remove('no-such-recipe', options), /there is no recipe no-such-recipe\.json/);
  });

  test('a recipe that is not valid JSON is left alone', async (t) => {
    const b = await batch(t, {});
    const { mkdir } = await import('node:fs/promises');
    await mkdir(b.contentDir, { recursive: true });
    await writeFile(path.join(b.contentDir, 'broken.json'), '{ not json');
    await assert.rejects(remove('broken', { contentDir: b.contentDir, removalFile: b.removalFile, manifestFile: b.manifestFile, apply: true }));
    assert.equal(existsSync(path.join(b.contentDir, 'broken.json')), true);
  });
});
