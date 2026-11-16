/**
 * rerun: add a run on another model to recipes that are already published.
 *
 * For each recipe selected (--slugs, --tag or --all) and each model named
 * (--models), one job: the recipe's prompt in the language its first run
 * sent, with the first run's parameters as far as the model takes them, and,
 * for a recipe with inputs, its sample inputs, uploaded as input files as
 * generate does. A recipe with a template is run with the prompt its template
 * gives with the defaults, which the format holds to be the prompt itself. A
 * recipe that already has a run on a model gets no second one; one that a
 * model cannot run is reported with the reason, and nothing is spent on it.
 *
 * --china-hosted adds, to each selected recipe with no run on a China-hosted
 * model, a run on the cheapest one of its modality, chosen from the committed
 * list in config/china-hosted.json (the first listed when two cost the same).
 *
 * Parameters. The first run's are carried over only where the catalogue
 * (GET /v1/models) says the model takes them: a size it lists, or its default
 * size; a count up to its max_n (1 when it states none); a clip length it
 * lists, or its default. Anything else is left out, and the plan says what
 * and why. A video always states its seconds: the model's default when the
 * first run's cannot be kept. config/rerun-params.json holds the exceptions,
 * each confirmed by the provider's own documentation.
 *
 * Money. As generate: a dry run unless --apply, never without
 * --max-spend-cents, one budget over everything the work folder has spent and
 * holds, at most two jobs in flight, and no double payment. A run's record is
 * saved before its request is sent and again, with the job id, the moment the
 * API accepts it; a request that got no clear answer is never sent again on
 * its own.
 *
 * Publishing. A finished run is reviewed beside the recipe's first run, in
 * the review sheet, and approved on its own. publish appends each approved
 * run to its recipe, with the day it was approved (approved_on). Nothing else
 * in the recipe changes: not its other runs, not their order, not its review.
 */
import { readFile, writeFile } from 'node:fs/promises';
import path from 'node:path';
import { isDeepStrictEqual } from 'node:util';
import { checkRecipe, formatProblem, recipeFileName } from '../../../src/lib/gallery-schema.js';
import { BudgetExceeded } from '../lib/budget.js';
import { saveImage, saveVideo } from '../lib/media.js';
import { pool } from '../lib/pool.js';
import { readJson, today, writeJson } from '../lib/work.js';
import { inputFields, outputLinks, waitForJob } from './generate.js';

const realSleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

const LANGUAGE = { en: 'English', zh: 'Chinese' };
const KIND = { IMAGE: 'images', VIDEO: 'videos' };
const A_RECIPE = { IMAGE: 'an image recipe', VIDEO: 'a video recipe' };

/** The record a rerun is kept under in reruns.json and in the decisions: one recipe on one model. */
export const rerunKey = (slug, model) => `${slug}@${model}`;

/** A parameter's value as a plan prints it. */
export const shownValue = (value) => (typeof value === 'string' ? value : JSON.stringify(value));

/**
 * The published recipes a rerun is for: those named, in the order named; or
 * those with a tag, or all of them, in slug order.
 *
 * @param {object[]} recipes Published recipes.
 * @param {{ slugs?: string[] | null, tag?: string | null, all?: boolean }} selector
 * @returns {{ recipes: object[], unknown: string[] }} `unknown` holds the slugs that name no recipe.
 */
export function selectRecipes(recipes, { slugs = null, tag = null, all = false }) {
  const sorted = [...recipes].sort((a, b) => a.slug.localeCompare(b.slug));
  if (all) return { recipes: sorted, unknown: [] };
  if (tag !== null) return { recipes: sorted.filter((recipe) => recipe.tags.includes(tag)), unknown: [] };
  const bySlug = new Map(recipes.map((recipe) => [recipe.slug, recipe]));
  const wanted = [...new Set(slugs ?? [])];
  return { recipes: wanted.filter((slug) => bySlug.has(slug)).map((slug) => bySlug.get(slug)), unknown: wanted.filter((slug) => !bySlug.has(slug)) };
}

const lists = (values, value) => Array.isArray(values) && values.some((each) => isDeepStrictEqual(each, value));

