#!/usr/bin/env node
/**
 * Gallery import: turn entries from licensed open-source prompt lists into
 * reviewed, bilingual, reproducible recipes.
 *
 *   node scripts/gallery/cli.js <command> [options]
 *
 * Every command is a dry run unless --apply is given. A command that spends
 * money also needs --max-spend-cents. See README.md beside this file.
 */
import { readFile, readdir, writeFile, mkdir } from 'node:fs/promises';
import path from 'node:path';
import { pathToFileURL } from 'node:url';
import { readRecipeFolder } from '../gallery-schema/read-recipes.js';
import { createApi } from './lib/api.js';
import { cents, createBudget, openLedger } from './lib/budget.js';
import { hasFfmpeg } from './lib/media.js';
import { priceBook } from './lib/pricing.js';
import { oneLine } from './lib/text.js';
import { DEFAULT_CONFIG_DIR, DEFAULT_CONTENT_DIR, DEFAULT_WORK_DIR, MARKETING_ROOT, configPaths, readJson, workPaths, writeJson } from './lib/work.js';
import { createListReader, fetchEntries } from './stages/fetch.js';
import { generate, heldByRuns, planRun } from './stages/generate.js';
import { publish, remove } from './stages/publish.js';
import { checkDecisions, reviewItems, reviewSheet, standardVersionOf } from './stages/review.js';
import { REASONS, classifyEntries, compileTerms, countReasons, projectClassify, screenEntries } from './stages/screen.js';
import { planTranslation, projectTranslate, translateEntries } from './stages/translate.js';

export const STANDARD_FILE = path.join(MARKETING_ROOT, '..', 'docs', 'gallery-content-standard.md');

const HELP = `Gallery import. Every command is a dry run unless --apply is given.

  plan                 The whole pipeline as a dry run: counts at each stage and
                       projected spend. Writes nothing, makes no paid call.
  fetch                Read entries from each list at its pinned commit.
  screen               Set aside what cannot be used. --classify adds a text model.
  translate            Complete each prompt in English and Chinese.
  generate             Run each candidate on Godance and save the output.
  review               Build the review sheet.
  publish              Write the recipes the operator approved.
  remove <slug>        Take a recipe down and keep it from coming back.
  status               What each stage has done, and what has been spent.

Options
  --apply                  Do it: write state, and spend where the stage spends.
  --max-spend-cents <n>    The cap, over everything this work folder has spent.
                           Required by any stage that makes a paid call.
  --source <id>            Only this source from config/sources.json.
  --images <n>             How many image runs to make in total (default 120).
  --videos <n>             How many video runs to make in total (default 30).
  --video-seconds <n>      Clip length when a prompt names none (default 5).
  --max-video-seconds <n>  Set aside prompts written for longer clips (default 10).
  --classify               screen: also ask a text model (paid).
  --retry-failed           generate: run failed entries again.
  --retry-uncertain        generate: submit again entries whose first request got
                           no clear answer. Check GET /v1/jobs first: this can pay twice.
  --offline                Use seed prices; do not ask the API for the catalogue.
  --reason <text>          remove: why, for the removal list.
  --work-dir, --config-dir, --content-dir <path>

Environment
  GODANCE_API_KEY          Read from the environment only. Never written anywhere.
  GODANCE_API_BASE         Defaults to https://api.godance.ai`;

const FLAGS_WITH_VALUE = new Set(['max-spend-cents', 'source', 'images', 'videos', 'video-seconds', 'max-video-seconds', 'reason', 'work-dir', 'config-dir', 'content-dir']);
const FLAGS = new Set([...FLAGS_WITH_VALUE, 'apply', 'classify', 'retry-failed', 'retry-uncertain', 'offline', 'help']);

class Misuse extends Error {}

