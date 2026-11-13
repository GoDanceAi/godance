/**
 * generate, with the API mocked: the budget guard, resuming without paying
 * twice, what happens when a request gets no clear answer, and where the
 * API key does and does not go.
 */
import assert from 'node:assert/strict';
import { readFile } from 'node:fs/promises';
import path from 'node:path';
import { describe, test } from 'node:test';
import { ApiError, createApi } from './lib/api.js';
import { BudgetExceeded, createBudget, openLedger } from './lib/budget.js';
import { probeMp4 } from './lib/media.js';
import { priceBook } from './lib/pricing.js';
import { readJson, writeJson } from './lib/work.js';
import { generate, heldByRuns, outputLinks, planRun, resolveModel } from './stages/generate.js';
import { API_BASE, CATALOGUE, FAKE_KEY, MODEL_MAP, allFiles, fakeWorld, noSleep, tempDir, tinyMp4 } from './testing.js';

const prices = priceBook(CATALOGUE);

const imageItem = (n, extra = {}) => ({
  entry: { id: `x-${n}`, modality: 'IMAGE', model: 'gpt-image-2', category: 'poster', ...extra },
  translated: { slug: `picture-${n}`, prompt: { en: `An invented picture, number ${n}.`, zh: `虚构的图片 ${n}` }, prompt_lang: 'en' },
});
const videoItem = (n, extra = {}) => ({
  entry: { id: `x-v${n}`, modality: 'VIDEO', model: 'seedance-2.0', category: null, stated_seconds: null, ...extra },
  translated: { slug: `clip-${n}`, prompt: { en: `An invented clip, number ${n}.`, zh: `虚构的视频 ${n}` }, prompt_lang: 'zh' },
});

/** Everything `generate` needs, over a fresh work folder and a fresh fake API. */
async function setup(t, { cap = 1000, world = fakeWorld(), runs = {} } = {}) {
  const work = await tempDir(t);
  const runsFile = path.join(work, 'runs.json');
  const api = createApi({ base: API_BASE, key: FAKE_KEY, fetch: world.fetch, sleep: noSleep });
  const ledger = await openLedger(path.join(work, 'ledger.json'));
  const budget = createBudget({ cap, spent: ledger.spent, held: heldByRuns(runs) });
  const ctx = {
    api, prices, budget, ledger, modelMap: MODEL_MAP, mediaDir: path.join(work, 'media'),
    save: () => writeJson(runsFile, runs), sleep: noSleep, pollIntervalMs: 1, pollTimeoutMs: 1000,
  };
  return { work, world, runs, runsFile, api, ledger, budget, ctx };
}

