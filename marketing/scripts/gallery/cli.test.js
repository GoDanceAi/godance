/**
 * The command line, end to end, against invented lists and a mocked API:
 * a dry run writes nothing and pays for nothing, a paid stage needs a cap
 * and a key, the whole pipeline produces valid recipes from what the
 * operator approved, and the API key never reaches a file or the output.
 */
import assert from 'node:assert/strict';
import { existsSync } from 'node:fs';
import { mkdir, readFile, readdir, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { readRecipeFolder } from '../gallery-schema/read-recipes.js';
import { main, parseArgs, pickForGeneration } from './cli.js';
import { readJson } from './lib/work.js';
import { API_BASE, CC_BY_TEXT, FAKE_KEY, IMAGE_LIST, VIDEO_LIST, allFiles, configDir, fakeWorld, noSleep, tempDir } from './testing.js';

const lists = { 'invented/image-prompts': IMAGE_LIST, 'invented/video-prompts': VIDEO_LIST };

/** A reply for whatever the translate stage asks. */
const translator = (messages) =>
  /short title/.test(messages[0].content) ? 'Invented Title'
  : /Translate into English/.test(messages[1].content) ? 'A tea packaging label with green hills, minimal style, tall frame.'
  : '一段用于测试的中文译文，描述画面的内容与风格。';

/** A fresh work folder, config folder and content folder, and a way to run the tool in them. */
async function workspace(t, { world = fakeWorld({ lists, onChat: translator }), key = FAKE_KEY, config } = {}) {
  const root = await tempDir(t);
  const dirs = { work: path.join(root, 'work'), content: path.join(root, 'content'), config: await configDir(t, config) };
  const lines = [];
  const env = { GODANCE_API_BASE: API_BASE, ...(key ? { GODANCE_API_KEY: key } : {}) };
  const cli = async (...args) => {
    const from = lines.length;
    const code = await main([...args, '--work-dir', dirs.work, '--config-dir', dirs.config, '--content-dir', dirs.content], {
      env, fetch: world.fetch, out: (line) => lines.push(line), sleep: noSleep, now: () => new Date('2026-11-22T09:00:00Z'),
    });
    return { code, text: lines.slice(from).join('\n') };
  };
  const paidRequests = () => world.requests.filter((request) => request.method === 'POST');
  return { dirs, world, cli, lines, paidRequests };
}

describe('a dry run', () => {
  test('plan prints the count at each stage and the projected spend', async (t) => {
    const { cli } = await workspace(t);
    const { code, text } = await cli('plan');
    assert.equal(code, 0, text);
    for (const expected of [
      'gallery plan: dry run over every stage. Nothing is written and no paid call is made.',
      `prices: the live catalogue at ${API_BASE}`,
      'source gpt-image-2  invented/image-prompts @ aaaaaaaaaaaa  (CC0-1.0)',
      'fetch      7 read · 1 duplicate · 0 on the removal list · 6 kept',
      'screen     3 set aside · 3 candidates',
      '    1  needs an input image, video or audio',
      '    1  has a blank with no default',
      '    1  broken in the list',
      'translate  all 3 need a text model for one language',
      'generate   3 of 3 candidates would run (limit: 120)',
      'source seedance-2  invented/video-prompts @ bbbbbbbbbbbb  (CC-BY-4.0)',
      'fetch      4 read · 0 duplicates · 0 on the removal list · 4 kept',
      'screen     2 set aside · 2 candidates',
      '    1  written for a longer clip than the limit',
      '    1  written in neither English nor Chinese',
      'translate  none of the 2 need a text model: the list supplies both languages',
      'generate   2 of 2 candidates would run (limit: 30)',
      'images     3 × 6 cents, on gpt-image-2',
      'videos     1 × 81 cents, 5 s on seedance-2.0',
      'videos     1 × 129 cents, 8 s on seedance-2.0',
      'total without the classifier',
      'optional: screen --classify (text model)',
    ]) {
      assert.ok(text.includes(expected), `the plan does not say: ${expected}\n\n${text}`);
    }
    assert.match(text, /total without the classifier\s+229 cents/);
  });

  test('plan writes nothing and makes no paid call', async (t) => {
    const { cli, dirs, world, paidRequests } = await workspace(t);
    await cli('plan');
    assert.equal(existsSync(dirs.work), false, 'no work folder was made');
    assert.equal(existsSync(dirs.content), false);
    assert.deepEqual(paidRequests(), []);
    const toApi = world.requests.filter((request) => request.url.startsWith(API_BASE));
    assert.deepEqual(toApi.map((request) => `${request.method} ${new URL(request.url).pathname}`), ['GET /v1/models'], 'only the free, public catalogue was read');
    assert.ok(world.requests.every((request) => request.authorization === null), 'the key was sent nowhere');
  });

  test('plan --offline uses seed prices and does not contact the API at all', async (t) => {
    const { cli, world } = await workspace(t);
    const { code, text } = await cli('plan', '--offline');
    assert.equal(code, 0, text);
    assert.match(text, /prices: seed prices/);
    assert.equal(world.requests.some((request) => request.url.startsWith(API_BASE)), false);
  });

  test('plan needs no API key', async (t) => {
    const { cli } = await workspace(t, { key: null });
    assert.equal((await cli('plan')).code, 0);
  });

  test('every stage is a dry run unless --apply is given', async (t) => {
    const { cli, dirs, paidRequests } = await workspace(t);
    const fetched = await cli('fetch');
    assert.equal(fetched.code, 0);
    assert.match(fetched.text, /gallery fetch: dry run\. Nothing is written and no paid call is made\. Add --apply to do it\./);
    assert.equal(existsSync(dirs.work), false);

    // With real state in place, the later stages still change nothing without --apply.
    for (const stage of [['fetch'], ['screen'], ['translate', '--max-spend-cents', '500'], ['generate', '--max-spend-cents', '500'], ['review']]) await cli(...stage, '--apply');
    const before = await allFiles(dirs.work);
    const posts = paidRequests().length;
    for (const stage of ['fetch', 'screen', 'translate', 'generate', 'review']) {
      const { code, text } = await cli(stage);
      assert.equal(code, 0, text);
      assert.match(text, new RegExp(`gallery ${stage}: dry run`));
    }
    assert.deepEqual(await allFiles(dirs.work), before, 'the work folder is byte for byte what it was');
    assert.equal(paidRequests().length, posts, 'no paid call was made');
  });

  test('a stage that needs an earlier one says so', async (t) => {
    const { cli } = await workspace(t);
    const { code, text } = await cli('screen');
    assert.equal(code, 1);
    assert.match(text, /there is no entries\.json yet: run "fetch --apply" first/);
  });

  test('a list whose licence is not the one configured is refused, loudly', async (t) => {
    const world = fakeWorld({ lists: { ...lists, 'invented/image-prompts': { ...IMAGE_LIST, LICENSE: CC_BY_TEXT } } });
    const { cli } = await workspace(t, { world });
    const { code, text } = await cli('plan');
    assert.equal(code, 1);
    assert.match(text, /REFUSED: its licence file grants CC-BY-4\.0, not the CC0-1\.0 this source is configured with\. Nothing was read from this list\./);
    assert.match(text, /source seedance-2[\s\S]*4 read/, 'the other list is still read');
  });
});

describe('a paid stage', () => {
  async function ready(t, options) {
    const space = await workspace(t, options);
    await space.cli('fetch', '--apply');
    await space.cli('screen', '--apply');
    return space;
  }

  test('refuses to run without --max-spend-cents', async (t) => {
    const { cli, paidRequests } = await ready(t);
    for (const stage of [['translate', '--apply'], ['generate', '--apply'], ['screen', '--apply', '--classify']]) {
      const { code, text } = await cli(...stage);
      assert.equal(code, 2, text);
      assert.match(text, /a run that spends money needs --max-spend-cents/);
    }
    assert.deepEqual(paidRequests(), []);
  });

  test('refuses to run without the API key', async (t) => {
    const { cli, paidRequests } = await ready(t, { key: null });
    const { code, text } = await cli('translate', '--apply', '--max-spend-cents', '500');
    assert.equal(code, 1);
    assert.match(text, /GODANCE_API_KEY is not set/);
    assert.deepEqual(paidRequests(), []);
  });

  test('translating a list that supplies both languages needs neither a cap nor a key', async (t) => {
    const { cli, dirs, paidRequests } = await ready(t, { key: null });
    const { code, text } = await cli('translate', '--apply', '--source', 'seedance-2');
    assert.equal(code, 0, text);
    assert.deepEqual(paidRequests(), []);
    assert.deepEqual(Object.values(await readJson(path.join(dirs.work, 'translated.json'))).map((record) => record.slug).sort(), ['kite-over-the-salt-flat', 'paper-boat-in-the-rain']);
  });

  test('generate stops at the cap and says what was spent against it', async (t) => {
    const { cli, dirs, world } = await ready(t);
    await cli('translate', '--apply', '--max-spend-cents', '500');
    const { code, text } = await cli('generate', '--apply', '--max-spend-cents', '21');
    assert.equal(code, 0, text);
    assert.equal(world.submissions.length, 3, 'three 6-cent images fit; the 81-cent video does not');
    assert.match(text, /stopped at the cap/);
    assert.match(text, /spent: 18 cents in this run; \d+ cents in total against a cap of 21 cents/);
    const ledger = await readJson(path.join(dirs.work, 'ledger.json'));
    assert.ok(ledger.spent_cents <= 21, `spent ${ledger.spent_cents}`);
  });

  test('the classifier only narrows, and what it said survives a re-screen', async (t) => {
    const world = fakeWorld({ lists, onChat: (messages) => (/lighthouse/i.test(messages[1].content) ? '{"set_aside": true, "rules": [8], "reason": "unsure"}' : '{"set_aside": false, "rules": [], "reason": "ok"}') });
    const { cli, dirs } = await ready(t, { world });
    const { code, text } = await cli('screen', '--apply', '--classify', '--max-spend-cents', '50');
    assert.equal(code, 0, text);
    assert.match(text, /classifier: read 5, set aside 1/);
    const chats = world.chats.length;

    await cli('screen', '--apply'); // the fixed rules again, without the classifier
    const screened = await readJson(path.join(dirs.work, 'screened.json'));
    assert.deepEqual(screened['x-1006'].reasons, [{ rule: 'standard-8', note: 'classifier: unsure' }]);
    assert.equal(screened['x-1006'].status, 'set-aside');
    assert.equal(screened['x-1001'].status, 'candidate');
    assert.equal(world.chats.length, chats, 're-screening did not pay again');
  });
});

describe('the whole pipeline, with a translator that keeps the markers', () => {
  /** Answers each kind of request the translate stage makes. */
  const keepsMarkers = (messages) =>
    /short title/.test(messages[0].content) ? 'Invented Title'
    : /values that fill the blanks/.test(messages[0].content) ? '{"boat colour": "红色"}'
    : /placeholders written in double braces/.test(messages[0].content) ? '一张黎明时分小港口的扁平海报，{{boat colour}}的船，3:4比例。'
    : /Translate into English/.test(messages[1].content) ? 'A tea packaging label with green hills, minimal style, tall frame.'
    : '一段用于测试的中文译文，描述画面的内容与风格。';

  test('a prompt with a blank is published with its template, and the run used the filled prompt', async (t) => {
    const { cli, dirs, world } = await workspace(t, { world: fakeWorld({ lists, onChat: keepsMarkers }) });
    for (const stage of [['fetch'], ['screen'], ['translate', '--max-spend-cents', '500'], ['generate', '--max-spend-cents', '500'], ['review']]) {
      const result = await cli(...stage, '--apply');
      assert.equal(result.code, 0, result.text);
    }
    assert.match(await readFile(path.join(dirs.work, 'review', 'index.html'), 'utf8'), /"template":\{"en":\[\{"text":"A flat poster of a small harbour at dawn, "\},\{"name":"boat colour"\}/);
    await writeFile(path.join(dirs.work, 'review', 'decisions.json'), JSON.stringify({
      standard_version: 1,
      decisions: { 'x-1001': { slug: 'harbour-poster', decision: 'approved', reason: '', decided_on: '2026-11-21' } },
    }));
    const published = await cli('publish', '--apply');
    assert.equal(published.code, 0, published.text);

    const { recipes, problems } = await readRecipeFolder(dirs.content);
    assert.deepEqual(problems, []);
    assert.deepEqual(recipes[0].data.prompt, {
      en: 'A flat poster of a small harbour at dawn, red boats, 3:4 ratio.',
      zh: '一张黎明时分小港口的扁平海报，红色的船，3:4比例。',
      zh_origin: 'machine',
      template: {
        en: 'A flat poster of a small harbour at dawn, {{boat colour}} boats, 3:4 ratio.',
        zh: '一张黎明时分小港口的扁平海报，{{boat colour}}的船，3:4比例。',
      },
      variables: [{ name: 'boat colour', default: 'red', zh_default: '红色' }],
    });
    assert.deepEqual(recipes[0].data.source.changes, ['placeholders rewritten as {{name}}', 'translated to Chinese']);
    // The model was sent the author's text with the blank filled, as before: the output is of that prompt.
    assert.equal(world.submissions.find((sent) => sent.body.prompt.includes('harbour')).body.prompt, 'A flat poster of a small harbour at dawn, red boats, 3:4 ratio.');

    // A recipe published with its template has nothing left for retemplate to do.
    const again = await cli('retemplate', '--all');
    assert.match(again.text, /= harbour-poster: left as it is: it already has a template/);
  });
});

describe('the whole pipeline', () => {
  test('from two lists to published recipes, through the operator’s decisions', async (t) => {
    const { cli, dirs, world, lines, paidRequests } = await workspace(t);
    const ok = async (...args) => {
      const result = await cli(...args);
      assert.equal(result.code, 0, `${args.join(' ')}\n${result.text}`);
      return result.text;
    };

    await ok('fetch', '--apply');
    await ok('screen', '--apply');
    assert.match(await ok('translate', '--apply', '--max-spend-cents', '500'), /translated 5, 0 failed/);
    const generated = await ok('generate', '--apply', '--max-spend-cents', '500');
    assert.match(generated, /done 5 · failed 0 · still running 0 · uncertain 0/);
    assert.match(generated, /spent: 239 cents in this run/); // 3 images at 6, then 5 and 8 seconds of video at 17 a second

    // Running it again resumes nothing and pays for nothing.
    const submissions = world.submissions.length;
    assert.match(await ok('generate', '--apply', '--max-spend-cents', '500'), /0 new runs would start/);
    assert.equal(world.submissions.length, submissions);

    assert.match(await ok('review', '--apply'), /5 candidates with a finished run; content standard version 1/);
    assert.equal(existsSync(path.join(dirs.work, 'review', 'index.html')), true);
    assert.equal(existsSync(path.join(dirs.work, 'review', 'decisions.json')), false, 'the tool never writes the decisions');

    const early = await cli('publish', '--apply');
    assert.equal(early.code, 1);
    assert.match(early.text, /there are no decisions yet: the operator saves/);

    // The operator decides: two approved, one rejected, two left undecided.
    await writeFile(path.join(dirs.work, 'review', 'decisions.json'), JSON.stringify({
      standard_version: 1,
      decisions: {
        'x-1001': { slug: 'harbour-poster', decision: 'approved', reason: '', decided_on: '2026-11-21' },
        'x-2002': { slug: 'kite-over-the-salt-flat', decision: 'approved', reason: '', decided_on: '2026-11-21' },
        'x-1006': { slug: 'lighthouse-print', decision: 'rejected', reason: 'the output shows a logo', decided_on: '2026-11-21' },
      },
    }));

    assert.match(await ok('publish'), /would write 2 recipes; 0 already published; 0 refused/);
    assert.equal(existsSync(dirs.content), false, 'a dry publish writes no recipe');

    const published = await ok('publish', '--apply');
    assert.match(published, /decisions: 2 approved, 1 rejected/);
    assert.match(published, /wrote 2 recipes/);
    assert.match(published, /the gallery folder is valid: 2 recipes/);
    assert.deepEqual((await readdir(dirs.content)).sort(), ['harbour-poster.json', 'kite-over-the-salt-flat.json'], 'only what was approved, and no media');

    const folder = await readRecipeFolder(dirs.content);
    assert.deepEqual(folder.problems, []);
    const [image, video] = folder.recipes.map((recipe) => recipe.data);
    assert.deepEqual(image, {
      slug: 'harbour-poster',
      title: { en: 'Harbour Poster', zh: '港口海报' },
      modality: 'IMAGE',
      tags: ['poster'],
      prompt: {
        en: 'A flat poster of a small harbour at dawn, red boats, 3:4 ratio.',
        zh: '一段用于测试的中文译文，描述画面的内容与风格。',
        zh_origin: 'machine',
        variables: [{ name: 'boat colour', default: 'red' }],
      },
      runs: [{
        model: 'gpt-image-2', prompt_lang: 'en', params: { size: '1024x1536', n: 1 }, cost_cents: 6, generated_on: '2026-11-22',
        outputs: [{ file: 'harbour-poster/gpt-image-2-0.webp', thumb: 'harbour-poster/gpt-image-2-0-480.webp', content_type: 'image/webp', width: 96, height: 64 }],
      }],
      source: {
        list: 'invented/image-prompts', list_commit: 'a'.repeat(40), licence: 'CC0-1.0', author: 'inventedone',
        author_url: 'https://x.com/inventedone', original_url: 'https://x.com/inventedone/status/1001',
        changes: ['placeholders resolved', 'translated to Chinese'],
      },
      review: { approved_on: '2026-11-21', standard_version: 1 },
      published_on: '2026-11-22',
    });
    assert.deepEqual({ zh_origin: video.prompt.zh_origin, prompt_lang: video.runs[0].prompt_lang, params: video.runs[0].params, cost: video.runs[0].cost_cents, licence: video.source.licence, changes: video.source.changes },
      { zh_origin: 'list', prompt_lang: 'zh', params: { seconds: 8, size: '1280x720' }, cost: 136, licence: 'CC-BY-4.0', changes: [] });
    assert.match(video.prompt.zh, /^正午时分/);
    assert.equal(video.runs[0].outputs[0].duration_seconds, 8);

    // The image run was sent the author's English text with its blank filled.
    assert.equal(world.submissions.find((sent) => sent.body.prompt.includes('harbour')).body.prompt, 'A flat poster of a small harbour at dawn, red boats, 3:4 ratio.');

    const status = await ok('status');
    assert.match(status, /generate   3 done/);
    assert.match(status, /review     1 approved · 1 rejected/);
    assert.match(status, /spent: \d+ cents in total \(translate \d+ cents?, image 18 cents, video 221 cents\)/);

    // Taking one down keeps it from coming back.
    const removed = await ok('remove', 'harbour-poster', '--apply', '--reason', 'the author asked');
    assert.match(removed, /removed harbour-poster\.json/);
    assert.deepEqual(await readdir(dirs.content), ['kite-over-the-salt-flat.json']);
    assert.deepEqual((await readJson(path.join(dirs.config, 'removal-list.json'))).removed, [
      { original_url: 'https://x.com/inventedone/status/1001', slug: 'harbour-poster', removed_on: '2026-11-22', reason: 'the author asked' },
    ]);
    // The list carries that post twice; both copies are dropped.
    assert.match(await ok('fetch', '--apply'), /7 read · 0 duplicates · 2 on the removal list · 5 kept/);
    assert.equal((await readJson(path.join(dirs.work, 'entries.json'))).entries.some((entry) => entry.id === 'x-1001'), false);

    // The API key: used for every paid call, and found nowhere afterwards.
    assert.ok(paidRequests().length >= 8);
    assert.ok(paidRequests().every((request) => request.authorization === `Bearer ${FAKE_KEY}`));
    assert.deepEqual(world.keyLeaks(), []);
    assert.equal(lines.join('\n').includes(FAKE_KEY), false, 'the key is in the output');
    for (const dir of [dirs.work, dirs.content, dirs.config]) {
      const files = await allFiles(dir);
      assert.ok(files.length > 0);
      for (const [name, contents] of files) assert.equal(contents.includes(FAKE_KEY), false, `${name} contains the key`);
    }
  });

  test('an error that echoes the key is printed and saved without it', async (t) => {
    const world = fakeWorld({ lists, onChat: translator, onSubmit: () => new Response(JSON.stringify({ title: 'Bad Request', detail: `no credit for ${FAKE_KEY}` }), { status: 400 }) });
    const { cli, dirs, lines } = await workspace(t, { world });
    await cli('fetch', '--apply');
    await cli('screen', '--apply');
    await cli('translate', '--apply', '--max-spend-cents', '500');
    const { text } = await cli('generate', '--apply', '--max-spend-cents', '500');
    assert.match(text, /failed x-\w+: POST \/v1\/(images|videos)\/generations: 400 no credit for \[api key\]/);
    assert.equal(lines.join('\n').includes(FAKE_KEY), false);
    for (const [name, contents] of await allFiles(dirs.work)) assert.equal(contents.includes(FAKE_KEY), false, `${name} contains the key`);
  });

  test('a decisions file with a rejection that gives no reason is not used', async (t) => {
    const { cli, dirs } = await workspace(t);
    for (const stage of [['fetch'], ['screen'], ['translate', '--max-spend-cents', '500'], ['generate', '--max-spend-cents', '500']]) await cli(...stage, '--apply');
    await mkdir(path.join(dirs.work, 'review'), { recursive: true });
    await writeFile(path.join(dirs.work, 'review', 'decisions.json'), JSON.stringify({ standard_version: 1, decisions: { 'x-1006': { decision: 'rejected', reason: '', decided_on: '2026-11-21' } } }));
    const { code, text } = await cli('publish', '--apply');
    assert.equal(code, 1);
    assert.match(text, /the decisions file cannot be used:\s+x-1006: rejected without a reason/);
    assert.equal(existsSync(dirs.content), false);
  });
});

describe('the command line itself', () => {
  test('reads options with a value either way, and refuses what it does not know', () => {
    assert.deepEqual(parseArgs(['generate', '--apply', '--max-spend-cents', '500', '--source=seedance-2']), {
      command: 'generate', positional: [], flags: { apply: true, 'max-spend-cents': '500', source: 'seedance-2' },
    });
    assert.deepEqual(parseArgs(['remove', 'some-slug']).positional, ['some-slug']);
    assert.throws(() => parseArgs(['plan', '--yes']), /unknown option --yes/);
    assert.throws(() => parseArgs(['generate', '--max-spend-cents']), /needs a value/);
  });

  test('misuse exits with 2 and shows the help', async (t) => {
    const { cli } = await workspace(t);
    for (const args of [['bogus'], ['plan', '--yes'], ['generate', '--apply', '--max-spend-cents', 'lots'], ['remove'], ['plan', '--source', 'nope']]) {
      const { code, text } = await cli(...args);
      assert.equal(code, 2, `${args.join(' ')}\n${text}`);
    }
    assert.match((await cli('--help')).text, /Every command is a dry run unless --apply is given/);
  });

  test('picks runs across categories, carries on what was started, and respects the limits', () => {
    const item = (id, category, modality = 'IMAGE') => ({ entry: { id, category, modality }, translated: {} });
    const candidates = [item('a1', 'poster'), item('a2', 'poster'), item('a3', 'poster'), item('b1', 'ui'), item('c1', 'portrait'), item('v1', null, 'VIDEO'), item('v2', null, 'VIDEO')];
    assert.deepEqual(pickForGeneration(candidates, {}, { images: 3, videos: 1 }).map(({ entry }) => entry.id), ['a1', 'b1', 'c1', 'v1']);
    // One already started counts towards the limit and is always carried on; a failed one is not picked again.
    const runs = { a3: { status: 'accepted' }, a1: { status: 'failed' } };
    assert.deepEqual(pickForGeneration(candidates, runs, { images: 3, videos: 0 }).map(({ entry }) => entry.id), ['a3', 'a2', 'b1']);
  });
});