/** Why the catalogue does not say `model` takes this value, or null when it does. */
function refusal(param, value, { model, capabilities }) {
  switch (param) {
    case 'size':
      if (lists(capabilities.sizes, value) || value === capabilities.default_size) return null;
      return Array.isArray(capabilities.sizes)
        ? `${model} does not list ${shownValue(value)} among its sizes`
        : `${model} lists no sizes${capabilities.default_size ? `, so its default, ${capabilities.default_size}, applies` : ''}`;
    case 'n': {
      // An absent max_n is 1 (V24): one output per request.
      const most = Number.isInteger(capabilities.max_n) ? capabilities.max_n : 1;
      if (Number.isInteger(value) && value >= 1 && value <= most) return null;
      return `${model} makes at most ${most} per request`;
    }
    case 'seconds':
      if (lists(capabilities.durations, value) || value === capabilities.default_seconds) return null;
      return Array.isArray(capabilities.durations)
        ? `${model} does not list ${shownValue(value)} s among its clip lengths`
        : `${model} lists no clip lengths${Number.isInteger(capabilities.default_seconds) ? `, so its default, ${capabilities.default_seconds} s, is sent` : ''}`;
    default:
      return `the catalogue does not say ${model} takes ${param}`;
  }
}

/**
 * The first run's parameters as far as `model` takes them.
 *
 * Each is kept only where the catalogue's capabilities say the model takes
 * it, or where the exceptions file allows it or names a value to send
 * instead. Everything else is left out, with the reason. A video always
 * states its seconds: the model's default when the first run's is left out.
 *
 * @param {Record<string, unknown>} params The first run's.
 * @param {{ model: string, modality: 'IMAGE' | 'VIDEO', capabilities?: object, exceptions?: { allow?: object, map?: object } }} options
 * @returns {{ params: Record<string, unknown>, left: { param: string, value: unknown, why: string }[] } | { error: string }}
 */
export function mapParams(params, { model, modality, capabilities = {}, exceptions = {} }) {
  const allowed = exceptions.allow?.[model] ?? {};
  const instead = exceptions.map?.[model] ?? {};
  const out = {};
  const left = [];
  for (const [param, value] of Object.entries(params ?? {})) {
    const replacements = instead[param];
    if (replacements && typeof replacements === 'object' && Object.hasOwn(replacements, String(value))) {
      out[param] = replacements[String(value)];
      continue;
    }
    if (lists(allowed[param], value)) {
      out[param] = value;
      continue;
    }
    const why = refusal(param, value, { model, capabilities });
    if (why === null) out[param] = value;
    else left.push({ param, value, why });
  }
  if (modality === 'VIDEO' && out.seconds === undefined) {
    if (!Number.isInteger(capabilities.default_seconds)) {
      return { error: `${model} lists no clip lengths and no default length, so the run could not state its seconds` };
    }
    out.seconds = capabilities.default_seconds;
  }
  return { params: out, left };
}

/**
 * Why `model` cannot take the recipe's input slots, or null when it can, or
 * the recipe has none. The catalogue says what each model takes (V33, V34),
 * and a capability it does not state is one it does not have.
 */
export function inputsRefusal(recipe, { model, capabilities = {} }) {
  const slots = recipe.inputs ?? [];
  if (slots.length === 0) return null;
  const takes = capabilities.inputs ?? {};
  if (recipe.modality === 'IMAGE') {
    const most = Number.isInteger(takes.images?.max) ? takes.images.max : 0;
    if (slots.length <= most) return null;
    return most === 0 ? `${model} takes no input images` : `${model} takes at most ${most} input images, and this recipe has ${slots.length}`;
  }
  const count = (role) => slots.filter((slot) => slot.role === role).length;
  if (count('first_frame') > 0 && takes.first_frame !== true) return `${model} takes no first frame`;
  if (count('last_frame') > 0 && takes.last_frame !== true) return `${model} takes no last frame`;
  const references = count('reference_image');
  const most = Number.isInteger(takes.reference_images?.max) ? takes.reference_images.max : 0;
  if (references > most) {
    return most === 0 ? `${model} takes no reference images` : `${model} takes at most ${most} reference images, and this recipe has ${references}`;
  }
  return null;
}