describe('the budget guard', () => {
  test('refuses a hold that would pass the cap, before anything is spent', () => {
    const budget = createBudget({ cap: 20 });
    const first = budget.hold(12);
    assert.throws(() => budget.hold(9), BudgetExceeded);
    assert.equal(budget.held, 12);
    first.settle(11);
    assert.deepEqual({ spent: budget.spent, held: budget.held }, { spent: 11, held: 0 });
    budget.hold(9);
    assert.throws(() => budget.hold(1), /would pass the cap of 20 cents \(11 cents spent, 9 cents in flight\)/);
  });

  test('a released hold is free again; a hold cannot be closed twice', () => {
    const budget = createBudget({ cap: 10 });
    const hold = budget.hold(10);
    hold.release();
    assert.equal(budget.remaining, 10);
    assert.throws(() => hold.settle(3), /already settled or released/);
  });

  test('counts what earlier runs spent and what their unfinished jobs still hold', () => {
    const budget = createBudget({ cap: 100, spent: 60, held: 30 });
    assert.equal(budget.canHold(10), true);
    assert.equal(budget.canHold(11), false);
  });

  test('never passes its cap, whatever the order of holds, costs and failures', () => {
    let seed = 20261113;
    const random = () => (seed = (seed * 1103515245 + 12345) % 2147483648) / 2147483648;
    for (let round = 0; round < 200; round++) {
      const cap = Math.floor(random() * 500);
      const budget = createBudget({ cap });
      const open = [];
      for (let step = 0; step < 60; step++) {
        if (open.length > 0 && random() < 0.5) {
          const hold = open.splice(Math.floor(random() * open.length), 1)[0];
          if (random() < 0.2) hold.release(); // the job failed and was refunded
          else hold.settle(Math.floor(random() * (hold.amount + 1))); // it cost no more than was held
        } else {
          const amount = Math.floor(random() * 120);
          try {
            open.push(budget.hold(amount));
          } catch (error) {
            assert.ok(error instanceof BudgetExceeded);
          }
        }
        assert.ok(budget.spent + budget.held <= cap, `round ${round}: ${budget.spent} spent + ${budget.held} held > cap ${cap}`);
      }
    }
  });

  test('generate stops before the cap would be passed, and submits nothing more', async (t) => {
    const { world, runs, ctx, budget } = await setup(t, { cap: 20 }); // room for three 6-cent images, not four
    const result = await generate([1, 2, 3, 4, 5].map((n) => imageItem(n)), runs, ctx);

    assert.equal(world.submissions.length, 3);
    assert.deepEqual({ done: result.done, stoppedByBudget: result.stoppedByBudget }, { done: 3, stoppedByBudget: true });
    assert.equal(budget.spent, 18);
    assert.ok(budget.spent <= 20);
    assert.deepEqual(Object.keys(runs).sort(), ['x-1', 'x-2', 'x-3']);
  });

  test('a cap of zero submits nothing', async (t) => {
    const { world, runs, ctx } = await setup(t, { cap: 0 });
    const result = await generate([imageItem(1)], runs, ctx);
    assert.equal(world.submissions.length, 0);
    assert.equal(result.stoppedByBudget, true);
    assert.deepEqual(runs, {});
  });

  test('a later run with the same cap counts what the first one spent', async (t) => {
    const first = await setup(t, { cap: 20 });
    await generate([1, 2, 3, 4, 5].map((n) => imageItem(n)), first.runs, first.ctx);

    const ledger = await openLedger(path.join(first.work, 'ledger.json'));
    const budget = createBudget({ cap: 20, spent: ledger.spent, held: heldByRuns(first.runs) });
    const result = await generate([1, 2, 3, 4, 5].map((n) => imageItem(n)), first.runs, { ...first.ctx, ledger, budget });
    assert.equal(first.world.submissions.length, 3, 'nothing new was submitted');
    assert.equal(result.stoppedByBudget, true);
    assert.equal(ledger.spent, 18);
  });
});

