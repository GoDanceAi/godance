/**
 * generate: run each translated candidate on Godance, once.
 *
 * Budget guard. Before a job is submitted its projected cost is held
 * against the cap; when what has been spent, what is in flight and this job
 * would pass the cap, the job is not submitted and nothing more is started.
 *
 * No double payment. A run's record is saved before the request is sent
 * ("submitting") and again the moment the API accepts it, with the job id
 * ("accepted"). A later run resumes that job by its id. If the request was
 * sent and no clear answer came back, the record stays "submitting": the
 * job may exist, so it is never submitted again without the operator
 * saying so.
 *
 * Output links expire, so each output is downloaded as soon as its job is
 * done. At most two jobs are in flight; a 429 is retried after a wait (see
 * lib/api.js).
 */
import { BudgetExceeded } from '../lib/budget.js';
import { saveImage, saveVideo } from '../lib/media.js';
import { pool } from '../lib/pool.js';
import { statedShape } from '../lib/text.js';
import { today } from '../lib/work.js';

const realSleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

/**
 * The model to run a list's prompts on: the one the list was written for
 * when Godance serves it, otherwise what the operator's mapping file says.
 */
export function resolveModel(wanted, { prices, modelMap }) {
  if (prices.has(wanted)) return wanted;
  const mapped = modelMap?.generate?.[wanted];
  return mapped && prices.has(mapped) ? mapped : null;
}

/**
 * The request for one run: its model, its parameters under the API's own
 * field names, and the cents it will hold. Images are made one at a time; a
 * video always states its seconds.
 */
export function planRun(entry, translated, { prices, modelMap, videoSeconds = 5 }) {
  const model = resolveModel(entry.model, { prices, modelMap });
  if (!model) return { error: `Godance does not serve "${entry.model}", and model-map.json gives no model to use instead` };
  const prompt = translated.prompt[translated.prompt_lang];
  const shape = statedShape(prompt);
  if (entry.modality === 'VIDEO') {
    const seconds = entry.stated_seconds ?? videoSeconds;
    const params = { seconds, size: modelMap.video_sizes[shape] };
    return { model, prompt, params, hold: prices.video(model, seconds) };
  }
  const params = { size: modelMap.image_sizes[shape], n: 1 };
  return { model, prompt, params, hold: prices.image(model, 1) };
}

/** What still holds money from an earlier, interrupted run. */
export const heldByRuns = (runs) =>
  Object.values(runs)
    .filter((run) => run.status === 'accepted' || run.status === 'submitting')
    .reduce((total, run) => total + (run.hold_cents ?? 0), 0);

/** Wait for a job to finish. Returns the job, or null when the wait ran out. */
async function waitForJob(api, jobId, { intervalMs, timeoutMs, sleep, now }) {
  const deadline = now().getTime() + timeoutMs;
  for (;;) {
    let job = null;
    try {
      job = await api.job(jobId);
    } catch (error) {
      if (error.rejected) throw error; // refused outright (bad key, not our job): waiting will not help
    }
    if (job && (job.status === 'DONE' || job.status === 'FAILED')) return job;
    if (now().getTime() >= deadline) return null;
    await sleep(intervalMs);
  }
}

/** The links a finished job gives: `outputs` when it has them, `outputUrl` otherwise. */
export function outputLinks(job) {
  const many = Array.isArray(job.outputs) ? job.outputs.map((output) => (typeof output === 'string' ? output : output?.url)).filter(Boolean) : [];
  return many.length > 0 ? many : job.outputUrl ? [job.outputUrl] : [];
}

/**
 * @param {{ entry: object, translated: object }[]} items What to run, in order.
 * @param {Record<string, object>} runs One record per entry id, from earlier runs; updated in place.
 * @param {object} ctx
 * @returns {Promise<object>} Counts of what happened.
 */