/** The model ids of one modality in config/china-hosted.json, in the file's order. */
export const chinaHostedModels = (list, modality) => (list?.[modality] ?? []).map((entry) => entry.model);

/**
 * The China-hosted model --china-hosted gives a recipe: of the committed
 * list's models of its modality that today's catalogue lists, the cheapest by
 * the price of one image or one second, with the fee. When two cost the same,
 * the one listed first in the file. None, with the reason, when the recipe
 * already has a run on one of them or the catalogue lists none.
 *
 * @returns {{ model: string } | { model: null, why: string }}
 */
export function chinaHostedFor(recipe, { list, prices }) {
  const listed = chinaHostedModels(list, recipe.modality);
  const has = recipe.runs.find((run) => listed.includes(run.model));
  if (has) return { model: null, why: `it already has a run on ${has.model}, which is China-hosted` };
  const served = listed.filter((id) => prices.has(id) && prices.modality(id) === recipe.modality);
  if (served.length === 0) return { model: null, why: `the catalogue lists none of the China-hosted ${KIND[recipe.modality]} models in config/china-hosted.json` };
  // A later model replaces the cheapest so far only when it costs strictly less, so a tie goes to the file's order.
  return { model: served.reduce((best, id) => (prices.unitCents(id) < prices.unitCents(best) ? id : best)) };
}

/**
 * One rerun as it would be made: what it sends and what it holds; or why it
 * is not made. Reads nothing and sends nothing.
 *
 * @param {object} recipe A published recipe.
 * @param {string} model
 * @param {{ prices: object, exceptions?: object }} options
 */
export function planRerun(recipe, model, { prices, exceptions = {} }) {
  const base = { key: rerunKey(recipe.slug, model), slug: recipe.slug, model, modality: recipe.modality };
  if (recipe.runs.some((run) => run.model === model)) return { ...base, skip: 'already', why: `it already has a run on ${model}` };
  if (!prices.has(model)) return { ...base, skip: 'cannot', why: `the catalogue does not list ${model}` };
  if (prices.modality(model) !== recipe.modality) {
    return { ...base, skip: 'cannot', why: `${model} does not make ${KIND[recipe.modality]}, and this is ${A_RECIPE[recipe.modality]}` };
  }
  const capabilities = prices.capabilities(model);
  const inputsWhy = inputsRefusal(recipe, { model, capabilities });
  if (inputsWhy) return { ...base, skip: 'cannot', why: inputsWhy };

  const first = recipe.runs[0];
  const mapped = mapParams(first.params, { model, modality: recipe.modality, capabilities, exceptions });
  if ('error' in mapped) return { ...base, skip: 'cannot', why: mapped.error };

  const slots = recipe.inputs ?? [];
  const count = Number.isInteger(mapped.params.n) ? mapped.params.n : 1;
  // As generate holds: an edit also holds the model's price per input image, when it has one.
  const hold = recipe.modality === 'VIDEO'
    ? prices.video(model, mapped.params.seconds, 1, slots.length)
    : slots.length > 0 ? prices.edit(model, count, slots.length) : prices.image(model, count);
  return {
    ...base,
    prompt_lang: first.prompt_lang,
    prompt: recipe.prompt[first.prompt_lang],
    params: mapped.params,
    left: mapped.left,
    inputs: slots.length > 0 ? slots : null,
    hold,
  };
}

/** What still holds money from an earlier, interrupted rerun. */
export const heldByReruns = (state) =>
  Object.values(state)
    .filter((record) => record.status === 'accepted' || record.status === 'submitting')
    .reduce((total, record) => total + (record.hold_cents ?? 0), 0);

/**
 * Run the planned reruns, and resume the ones an earlier run left in flight.
 *
 * @param {object[]} items Planned reruns (planRerun), and unfinished records to resume, as { key, slug, model, modality }.
 * @param {Record<string, object>} state reruns.json, one record per key; updated in place.
 * @param {object} ctx
 * @returns {Promise<object>} Counts of what happened.
 */