describe('no double payment', () => {
  test('the job id is on disk as soon as the API accepts the job', async (t) => {
    let seenOnDisk = null;
    const { runs, runsFile, ctx } = await setup(t, {
      world: fakeWorld({ onPoll: (view) => view }),
    });
    const api = ctx.api;
    ctx.api = { ...api, job: async (id) => { seenOnDisk ??= (await readJson(runsFile))['x-1']; return api.job(id); } };
    await generate([imageItem(1)], runs, ctx);
    assert.deepEqual({ status: seenOnDisk.status, job_id: seenOnDisk.job_id, hold_cents: seenOnDisk.hold_cents }, { status: 'accepted', job_id: 'job-1', hold_cents: 6 });
  });

  test('a resumed run does not resubmit an accepted job', async (t) => {
    // First run: the job is accepted, then the wait runs out (as if the run was interrupted).
    const world = fakeWorld({ pollsUntilDone: 3 });
    const first = await setup(t, { world });
    const interrupted = await generate([imageItem(1)], first.runs, { ...first.ctx, pollTimeoutMs: 0 });
    assert.equal(interrupted.pending, 1);
    assert.equal(world.submissions.length, 1);
    assert.deepEqual({ status: first.runs['x-1'].status, job_id: first.runs['x-1'].job_id }, { status: 'accepted', job_id: 'job-1' });

    // Second run, from what is on disk: same job, no new submission.
    const runs = await readJson(first.runsFile);
    const ledger = await openLedger(path.join(first.work, 'ledger.json'));
    const budget = createBudget({ cap: 1000, spent: ledger.spent, held: heldByRuns(runs) });
    assert.equal(budget.held, 6, 'the unfinished job still holds its money');
    const resumed = await generate([imageItem(1)], runs, { ...first.ctx, ledger, budget, save: () => writeJson(first.runsFile, runs) });

    assert.equal(world.submissions.length, 1, 'the job was not submitted again');
    assert.equal(resumed.done, 1);
    assert.deepEqual({ status: runs['x-1'].status, job_id: runs['x-1'].job_id, cost_cents: runs['x-1'].cost_cents }, { status: 'done', job_id: 'job-1', cost_cents: 6 });
    assert.deepEqual({ spent: budget.spent, held: budget.held }, { spent: 6, held: 0 });
    assert.equal(ledger.events.length, 1, 'it was paid for once');
  });

  test('a finished run is never run again', async (t) => {
    const { world, runs, ctx } = await setup(t);
    await generate([imageItem(1)], runs, ctx);
    const again = await generate([imageItem(1)], runs, ctx);
    assert.equal(world.submissions.length, 1);
    assert.equal(again.skipped, 1);
  });

  test('a request that got no clear answer is not sent again by itself', async (t) => {
    const world = fakeWorld({ onSubmit: (call) => (call.body.prompt.includes('number 1.') ? 'network-error' : undefined) });
    const { runs, ctx, budget } = await setup(t, { world });
    const result = await generate([imageItem(1), imageItem(2)], runs, ctx);
    assert.deepEqual({ uncertain: result.uncertain, done: result.done }, { uncertain: 1, done: 1 });
    assert.equal(runs['x-1'].status, 'submitting');
    assert.equal(runs['x-1'].job_id, undefined);
    assert.equal(budget.held, 6, 'its money stays held: the job may exist');

    const posts = () => world.requests.filter((request) => request.method === 'POST').length;
    const before = posts();
    const again = await generate([imageItem(1), imageItem(2)], runs, ctx);
    assert.equal(posts(), before, 'no request was sent for it');
    assert.equal(again.uncertain, 1);
  });

  test('a 5xx on submit is treated as no clear answer; a 4xx as a refusal that frees the money', async (t) => {
    const world = fakeWorld({
      onSubmit: (call) =>
        call.body.prompt.includes('number 1.') ? new Response('{"title":"Bad Gateway"}', { status: 502 })
        : call.body.prompt.includes('number 2.') ? new Response('{"title":"Bad Request","detail":"size not supported"}', { status: 400 })
        : undefined,
    });
    const { runs, ctx, budget } = await setup(t, { world });
    const result = await generate([imageItem(1), imageItem(2)], runs, ctx);
    assert.deepEqual({ uncertain: result.uncertain, failed: result.failed }, { uncertain: 1, failed: 1 });
    assert.equal(runs['x-1'].status, 'submitting');
    assert.equal(runs['x-2'].status, 'failed');
    assert.match(runs['x-2'].error, /400 size not supported/);
    assert.equal(budget.held, 6, 'only the uncertain one still holds money');
  });

  test('--retry-uncertain sends it again, holding its money once', async (t) => {
    let fail = true;
    const world = fakeWorld({ onSubmit: () => (fail ? 'network-error' : undefined) });
    const { runs, ctx, budget } = await setup(t, { world, cap: 6 });
    await generate([imageItem(1)], runs, ctx);
    fail = false;
    const result = await generate([imageItem(1)], runs, { ...ctx, retryUncertain: true });
    assert.equal(result.done, 1);
    assert.deepEqual({ spent: budget.spent, held: budget.held }, { spent: 6, held: 0 });
  });

  test('a failed job is refunded: its hold is released and nothing is recorded as spent', async (t) => {
    const world = fakeWorld({ onPoll: (view) => ({ ...view, status: 'FAILED', actualCostCents: null, outputUrl: null, errorMessage: 'the provider refused the prompt' }) });
    const { runs, ctx, budget, ledger } = await setup(t, { world });
    const result = await generate([imageItem(1)], runs, ctx);
    assert.equal(result.failed, 1);
    assert.deepEqual({ status: runs['x-1'].status, error: runs['x-1'].error }, { status: 'failed', error: 'the provider refused the prompt' });
    assert.deepEqual({ spent: budget.spent, held: budget.held, events: ledger.events.length }, { spent: 0, held: 0, events: 0 });
  });

  test('a download that fails is tried again from the job, without a new submission', async (t) => {
    let expired = true;
    const world = fakeWorld({ onPoll: (view) => (expired && view.outputUrl ? { ...view, outputUrl: 'https://storage.godance.test/out/expired.png' } : view) });
    const { runs, ctx, ledger } = await setup(t, { world });
    const first = await generate([imageItem(1)], runs, ctx);
    assert.equal(first.pending, 1);
    assert.deepEqual({ status: runs['x-1'].status, cost_cents: runs['x-1'].cost_cents }, { status: 'downloading', cost_cents: 6 });

    expired = false;
    const second = await generate([imageItem(1)], runs, ctx);
    assert.equal(second.done, 1);
    assert.equal(world.submissions.length, 1);
    assert.equal(ledger.events.length, 1, 'paid for once');
  });
});