export async function generate(items, runs, ctx) {
  const {
    api, prices, budget, ledger, modelMap, save, mediaDir,
    videoSeconds = 5, ffmpeg = false, concurrency = 2, retryUncertain = false,
    pollIntervalMs = 5000, pollTimeoutMs = 20 * 60 * 1000,
    sleep = realSleep, now = () => new Date(), log = () => {},
  } = ctx;
  const outcome = { done: 0, failed: 0, pending: 0, uncertain: 0, skipped: 0, stoppedByBudget: false };
  const waiting = { intervalMs: pollIntervalMs, timeoutMs: pollTimeoutMs, sleep, now };

  await pool(items, Math.min(concurrency, 2), async ({ entry, translated }) => {
    const id = entry.id;
    let run = runs[id];
    const kind = entry.modality === 'VIDEO' ? 'video' : 'image';

    if (run?.status === 'done' || run?.status === 'failed') {
      outcome.skipped++;
      return true;
    }
    if (run?.status === 'submitting' && !retryUncertain) {
      outcome.uncertain++; // the job may exist: never sent again on its own
      return true;
    }

    let hold = null;
    if (run?.job_id && run.status === 'accepted') {
      hold = budget.adopt(run.hold_cents); // resumed: already counted as in flight
    } else if (!(run?.job_id && run.status === 'downloading')) {
      const plan = planRun(entry, translated, { prices, modelMap, videoSeconds });
      if (plan.error) {
        runs[id] = { status: 'failed', error: plan.error };
        await save();
        outcome.failed++;
        return true;
      }
      const wasUncertain = run?.status === 'submitting';
      try {
        // An uncertain run still holds its money; retrying it does not hold twice.
        hold = wasUncertain ? budget.adopt(run.hold_cents) : budget.hold(plan.hold);
      } catch (error) {
        if (!(error instanceof BudgetExceeded)) throw error;
        outcome.stoppedByBudget = true;
        log(error.message);
        return false;
      }
      run = runs[id] = {
        status: 'submitting',
        slug: translated.slug,
        model: plan.model,
        params: plan.params,
        prompt_lang: translated.prompt_lang,
        hold_cents: hold.amount,
        submitted_at: now().toISOString(),
      };
      await save(); // before the request: a crash from here on leaves a trace

      const body = { model: plan.model, prompt: plan.prompt, ...plan.params };
      let jobId;
      try {
        jobId = kind === 'video' ? await api.submitVideo(body) : await api.submitImage(body);
      } catch (error) {
        if (error.rejected) {
          hold.release();
          runs[id] = { ...run, status: 'failed', error: api.redact(error.message) };
          outcome.failed++;
        } else {
          run.error = api.redact(error.message); // stays "submitting": outcome unknown
          outcome.uncertain++;
        }
        await save();
        return true;
      }
      run = runs[id] = { ...run, status: 'accepted', job_id: jobId };
      delete run.error;
      await save(); // the job id is on disk before anything else happens
    }

    // Wait for the job, unless it already finished in an earlier run.
    let job;
    try {
      job = await waitForJob(api, run.job_id, waiting);
    } catch (error) {
      run.error = api.redact(error.message);
      await save();
      outcome.pending++;
      return true;
    }
    if (!job) {
      outcome.pending++; // still running: the next run picks it up by its id
      return true;
    }
    if (job.status === 'FAILED') {
      hold?.release(); // a failed job is refunded
      runs[id] = { ...run, status: 'failed', error: api.redact(String(job.errorMessage ?? 'the job failed')) };
      await save();
      outcome.failed++;
      return true;
    }

    if (run.status === 'accepted') {
      const actual = Number.isFinite(job.actualCostCents) ? job.actualCostCents : (job.estimatedCostCents ?? run.hold_cents);
      hold.settle(actual);
      await ledger.record({ kind, ref: id, job_id: run.job_id, model: run.model, cents: actual });
      run = runs[id] = { ...run, status: 'downloading', cost_cents: actual, generated_on: today(now()) };
      await save();
    }

    // Download at once: the links expire.
    try {
      const links = outputLinks(job);
      if (links.length === 0) throw new Error('the finished job has no output link');
      const outputs = [];
      for (const [index, link] of links.entries()) {
        const { bytes, contentType } = await api.download(link);
        const name = `${run.model}-${index}`;
        outputs.push(
          kind === 'video'
            ? await saveVideo({ bytes, contentType, mediaDir, slug: run.slug, name, ffmpeg })
            : await saveImage({ bytes, mediaDir, slug: run.slug, name }),
        );
      }
      run = runs[id] = { ...run, status: 'done', outputs };
      delete run.error;
      outcome.done++;
    } catch (error) {
      run.error = api.redact(error.message); // stays "downloading": the next run fetches the job again
      outcome.pending++;
    }
    await save();
    return true;
  });
  return outcome;
}