export async function runReruns(items, state, ctx) {
  const {
    api, budget, ledger, save, mediaDir, ffmpeg = false, concurrency = 2, retryUncertain = false,
    pollIntervalMs = 5000, pollTimeoutMs = 20 * 60 * 1000,
    sleep = realSleep, now = () => new Date(), log = () => {},
  } = ctx;
  const outcome = { done: 0, failed: 0, pending: 0, uncertain: 0, skipped: 0, stoppedByBudget: false };
  const waiting = { intervalMs: pollIntervalMs, timeoutMs: pollTimeoutMs, sleep, now };

  await pool(items, Math.min(concurrency, 2), async (item) => {
    const { key } = item;
    let record = state[key];
    const kind = (record?.modality ?? item.modality) === 'VIDEO' ? 'video' : 'image';

    if (record?.status === 'done' || record?.status === 'failed') {
      outcome.skipped++;
      return true;
    }
    if (record?.status === 'submitting' && !retryUncertain) {
      outcome.uncertain++; // the job may exist: never sent again on its own
      return true;
    }

    let hold = null;
    if (record?.job_id && record.status === 'accepted') {
      hold = budget.adopt(record.hold_cents); // resumed: already counted as in flight
    } else if (!(record?.job_id && record.status === 'downloading')) {
      // A recipe with inputs: its samples go up as input files first. An
      // upload is free and holds nothing, so one that fails leaves no trace.
      let inputRequest = null;
      if (item.inputs) {
        const fileIds = [];
        try {
          for (const [index, slot] of item.inputs.entries()) {
            const bytes = await readFile(path.join(mediaDir, slot.sample.file));
            fileIds.push(await api.uploadFile({ bytes, filename: `input-${index}.webp`, contentType: slot.sample.content_type }));
          }
        } catch (error) {
          if (error.code === 'ENOENT') {
            state[key] = { status: 'failed', slug: item.slug, model: item.model, modality: item.modality,
              error: `its sample input is not in the work folder's media folder (${path.basename(error.path ?? '')}): put the recipe's published media there first` };
            await save();
            outcome.failed++;
          } else if (error.rejected) {
            state[key] = { status: 'failed', slug: item.slug, model: item.model, modality: item.modality, error: api.redact(`its sample input could not be uploaded: ${error.message}`) };
            await save();
            outcome.failed++;
          } else {
            outcome.pending++; // no clear answer: nothing was started, so the next run tries again
          }
          return true;
        }
        inputRequest = inputFields(item.modality, item.inputs, fileIds);
      }

      const wasUncertain = record?.status === 'submitting';
      try {
        // An uncertain run still holds its money; sending it again does not hold twice.
        hold = wasUncertain ? budget.adopt(record.hold_cents) : budget.hold(item.hold);
      } catch (error) {
        if (!(error instanceof BudgetExceeded)) throw error;
        outcome.stoppedByBudget = true;
        log(error.message);
        return false;
      }
      record = state[key] = {
        status: 'submitting',
        slug: item.slug,
        model: item.model,
        modality: item.modality,
        prompt_lang: item.prompt_lang,
        prompt: item.prompt,
        params: item.params,
        left: item.left,
        // Which sample each slot was given, as the recipe's runs record it.
        ...(item.inputs ? { inputs: Object.fromEntries(item.inputs.map((slot) => [slot.slot, slot.sample.file])) } : {}),
        hold_cents: hold.amount,
        submitted_at: now().toISOString(),
      };
      await save(); // before the request: a crash from here on leaves a trace

      const body = { model: item.model, prompt: item.prompt, ...item.params, ...(inputRequest ?? {}) };
      let jobId;
      try {
        jobId = kind === 'video' ? await api.submitVideo(body) : inputRequest ? await api.submitEdit(body) : await api.submitImage(body);
      } catch (error) {
        if (error.rejected) {
          hold.release();
          state[key] = { ...record, status: 'failed', error: api.redact(error.message) };
          outcome.failed++;
        } else {
          record.error = api.redact(error.message); // stays "submitting": outcome unknown
          outcome.uncertain++;
        }
        await save();
        return true;
      }
      record = state[key] = { ...record, status: 'accepted', job_id: jobId };
      delete record.error;
      await save(); // the job id is on disk before anything else happens
    }

    // Wait for the job, unless it already finished in an earlier run.
    let job;
    try {
      job = await waitForJob(api, record.job_id, waiting);
    } catch (error) {
      record.error = api.redact(error.message);
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
      state[key] = { ...record, status: 'failed', error: api.redact(String(job.errorMessage ?? 'the job failed')) };
      await save();
      outcome.failed++;
      return true;
    }

    if (record.status === 'accepted') {
      const actual = Number.isFinite(job.actualCostCents) ? job.actualCostCents : (job.estimatedCostCents ?? record.hold_cents);
      hold.settle(actual);
      await ledger.record({ kind: 'rerun', ref: key, job_id: record.job_id, model: record.model, cents: actual });
      record = state[key] = { ...record, status: 'downloading', cost_cents: actual, generated_on: today(now()) };
      await save();
    }

    // Download at once: the links expire. Named as generate names outputs, by model, so
    // no file of the recipe's other runs is touched.
    try {
      const links = outputLinks(job);
      if (links.length === 0) throw new Error('the finished job has no output link');
      const outputs = [];
      for (const [index, link] of links.entries()) {
        const { bytes, contentType } = await api.download(link);
        const name = `${record.model}-${index}`;
        outputs.push(
          kind === 'video'
            ? await saveVideo({ bytes, contentType, mediaDir, slug: record.slug, name, ffmpeg })
            : await saveImage({ bytes, mediaDir, slug: record.slug, name }),
        );
      }
      record = state[key] = { ...record, status: 'done', outputs };
      delete record.error;
      outcome.done++;
    } catch (error) {
      record.error = api.redact(error.message); // stays "downloading": the next run fetches the job again
      outcome.pending++;
    }
    await save();
    return true;
  });
  return outcome;
}