describe('running a job', () => {
  test('an image is made one at a time, saved as WebP with a 480-pixel thumbnail, and its cost recorded', async (t) => {
    const { work, world, runs, ctx } = await setup(t);
    await generate([imageItem(1)], runs, ctx);
    assert.deepEqual(world.submissions[0].body, { model: 'gpt-image-2', prompt: 'An invented picture, number 1.', size: '1024x1024', n: 1 });
    const run = runs['x-1'];
    assert.deepEqual(run.params, { size: '1024x1024', n: 1 });
    assert.equal(run.cost_cents, 6);
    assert.match(run.generated_on, /^\d{4}-\d{2}-\d{2}$/);
    assert.deepEqual(run.outputs, [{ file: 'picture-1/gpt-image-2-0.webp', thumb: 'picture-1/gpt-image-2-0-480.webp', content_type: 'image/webp', width: 96, height: 64 }]);
    const files = (await allFiles(path.join(work, 'media'))).map(([name]) => name).sort();
    assert.deepEqual(files, ['picture-1/gpt-image-2-0-480.webp', 'picture-1/gpt-image-2-0.webp']);
  });

  test('the prompt is sent in the language the author wrote it in', async (t) => {
    const { world, runs, ctx } = await setup(t);
    await generate([videoItem(1)], runs, ctx);
    assert.equal(world.submissions[0].body.prompt, '虚构的视频 1');
    assert.equal(runs['x-v1'].prompt_lang, 'zh');
  });

  test('a video always states its seconds: the length the prompt names, or the default', async (t) => {
    const { world, runs, ctx } = await setup(t);
    await generate([videoItem(1), videoItem(2, { stated_seconds: 8 })], runs, { ...ctx, videoSeconds: 5, concurrency: 1 });
    assert.deepEqual(world.submissions.map((s) => s.body.seconds), [5, 8]);
    assert.deepEqual(runs['x-v2'].params, { seconds: 8, size: '1280x720' });
    assert.deepEqual(runs['x-v2'].outputs, [{ file: 'clip-2/seedance-2.0-0.mp4', content_type: 'video/mp4', duration_seconds: 8, width: 1280, height: 720 }]);
    assert.equal(runs['x-v2'].cost_cents, 17 * 8);
  });

  test('the size follows the frame the prompt asks for', () => {
    const tall = imageItem(1);
    tall.translated.prompt.en = 'A poster, 3:4 ratio.';
    assert.deepEqual(planRun(tall.entry, tall.translated, { prices, modelMap: MODEL_MAP }).params, { size: '1024x1536', n: 1 });
    const wide = videoItem(1);
    wide.translated.prompt.zh = '竖屏，一只风筝。';
    assert.deepEqual(planRun(wide.entry, wide.translated, { prices, modelMap: MODEL_MAP, videoSeconds: 5 }).params, { seconds: 5, size: '720x1280' });
  });

  test('what a job holds comes from the catalogue price and the platform fee', () => {
    assert.equal(planRun(imageItem(1).entry, imageItem(1).translated, { prices, modelMap: MODEL_MAP }).hold, 6);
    assert.equal(planRun(videoItem(1).entry, videoItem(1).translated, { prices, modelMap: MODEL_MAP, videoSeconds: 5 }).hold, 81);
    assert.equal(planRun(videoItem(1, { stated_seconds: 10 }).entry, videoItem(1).translated, { prices, modelMap: MODEL_MAP }).hold, 161);
  });

  test("uses the list's own model when Godance serves it, the mapping file otherwise", () => {
    assert.equal(resolveModel('gpt-image-2', { prices, modelMap: MODEL_MAP }), 'gpt-image-2');
    const without = priceBook(CATALOGUE.filter((model) => model.id !== 'seedance-2.0'));
    assert.equal(resolveModel('seedance-2.0', { prices: without, modelMap: MODEL_MAP }), null);
    const other = priceBook([...CATALOGUE.filter((model) => model.id !== 'seedance-2.0'), { id: 'other-video', godance: { modality: 'VIDEO', pricing: { per_second_microcents: 100000 }, markup_percent: 10 } }]);
    assert.equal(resolveModel('seedance-2.0', { prices: other, modelMap: { ...MODEL_MAP, generate: { 'seedance-2.0': 'other-video' } } }), 'other-video');
  });

  test("uses a job's outputs when it has them, and outputUrl otherwise", () => {
    assert.deepEqual(outputLinks({ outputs: [{ url: 'https://s/a' }, { url: 'https://s/b' }], outputUrl: 'https://s/a' }), ['https://s/a', 'https://s/b']);
    assert.deepEqual(outputLinks({ outputs: ['https://s/a'] }), ['https://s/a']);
    assert.deepEqual(outputLinks({ outputs: [], outputUrl: 'https://s/first' }), ['https://s/first']);
    assert.deepEqual(outputLinks({ outputUrl: 'https://s/first' }), ['https://s/first']);
    assert.deepEqual(outputLinks({}), []);
  });

  test('at most two jobs are in flight', async (t) => {
    const { world, runs, ctx } = await setup(t, { world: fakeWorld({ pollsUntilDone: 3 }) });
    await generate([1, 2, 3, 4, 5, 6].map((n) => imageItem(n)), runs, { ...ctx, concurrency: 8 });
    assert.equal(world.submissions.length, 6);
    assert.equal(world.mostInFlight, 2);
  });

  test('backs off on 429 and tries again, as long as the API asks', async (t) => {
    const waits = [];
    const world = fakeWorld({ onSubmit: (call) => (call.attempt <= 2 ? new Response('{"title":"Too Many Requests"}', { status: 429, headers: call.attempt === 1 ? { 'retry-after': '7' } : {} }) : undefined) });
    const { runs, ctx } = await setup(t, { world });
    ctx.api = createApi({ base: API_BASE, key: FAKE_KEY, fetch: world.fetch, sleep: async (ms) => waits.push(ms) });
    const result = await generate([imageItem(1)], runs, ctx);
    assert.equal(result.done, 1);
    assert.equal(world.submissions.length, 1);
    assert.deepEqual(waits.slice(0, 2), [7000, 4000], 'the wait the API names, then a growing one');
  });

  test('reads the size and length of an MP4 from the file itself', () => {
    assert.deepEqual(probeMp4(tinyMp4({ width: 720, height: 1280, seconds: 5 })), { width: 720, height: 1280, duration_seconds: 5 });
    assert.equal(probeMp4(Buffer.from('not a video at all')), null);
    assert.equal(probeMp4(Buffer.alloc(0)), null);
  });
});