export function parseArgs(argv) {
  const flags = {};
  const positional = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (!arg.startsWith('--')) {
      positional.push(arg);
      continue;
    }
    const [name, inline] = arg.slice(2).split(/=(.*)/s);
    if (!FLAGS.has(name)) throw new Misuse(`unknown option --${name}`);
    if (FLAGS_WITH_VALUE.has(name)) {
      const value = inline ?? argv[++i];
      if (value === undefined) throw new Misuse(`--${name} needs a value`);
      flags[name] = value;
    } else {
      flags[name] = true;
    }
  }
  return { command: positional[0] ?? null, positional: positional.slice(1), flags };
}

function wholeNumber(flags, name, fallback) {
  if (flags[name] === undefined) return fallback;
  const value = Number(flags[name]);
  if (!Number.isInteger(value) || value < 0) throw new Misuse(`--${name} must be a whole number, zero or more`);
  return value;
}

const plural = (n, noun) => `${n} ${noun}${n === 1 ? '' : 's'}`;

/** A path as it is printed: relative to marketing/ when it is inside it, in full otherwise. */
function shown(file) {
  const relative = path.relative(MARKETING_ROOT, file);
  return relative.startsWith('..') ? file : relative;
}
const roundUp = (amount) => Math.ceil(amount - 1e-9);

/** Candidates to run, spread across categories so one category does not take every slot. */
export function pickForGeneration(candidates, runs, limits) {
  const picked = [];
  for (const modality of ['IMAGE', 'VIDEO']) {
    const limit = modality === 'IMAGE' ? limits.images : limits.videos;
    const all = candidates.filter(({ entry }) => entry.modality === modality);
    const started = all.filter(({ entry }) => runs[entry.id] && runs[entry.id].status !== 'failed');
    picked.push(...started); // whatever was started is always carried on
    let room = limit - started.length;
    const queues = new Map();
    for (const item of all) {
      if (runs[item.entry.id]) continue;
      const key = item.entry.category ?? '';
      if (!queues.has(key)) queues.set(key, []);
      queues.get(key).push(item);
    }
    while (room > 0 && [...queues.values()].some((queue) => queue.length > 0)) {
      for (const queue of queues.values()) {
        if (room > 0 && queue.length > 0) {
          picked.push(queue.shift());
          room--;
        }
      }
    }
  }
  return picked;
}

/**
 * @param {string[]} argv
 * @param {{ env?: Record<string, string | undefined>, fetch?: typeof fetch, out?: (line: string) => void, now?: () => Date, sleep?: Function }} io
 * @returns {Promise<number>} The exit code.
 */