/**
 * What the review sheet shows for each finished rerun: the new run beside
 * the recipe's first, and what the new run left out of the first one's
 * parameters.
 *
 * @param {{ state: Record<string, object>, recipes: object[], site?: string }} options
 */
export function rerunReviewItems({ state, recipes, site = 'https://godance.ai' }) {
  const bySlug = new Map(recipes.map((recipe) => [recipe.slug, recipe]));
  const items = [];
  for (const [key, record] of Object.entries(state)) {
    if (record.status !== 'done') continue;
    const recipe = bySlug.get(record.slug) ?? null;
    const first = recipe?.runs[0] ?? null;
    items.push({
      key,
      slug: record.slug,
      model: record.model,
      modality: recipe?.modality ?? record.modality,
      title: recipe?.title ?? { en: record.slug },
      published: recipe !== null,
      already: recipe ? recipe.runs.some((run) => run.model === record.model) : false,
      recipe_url: `${site}/gallery/${record.slug}`,
      prompt_lang: record.prompt_lang,
      prompt: record.prompt,
      run: { model: record.model, params: record.params, left: record.left ?? [], cost_cents: record.cost_cents, generated_on: record.generated_on, outputs: record.outputs },
      first: first
        ? { model: first.model, prompt_lang: first.prompt_lang, params: first.params, cost_cents: first.cost_cents, generated_on: first.generated_on, outputs: first.outputs }
        : null,
    });
  }
  return items;
}

const mediaOfRun = (run) => run.outputs.flatMap((output) => [output.file, output.thumb, output.poster].filter(Boolean));

/**
 * Append each approved rerun to its published recipe.
 *
 * Only runs the operator approved are written, each with the day it was
 * approved. A recipe changes in one way only: new runs at the end of its
 * runs. Its other runs, their order, its review and everything else stay as
 * they are; a change to anything else would be refused. A run is refused
 * when its recipe's prompt or samples are no longer the ones it was made
 * with, or when the recipe with it would not be valid.
 *
 * @param {object} options
 * @param {Record<string, object>} options.state reruns.json.
 * @param {Record<string, object>} options.decisions The decisions file's `runs`, by rerun key.
 * @param {string} options.contentDir
 * @param {string} options.manifestFile
 * @param {boolean} [options.apply]
 * @returns {Promise<{ appended: string[], already: string[], refused: { key: string, why: string[] }[], media: string[] }>}
 */