describe('the API key', () => {
  test('goes to the API and nowhere else: not to the host an output is downloaded from', async (t) => {
    const { world, runs, ctx } = await setup(t);
    await generate([imageItem(1), videoItem(1)], runs, ctx);
    assert.ok(world.requests.some((request) => request.url.startsWith('https://storage.godance.test/')));
    assert.deepEqual(world.keyLeaks(), []);
    assert.ok(world.requests.filter((request) => request.url.startsWith(API_BASE)).every((request) => request.authorization === `Bearer ${FAKE_KEY}`));
  });

  test('is never written to the work folder', async (t) => {
    const world = fakeWorld({ onSubmit: (call) => (call.body.prompt.includes('number 2.') ? new Response(JSON.stringify({ title: 'Bad Request', detail: `rejected for key ${FAKE_KEY}` }), { status: 400 }) : undefined) });
    const { work, runs, ctx } = await setup(t, { world });
    await generate([imageItem(1), imageItem(2), videoItem(1)], runs, ctx);
    await ctx.save();
    const files = await allFiles(work);
    assert.ok(files.length >= 4);
    for (const [name, contents] of files) assert.equal(contents.includes(FAKE_KEY), false, `${name} contains the key`);
    assert.match(runs['x-2'].error, /rejected for key \[api key\]/, 'an error that echoes the key is saved without it');
    assert.equal((await readFile(path.join(work, 'runs.json'), 'utf8')).includes('[api key]'), true);
  });

  test('is not in an error the client raises, even when the API echoes it back', async () => {
    const fetch = async () => new Response(JSON.stringify({ title: 'Unauthorized', detail: `bad key ${FAKE_KEY}` }), { status: 401 });
    const api = createApi({ base: API_BASE, key: FAKE_KEY, fetch, sleep: noSleep });
    await assert.rejects(api.job('job-1'), (error) => error instanceof ApiError && error.status === 401 && !error.message.includes(FAKE_KEY) && error.message.includes('[api key]'));
  });

  test('is not sent to the model catalogue, which is public', async () => {
    const world = fakeWorld();
    const api = createApi({ base: API_BASE, key: FAKE_KEY, fetch: world.fetch, sleep: noSleep });
    await api.models();
    assert.equal(world.requests[0].authorization, null);
  });

  test('without a key, a paid call is refused before any request is made', async () => {
    const world = fakeWorld();
    const api = createApi({ base: API_BASE, fetch: world.fetch, sleep: noSleep });
    await assert.rejects(api.submitImage({ model: 'gpt-image-2', prompt: 'x' }), /GODANCE_API_KEY/);
    assert.equal(world.requests.length, 0);
  });
});