export async function main(argv, io = {}) {
  const env = io.env ?? process.env;
  const fetch = io.fetch ?? globalThis.fetch;
  const now = io.now ?? (() => new Date());

  // The key is read here, handed to the API client, and kept nowhere else.
  const api = createApi({ base: env.GODANCE_API_BASE || 'https://api.godance.ai', key: env.GODANCE_API_KEY || undefined, fetch, sleep: io.sleep });
  const write = io.out ?? ((line) => console.log(line));
  const out = (line = '') => write(api.redact(line));

  let args;
  try {
    args = parseArgs(argv);
    if (args.flags.help || !args.command) {
      out(HELP);
      return args.command || args.flags.help ? 0 : 2;
    }
    return await run(args);
  } catch (error) {
    if (error instanceof Misuse) {
      out(`gallery: ${error.message}\n\n${HELP}`);
      return 2;
    }
    out(`gallery: ${error.message}`);
    return 1;
  }

  async function run({ command, positional, flags }) {
    const work = workPaths(flags['work-dir'] ? path.resolve(flags['work-dir']) : DEFAULT_WORK_DIR);
    const config = configPaths(flags['config-dir'] ? path.resolve(flags['config-dir']) : DEFAULT_CONFIG_DIR);
    const contentDir = flags['content-dir'] ? path.resolve(flags['content-dir']) : DEFAULT_CONTENT_DIR;
    const apply = Boolean(flags.apply);
    const cap = flags['max-spend-cents'] === undefined ? null : wholeNumber(flags, 'max-spend-cents');
    const limits = { images: wholeNumber(flags, 'images', 120), videos: wholeNumber(flags, 'videos', 30) };
    const videoSeconds = wholeNumber(flags, 'video-seconds', 5);
    const maxVideoSeconds = wholeNumber(flags, 'max-video-seconds', 10);

    const allSources = (await readJson(config.sources)).sources;
    const sources = flags.source ? allSources.filter((source) => source.id === flags.source) : allSources;
    if (sources.length === 0) throw new Misuse(`no source "${flags.source}" in ${config.sources}`);
    const modelMap = await readJson(config.modelMap);
    const terms = compileTerms(await readJson(config.screenTerms, { rules: {} }));
    const removed = (await readJson(config.removalList, { removed: [] })).removed;
    const screenOptions = { terms, maxVideoSeconds, maxPromptChars: Object.fromEntries(allSources.map((source) => [source.id, source.max_prompt_chars])) };
    const standard = await readFile(STANDARD_FILE, 'utf8').catch(() => null);

    const header = (what) => out(apply ? `gallery ${what}` : `gallery ${what}: dry run. Nothing is written and no paid call is made. Add --apply to do it.`);

    async function loadPrices() {
      if (flags.offline) return priceBook(null);
      try {
        return priceBook(await api.models());
      } catch {
        return priceBook(null);
      }
    }
    const describePrices = (prices) =>
      out(prices.source === 'live' ? `prices: the live catalogue at ${api.origin}` : 'prices: seed prices (the catalogue was not asked, or could not be reached)');

    /** A paid stage: needs the key and a cap, and gets a budget over everything spent so far. */
    async function paidStage(runs = {}) {
      if (cap === null) throw new Misuse('a run that spends money needs --max-spend-cents');
      if (!api.hasKey) throw new Error('GODANCE_API_KEY is not set');
      const ledger = await openLedger(work.ledger, { now });
      const budget = createBudget({ cap, spent: ledger.spent, held: heldByRuns(runs) });
      return { ledger, budget };
    }
    const spendLine = (budget, before) =>
      out(`spent: ${cents(roundUp(budget.spent - before))} in this run; ${cents(roundUp(budget.spent))} in total against a cap of ${cents(budget.cap)}` +
        (budget.held > 0 ? `; ${cents(budget.held)} still held by unfinished jobs` : ''));

    const needState = async (file, stage) => {
      const data = await readJson(file, null);
      if (data === null) throw new Error(`there is no ${path.basename(file)} yet: run "${stage} --apply" first`);
      return data;
    };
    const candidatesOf = (entries, screened) => entries.filter((entry) => screened[entry.id]?.status === 'candidate');
    const inSources = (entries) => entries.filter((entry) => sources.some((source) => source.id === entry.source));

    function printFetch(report) {
      for (const source of report) {
        out(`\nsource ${source.id}  ${source.repo} @ ${source.commit.slice(0, 12)}  (${source.licence})`);
        if (source.refused) out(`  REFUSED: ${source.refused}. Nothing was read from this list.`);
        else out(`  fetch      ${source.read} read · ${plural(source.duplicates, 'duplicate')} · ${source.removed} on the removal list · ${source.kept} kept`);
      }
    }
    function printScreen(entries, screened, indent = '  ') {
      const ids = entries.map((entry) => entry.id);
      const aside = ids.filter((id) => screened[id].status === 'set-aside');
      out(`${indent}screen     ${aside.length} set aside · ${plural(ids.length - aside.length, 'candidate')}`);
      const counts = countReasons(screened, aside);
      for (const [rule, n] of Object.entries(counts).sort((a, b) => b[1] - a[1])) out(`${indent}           ${String(n).padStart(5)}  ${REASONS[rule] ?? rule}`);
      if (aside.length > 0) out(`${indent}           (an entry set aside for several reasons counts under each)`);
    }

    switch (command) {
      case 'plan': {
        out('gallery plan: dry run over every stage. Nothing is written and no paid call is made.');
        const prices = await loadPrices();
        describePrices(prices);
        const fetched = await fetchEntries({ sources, removed, reader: createListReader({ fetch, cacheDir: work.sources, save: false }) });
        const screened = screenEntries(fetched.entries, screenOptions);
        const runs = await readJson(work.runs, {});
        let translateCents = 0;
        let classifyCents = 0;
        const lines = [];
        let generateCents = 0;

        for (const report of fetched.sources) {
          printFetch([report]);
          if (report.refused) continue;
          const source = sources.find((each) => each.id === report.id);
          const entries = fetched.entries.filter((entry) => entry.source === source.id);
          printScreen(entries, screened);
          const candidates = candidatesOf(entries, screened);
          const translation = projectTranslate(candidates, { prices, model: modelMap.text.translate });
          translateCents += translation.cents;
          if (standard) classifyCents += projectClassify(candidates, { prices, model: modelMap.text.classify, standard });
          out(
            translation.needModel === 0
              ? `  translate  none of the ${candidates.length} need a text model: the list supplies both languages`
              : translation.needModel === candidates.length
                ? `  translate  all ${candidates.length} need a text model for one language`
                : `  translate  ${translation.needModel} of ${candidates.length} need a text model for one language; the list supplies both for the other ${candidates.length - translation.needModel}`,
          );

          const items = candidates.map((entry) => {
            const plan = planTranslation(entry);
            return { entry, translated: { prompt: { ...plan.prompt, [entry.lang]: plan.prompt[entry.lang] ?? entry.prompt }, prompt_lang: entry.lang } };
          });
          const picked = pickForGeneration(items, runs, limits).filter(({ entry }) => entry.source === source.id);
          const byHold = new Map();
          let unserved = 0;
          for (const { entry, translated } of picked) {
            const planned = planRun(entry, translated, { prices, modelMap, videoSeconds });
            if (planned.error) {
              unserved++;
              continue;
            }
            const label = entry.modality === 'VIDEO' ? `${planned.params.seconds} s on ${planned.model}` : `on ${planned.model}`;
            const key = `${label}|${planned.hold}`;
            byHold.set(key, (byHold.get(key) ?? 0) + 1);
            generateCents += planned.hold;
          }
          out(`  generate   ${picked.length - unserved} of ${plural(candidates.length, 'candidate')} would run (limit: ${source.modality === 'VIDEO' ? limits.videos : limits.images})`);
          for (const [key, n] of byHold) {
            const [label, hold] = key.split('|');
            lines.push(`  ${source.modality === 'VIDEO' ? 'videos' : 'images'}  ${String(n).padStart(4)} × ${cents(Number(hold))}, ${label}`.padEnd(58) + `${String(n * Number(hold)).padStart(6)} cents`);
          }
          if (unserved > 0) out(`             ${unserved} cannot run: Godance does not serve "${source.model}" and model-map.json names no other`);
        }

        out('\nprojected spend');
        for (const line of lines) out(line);
        out(`${'  translate (text model)'.padEnd(58)}${String(roundUp(translateCents)).padStart(6)} cents`);
        out(`${'  total without the classifier'.padEnd(58)}${String(generateCents + roundUp(translateCents)).padStart(6)} cents`);
        if (standard) out(`${'  optional: screen --classify (text model)'.padEnd(58)}${String(roundUp(classifyCents)).padStart(6)} cents`);
        else out('  the content standard is not in docs/ yet, so the classifier cannot be projected');
        out('\nA projection, from catalogue prices. What is recorded for a run is its job\'s actualCostCents.');
        return fetched.sources.some((source) => source.refused) ? 1 : 0;
      }

      case 'fetch': {
        header('fetch');
        const fetched = await fetchEntries({ sources, removed, reader: createListReader({ fetch, cacheDir: work.sources, save: apply }) });
        printFetch(fetched.sources);
        if (apply) {
          // Entries of sources not fetched this time are kept as they were.
          const earlier = await readJson(work.entries, { entries: [], dropped: [], sources: [] });
          const ids = new Set(sources.map((source) => source.id));
          await writeJson(work.entries, {
            fetched_at: now().toISOString(),
            sources: [...earlier.sources.filter((source) => !ids.has(source.id)), ...fetched.sources],
            entries: [...earlier.entries.filter((entry) => !ids.has(entry.source)), ...fetched.entries],
            dropped: fetched.dropped,
          });
          out(`\nwrote ${shown(work.entries)}`);
        }
        return fetched.sources.some((source) => source.refused) ? 1 : 0;
      }

      case 'screen': {
        header('screen');
        const entries = inSources((await needState(work.entries, 'fetch')).entries);
        const earlier = await readJson(work.screened, {});
        const screened = screenEntries(entries, screenOptions);
        // What the classifier already said is kept: it was paid for.
        for (const [id, record] of Object.entries(screened)) {
          const before = earlier[id];
          if (!before?.classified) continue;
          record.classified = true;
          record.notes.push(...before.notes.filter((note) => note.startsWith('classifier:')));
          const said = before.reasons.filter((reason) => reason.note.startsWith('classifier:') || reason.rule === 'classifier');
          if (said.length > 0) {
            record.status = 'set-aside';
            record.reasons.push(...said);
          }
        }
        const all = { ...earlier, ...screened };

        if (flags.classify) {
          if (!standard) throw new Error('the classifier reads docs/gallery-content-standard.md, which does not exist yet');
          const prices = await loadPrices();
          const todo = candidatesOf(entries, screened).filter((entry) => !screened[entry.id].classified);
          const projected = projectClassify(todo, { prices, model: modelMap.text.classify, standard });
          out(`classifier: ${plural(todo.length, 'candidate')} to read with ${modelMap.text.classify}, projected at about ${cents(roundUp(projected))}`);
          if (apply) {
            const { ledger, budget } = await paidStage();
            const before = budget.spent;
            const result = await classifyEntries(entries, screened, {
              api, prices, budget, ledger, model: modelMap.text.classify, standard,
              save: () => writeJson(work.screened, { ...earlier, ...screened }),
            });
            out(`classifier: read ${result.classified}, set aside ${result.setAside}, ${result.failed} failed and will be tried again` +
              (result.stoppedByBudget ? '; stopped at the cap' : ''));
            spendLine(budget, before);
          }
        }
        for (const source of sources) {
          out(`\nsource ${source.id}`);
          printScreen(entries.filter((entry) => entry.source === source.id), screened);
        }
        if (apply) {
          await writeJson(work.screened, all);
          out(`\nwrote ${shown(work.screened)}`);
        }
        return 0;
      }

      case 'translate': {
        header('translate');
        const entries = inSources((await needState(work.entries, 'fetch')).entries);
        const screened = await needState(work.screened, 'screen');
        const candidates = candidatesOf(entries, screened);
        const state = await readJson(work.translated, {});
        const prices = await loadPrices();
        const todo = candidates.filter((entry) => state[entry.id]?.status !== 'done');
        const projection = projectTranslate(todo, { prices, model: modelMap.text.translate });
        out(`${plural(candidates.length, 'candidate')}; ${todo.length} still to do, of which ${projection.needModel} need ${modelMap.text.translate} for one language`);
        out(`projected: about ${cents(roundUp(projection.cents))}`);
        if (!apply) return 0;

        const taken = new Set((await readdir(contentDir).catch(() => [])).filter((name) => name.endsWith('.json')).map((name) => name.slice(0, -5)));
        const free = projection.cents === 0;
        const { ledger, budget } = free ? { ledger: null, budget: createBudget({ cap: 0 }) } : await paidStage();
        const before = budget.spent;
        const result = await translateEntries(candidates, state, {
          api, prices, budget, ledger, model: modelMap.text.translate, takenSlugs: taken,
          save: () => writeJson(work.translated, state),
        });
        await writeJson(work.translated, state);
        out(`translated ${result.done}, ${result.failed} failed${result.stoppedByBudget ? '; stopped at the cap' : ''}`);
        for (const [id, record] of Object.entries(state)) if (record.status === 'failed') out(`  failed ${id}: ${record.reason}`);
        if (!free) spendLine(budget, before);
        return result.stoppedByBudget ? 1 : 0;
      }

      case 'generate': {
        if (apply && cap === null) throw new Misuse('a run that spends money needs --max-spend-cents');
        header('generate');
        const entries = inSources((await needState(work.entries, 'fetch')).entries);
        const screened = await needState(work.screened, 'screen');
        const translated = await needState(work.translated, 'translate');
        const runs = await readJson(work.runs, {});
        if (flags['retry-failed'] && apply) for (const [id, run] of Object.entries(runs)) if (run.status === 'failed') delete runs[id];
        const prices = await loadPrices();
        describePrices(prices);

        const ready = candidatesOf(entries, screened).filter((entry) => translated[entry.id]?.status === 'done').map((entry) => ({ entry, translated: translated[entry.id] }));
        const picked = pickForGeneration(ready, runs, limits);
        const fresh = picked.filter(({ entry }) => !runs[entry.id]);
        let projected = 0;
        for (const { entry, translated: record } of fresh) projected += planRun(entry, record, { prices, modelMap, videoSeconds }).hold ?? 0;
        const byStatus = (status) => Object.values(runs).filter((run) => run.status === status).length;
        out(`${plural(ready.length, 'candidate')} translated; ${byStatus('done')} done, ${byStatus('accepted') + byStatus('downloading')} to resume, ${byStatus('submitting')} uncertain, ${byStatus('failed')} failed`);
        out(`${plural(fresh.length, 'new run')} would start, holding ${cents(projected)}`);
        if (!apply) return 0;

        const { ledger, budget } = await paidStage(runs);
        const before = budget.spent;
        const ffmpeg = await hasFfmpeg();
        if (!ffmpeg && picked.some(({ entry }) => entry.modality === 'VIDEO')) out('warning: ffmpeg is not installed, so videos get no poster');
        const result = await generate(picked, runs, {
          api, prices, budget, ledger, modelMap, mediaDir: work.media, videoSeconds, ffmpeg,
          retryUncertain: Boolean(flags['retry-uncertain']),
          save: () => writeJson(work.runs, runs),
          sleep: io.sleep, now, log: out,
        });
        await writeJson(work.runs, runs);
        out(`done ${result.done} · failed ${result.failed} · still running ${result.pending} · uncertain ${result.uncertain}` + (result.stoppedByBudget ? ' · stopped at the cap' : ''));
        for (const [id, run] of Object.entries(runs)) if (run.error) out(`  ${run.status} ${id}: ${oneLine(run.error, 160)}`);
        if (result.uncertain > 0) out('uncertain: a request was sent and no clear answer came back. Check GET /v1/jobs before using --retry-uncertain.');
        spendLine(budget, before);
        return 0;
      }

      case 'review': {
        header('review');
        const entries = inSources((await needState(work.entries, 'fetch')).entries);
        const items = reviewItems({ entries, screened: await needState(work.screened, 'screen'), translated: await needState(work.translated, 'translate'), runs: await needState(work.runs, 'generate') });
        const version = standardVersionOf(standard);
        if (version === null) throw new Error('docs/gallery-content-standard.md is missing or states no version: the review needs one to record');
        const decisions = await readJson(work.decisions, null);
        out(`${plural(items.length, 'candidate')} with a finished run; content standard version ${version}` + (decisions ? `; carrying on from ${Object.keys(decisions.decisions ?? {}).length} earlier decisions` : ''));
        if (apply) {
          await mkdir(work.review, { recursive: true });
          await writeFile(work.reviewSheet, reviewSheet({ items, standardVersion: version, decisions, builtAt: now().toISOString() }));
          out(`wrote ${shown(work.reviewSheet)}: open it in a browser, decide, and save decisions.json beside it`);
        }
        return 0;
      }

      case 'publish': {
        header('publish');
        const decisions = await readJson(work.decisions, null);
        if (!decisions) throw new Error(`there are no decisions yet: the operator saves ${shown(work.decisions)} from the review sheet`);
        const wrong = checkDecisions(decisions);
        if (wrong.length > 0) throw new Error(`the decisions file cannot be used:\n  ${wrong.join('\n  ')}`);
        const result = await publish({
          entries: (await needState(work.entries, 'fetch')).entries,
          translated: await needState(work.translated, 'translate'),
          runs: await needState(work.runs, 'generate'),
          decisions, contentDir, manifestFile: work.manifest, apply, now,
        });
        const made = Object.values(decisions.decisions);
        out(`decisions: ${made.filter((d) => d.decision === 'approved').length} approved, ${made.filter((d) => d.decision === 'rejected').length} rejected`);
        out(`${apply ? 'wrote' : 'would write'} ${plural(result.written.length, 'recipe')}; ${result.already.length} already published; ${result.refused.length} refused`);
        for (const slug of result.written) out(`  + ${slug}.json`);
        for (const { slug, why } of result.refused) out(`  refused ${slug}:\n    ${why.join('\n    ')}`);
        if (apply) {
          const folder = await readRecipeFolder(contentDir);
          out(folder.problems.length === 0 ? `the gallery folder is valid: ${plural(folder.recipes.length, 'recipe')}` : `the gallery folder has ${plural(folder.problems.length, 'problem')}: run npm run validate`);
          if (result.media.length > 0) out(`media to upload is listed in ${shown(work.manifest)} (${plural(result.media.length, 'file')}); media is never committed`);
          if (folder.problems.length > 0) return 1;
        }
        return result.refused.length > 0 ? 1 : 0;
      }

      case 'remove': {
        if (positional.length !== 1) throw new Misuse('remove takes one slug');
        header('remove');
        const result = await remove(positional[0], { contentDir, removalFile: config.removalList, manifestFile: work.manifest, reason: flags.reason ?? '', apply, now });
        out(`${apply ? 'removed' : 'would remove'} ${result.slug}.json`);
        out(`${apply ? 'added' : 'would add'} ${result.original_url} to the removal list`);
        out(`${plural(result.media.length, 'media file')} ${apply ? 'listed' : 'would be listed'} for deletion from public storage`);
        if (apply) out('commit the deleted recipe and config/removal-list.json together');
        return 0;
      }

      case 'status': {
        const fetched = await readJson(work.entries, null);
        if (!fetched) {
          out('nothing has been fetched yet');
          return 0;
        }
        const screened = await readJson(work.screened, {});
        const translated = await readJson(work.translated, {});
        const runs = await readJson(work.runs, {});
        const decisions = await readJson(work.decisions, { decisions: {} });
        const ledger = await openLedger(work.ledger, { now });
        for (const source of fetched.sources) {
          const entries = fetched.entries.filter((entry) => entry.source === source.id);
          printFetch([source]);
          if (entries.some((entry) => screened[entry.id])) printScreen(entries.filter((entry) => screened[entry.id]), screened);
          const count = (test) => entries.filter(test).length;
          out(`  translate  ${count((e) => translated[e.id]?.status === 'done')} done · ${count((e) => translated[e.id]?.status === 'failed')} failed`);
          out(`  generate   ${count((e) => runs[e.id]?.status === 'done')} done · ${count((e) => ['accepted', 'downloading'].includes(runs[e.id]?.status))} unfinished · ${count((e) => runs[e.id]?.status === 'submitting')} uncertain · ${count((e) => runs[e.id]?.status === 'failed')} failed`);
          out(`  review     ${count((e) => decisions.decisions[e.id]?.decision === 'approved')} approved · ${count((e) => decisions.decisions[e.id]?.decision === 'rejected')} rejected`);
        }
        const kinds = {};
        for (const event of ledger.events) kinds[event.kind] = (kinds[event.kind] ?? 0) + event.cents;
        out(`\nspent: ${cents(roundUp(ledger.spent))} in total` + (Object.keys(kinds).length ? ` (${Object.entries(kinds).map(([kind, amount]) => `${kind} ${cents(roundUp(amount))}`).join(', ')})` : '') +
          (cap !== null ? ` against a cap of ${cents(cap)}` : ''));
        return 0;
      }

      default:
        throw new Misuse(`unknown command "${command}"`);
    }
  }
}

if (process.argv[1] && import.meta.url === pathToFileURL(process.argv[1]).href) {
  process.exitCode = await main(process.argv.slice(2));
}