export async function publishReruns({ state, decisions, contentDir, manifestFile, apply = false }) {
  const result = { appended: [], already: [], refused: [], media: [] };
  const approved = Object.keys(decisions ?? {}).filter((key) => decisions[key]?.decision === 'approved');

  // The approved runs, in the order they were planned, by recipe.
  const bySlug = new Map();
  for (const key of approved) {
    const record = state[key];
    if (!record) {
      result.refused.push({ key, why: ['it is approved, but this work folder has no run for it'] });
      continue;
    }
    if (record.status !== 'done') {
      result.refused.push({ key, why: ['it is approved, but its run is not finished'] });
      continue;
    }
    if (!bySlug.has(record.slug)) bySlug.set(record.slug, []);
    bySlug.get(record.slug).push(key);
  }
  const order = Object.keys(state);
  for (const keys of bySlug.values()) keys.sort((a, b) => order.indexOf(a) - order.indexOf(b));

  for (const [slug, keys] of bySlug) {
    const fileName = recipeFileName(slug);
    const target = path.join(contentDir, fileName);
    let published;
    try {
      published = JSON.parse(await readFile(target, 'utf8'));
    } catch (error) {
      const why = error.code === 'ENOENT' ? `the recipe is not published: there is no ${fileName}` : `${fileName} could not be read: ${error.message}`;
      for (const key of keys) result.refused.push({ key, why: [why] });
      continue;
    }
    const was = checkRecipe(published, { file: fileName });
    if (was.length > 0) {
      for (const key of keys) result.refused.push({ key, why: [`the published recipe is not valid as it stands: ${was.map(formatProblem).join('; ')}`] });
      continue;
    }

    let recipe = published;
    const added = [];
    for (const key of keys) {
      const record = state[key];
      if (recipe.runs.some((run) => run.model === record.model)) {
        result.already.push(key);
        continue;
      }
      const why = [];
      if (recipe.prompt[record.prompt_lang] !== record.prompt) {
        why.push(`the recipe's ${LANGUAGE[record.prompt_lang] ?? record.prompt_lang} prompt is no longer the one this run was made with`);
      }
      const slots = recipe.inputs ?? [];
      const inputs = slots.length > 0 ? Object.fromEntries(slots.map((slot) => [slot.slot, slot.sample.file])) : null;
      if (!isDeepStrictEqual(inputs, record.inputs ?? null)) {
        why.push(inputs ? 'it was not made with the sample inputs the recipe has' : 'it was made with inputs, and the recipe has none');
      }
      if (why.length > 0) {
        result.refused.push({ key, why });
        continue;
      }
      const run = {
        model: record.model,
        prompt_lang: record.prompt_lang,
        params: record.params,
        ...(inputs ? { inputs } : {}),
        cost_cents: record.cost_cents,
        generated_on: record.generated_on,
        approved_on: decisions[key].decided_on,
        outputs: record.outputs,
      };
      const next = { ...recipe, runs: [...recipe.runs, run] };
      const problems = checkRecipe(next, { file: fileName });
      if (problems.length > 0) {
        result.refused.push({ key, why: problems.map(formatProblem) });
        continue;
      }
      recipe = next;
      added.push(key);
    }
    if (added.length === 0) continue;

    // Nothing but the new runs changes: what was published is the start of what is written.
    const kept = { ...recipe, runs: recipe.runs.slice(0, published.runs.length) };
    if (!isDeepStrictEqual(kept, published) || JSON.stringify(kept) !== JSON.stringify(published)) {
      throw new Error(`publish: ${fileName} would change in more than its new runs; nothing was written for it`);
    }
    if (apply) await writeFile(target, `${JSON.stringify(recipe, null, 2)}\n`);
    result.appended.push(...added);
    result.media.push(...recipe.runs.slice(published.runs.length).flatMap(mediaOfRun));
  }

  if (apply && result.media.length > 0) {
    const manifest = await readJson(manifestFile, { upload: [], delete: [] });
    manifest.upload = [...new Set([...manifest.upload, ...result.media])].sort();
    manifest.delete = manifest.delete.filter((file) => !result.media.includes(file));
    await writeJson(manifestFile, manifest);
  }
  return result;
}
