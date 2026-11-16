/**
 * review: build the sheet the operator works through.
 *
 * One static page in the work folder. For every candidate with a finished
 * run it shows the output, the prompt in both languages, the source and
 * author, the screening notes and the cost, with an Approve or Reject
 * choice and a reason for a rejection.
 *
 * The page keeps the choices in the browser and saves them as
 * decisions.json, which the operator puts in the review folder. This tool
 * only reads that file. It never writes it: a session cannot approve.
 *
 * Prompts and titles are third-party text. The page never builds HTML from
 * them: the data is embedded as JSON and put on the page as text.
 *
 * A prompt that keeps its blanks shows its template in both languages, each
 * {{name}} marker marked, and the default of every variable in English and
 * in Chinese: the defaults are held to the content standard like the prompt.
 *
 * An input recipe shows each sample input before the output it produced,
 * with its slot: the name and the hints, which the operator can change
 * here, the prompt the sample was generated from, and what it cost. The
 * sample is held to the content standard like the output.
 *
 * A new run on a published recipe (rerun) is shown beside the recipe's first
 * run, with what each cost and which of the first run's parameters the new
 * model did not take. It is approved or rejected on its own, and its decision
 * is saved under `runs` in decisions.json, by `<slug>@<model>`. Approving it
 * changes nothing about the recipe's own review.
 */
import { parseTemplate } from '../../../src/lib/gallery-template.js';
import { recipeInputs } from './sample.js';

/** JSON safe inside a <script> element. */
const embed = (data) =>
  JSON.stringify(data)
    .replace(/</g, '\\u003c')
    .replaceAll(String.fromCharCode(0x2028), '\\u2028')
    .replaceAll(String.fromCharCode(0x2029), '\\u2029');

/** What the sheet shows for each candidate that has a finished run. `samples` holds the slots of input recipes. */
export function reviewItems({ entries, screened, translated, runs, samples = {} }) {
  const items = [];
  for (const entry of entries) {
    const record = translated[entry.id];
    const run = runs[entry.id];
    if (record?.status !== 'done' || run?.status !== 'done') continue;
    items.push({
      id: entry.id,
      slug: record.slug,
      modality: entry.modality,
      category: entry.category ?? null,
      title: record.title,
      prompt: record.prompt,
      // The templates cut at their markers here, so the page only has to put text on the page.
      template: record.prompt.template
        ? { en: parseTemplate(record.prompt.template.en).parts, zh: parseTemplate(record.prompt.template.zh).parts }
        : null,
      changes: record.changes,
      run: { model: run.model, prompt_lang: run.prompt_lang, params: run.params, cost_cents: run.cost_cents, generated_on: run.generated_on, outputs: run.outputs },
      // An input recipe: its slots, each with the sample the run was made with. Null for any other.
      inputs: run.inputs ? recipeInputs(samples[entry.id]) : null,
      input_subject: run.inputs ? (samples[entry.id]?.input ?? null) : null,
      source: { list: entry.list, list_commit: entry.list_commit, licence: entry.licence, author: entry.author, author_url: entry.author_url, original_url: entry.original_url },
      // What screening noted, then what translation did: a template that could not be kept says why.
      notes: [...(screened[entry.id]?.notes ?? []), ...(record.notes ?? [])],
    });
  }
  return items;
}

/**
 * The review sheet as one HTML document.
 *
 * @param {object} options
 * @param {object[]} options.items From reviewItems.
 * @param {object[]} [options.reruns] New runs on published recipes, from rerunReviewItems.
 * @param {number} options.standardVersion The version of the content standard the review is made against.
 * @param {object | null} [options.decisions] An earlier decisions.json, to carry on from.
 * @param {string} [options.mediaBase] Where the media folder is, relative to the page.
 */
export function reviewSheet({ items, reruns = [], standardVersion, decisions = null, mediaBase = '../media/', builtAt = new Date().toISOString() }) {
  const data = { items, reruns, standardVersion, builtAt, mediaBase, earlier: decisions?.decisions ?? {}, earlierRuns: decisions?.runs ?? {} };
  return `<!doctype html>
<html lang="en">
<head>
<meta charset="utf-8">
<meta name="viewport" content="width=device-width, initial-scale=1">
<meta http-equiv="Content-Security-Policy" content="default-src 'none'; img-src 'self' file:; media-src 'self' file:; style-src 'unsafe-inline'; script-src 'unsafe-inline'">
<title>Gallery review</title>
<style>
  :root { color-scheme: light dark; --line: #8884; --muted: #777; --ok: #1a7f37; --no: #c62828; }
  body { font: 15px/1.5 system-ui, sans-serif; margin: 0; padding: 0 16px 96px; max-width: 1100px; margin-inline: auto; }
  header { position: sticky; top: 0; background: Canvas; padding: 12px 0; border-bottom: 1px solid var(--line); z-index: 1; }
  header h1 { font-size: 18px; margin: 0 0 4px; }
  .bar { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; }
  .count { font-variant-numeric: tabular-nums; }
  button { font: inherit; padding: 6px 12px; cursor: pointer; }
  .card { border: 1px solid var(--line); border-radius: 8px; margin: 16px 0; padding: 16px; display: grid; grid-template-columns: minmax(0, 360px) minmax(0, 1fr); gap: 16px; }
  .card.approved { border-color: var(--ok); } .card.rejected { border-color: var(--no); }
  @media (max-width: 760px) { .card { grid-template-columns: 1fr; } }
  .media img, .media video { max-width: 100%; height: auto; border-radius: 4px; background: #8882; }
  h2 { font-size: 16px; margin: 0; } .zh-title { color: var(--muted); }
  .meta { color: var(--muted); font-size: 13px; margin: 4px 0 8px; overflow-wrap: anywhere; }
  pre { white-space: pre-wrap; overflow-wrap: anywhere; background: #8881; padding: 8px; border-radius: 4px; max-height: 260px; overflow: auto; margin: 4px 0 8px; font: 13px/1.45 ui-monospace, monospace; }
  .label { font-size: 12px; text-transform: uppercase; letter-spacing: .04em; color: var(--muted); }
  .notes { margin: 0 0 8px; padding-left: 18px; font-size: 13px; }
  .decide { display: flex; flex-wrap: wrap; gap: 12px; align-items: center; border-top: 1px solid var(--line); padding-top: 12px; }
  .decide input[type=text] { flex: 1 1 240px; font: inherit; padding: 6px 8px; }
  .warn { color: var(--no); font-size: 13px; }
  mark { background: #f5c54255; color: inherit; border-radius: 3px; padding: 0 2px; font-weight: 600; }
  table.vars { border-collapse: collapse; font-size: 13px; margin: 4px 0 8px; }
  table.vars th, table.vars td { border: 1px solid var(--line); padding: 3px 8px; text-align: left; vertical-align: top; overflow-wrap: anywhere; }
  table.vars th { color: var(--muted); font-weight: 600; }
  table.vars input { font: inherit; width: 100%; min-width: 9em; box-sizing: border-box; padding: 2px 4px; }
  h2.section { font-size: 17px; margin: 32px 0 4px; }
</style>
</head>
<body>
<header>
  <h1>Gallery review <span class="meta" id="standard"></span></h1>
  <div class="bar">
    <span class="count" id="counts"></span>
    <label><input type="checkbox" id="undecided"> show undecided only</label>
    <button id="save">Save decisions.json</button>
  </div>
  <div class="meta">Apply the content standard to the output as well as to the prompt. A rejection needs a reason. When unsure, reject.
  Save the file into this page's folder (review/decisions.json); <code>publish</code> reads it from there.</div>
</header>
<main id="cards"></main>
<script type="application/json" id="data">${embed(data)}</script>
<script>
(() => {
  const data = JSON.parse(document.getElementById('data').textContent);
  const storeKey = 'gallery-review:' + data.items.map((item) => item.id).join(',').length + ':' + (data.items[0]?.id ?? '');
  let decisions = { ...data.earlier };
  try { decisions = { ...decisions, ...JSON.parse(localStorage.getItem(storeKey) ?? '{}') }; } catch {}
  // New runs on published recipes are decided on their own, by <slug>@<model>.
  const runStoreKey = 'gallery-review-runs:' + data.reruns.map((item) => item.key).join(',').length + ':' + (data.reruns[0]?.key ?? '');
  let runDecisions = { ...data.earlierRuns };
  try { runDecisions = { ...runDecisions, ...JSON.parse(localStorage.getItem(runStoreKey) ?? '{}') }; } catch {}

  const el = (tag, props = {}, ...children) => {
    const node = Object.assign(document.createElement(tag), props);
    for (const child of children) node.append(child);
    return node;
  };
  const link = (text, url) => (/^https:\\/\\//.test(url ?? '') ? el('a', { textContent: text, href: url, target: '_blank', rel: 'noopener noreferrer' }) : el('span', { textContent: text }));
  const today = () => new Date().toISOString().slice(0, 10);
  const persist = () => { try { localStorage.setItem(storeKey, JSON.stringify(decisions)); } catch {} };
  const persistRuns = () => { try { localStorage.setItem(runStoreKey, JSON.stringify(runDecisions)); } catch {} };
  const centsText = (amount) => amount + ' cent' + (amount === 1 ? '' : 's');

  document.getElementById('standard').textContent = 'content standard version ' + data.standardVersion + ' · built ' + data.builtAt.slice(0, 16).replace('T', ' ');
  const cards = document.getElementById('cards');
  const nodes = new Map();
  const runNodes = new Map();

  function refresh() {
    let approved = 0, rejected = 0;
    const mark = (node, made) => {
      node.card.classList.toggle('approved', made?.decision === 'approved');
      node.card.classList.toggle('rejected', made?.decision === 'rejected');
      node.warn.textContent = made?.decision === 'rejected' && !made.reason.trim() ? 'A rejection needs a reason.' : '';
      node.card.hidden = document.getElementById('undecided').checked && Boolean(made);
      if (made?.decision === 'approved') approved++;
      if (made?.decision === 'rejected') rejected++;
    };
    for (const item of data.items) mark(nodes.get(item.id), decisions[item.id]);
    for (const item of data.reruns) mark(runNodes.get(item.key), runDecisions[item.key]);
    const total = data.items.length + data.reruns.length;
    document.getElementById('counts').textContent =
      data.items.length + ' candidates · ' + (data.reruns.length ? data.reruns.length + ' new runs · ' : '') +
      approved + ' approved · ' + rejected + ' rejected · ' + (total - approved - rejected) + ' undecided';
  }

  for (const item of data.items) {
    const media = el('div', { className: 'media' });
    // An input recipe: each sample input first, then what it produced.
    const slotFields = [];
    for (const slot of item.inputs ?? []) {
      media.append(el('div', { className: 'label', textContent: 'Sample input: ' + slot.slot + ' (' + slot.role + ')' }));
      media.append(el('img', { loading: 'lazy', src: data.mediaBase + slot.sample.file, width: slot.sample.width, height: slot.sample.height, alt: '' }));
      media.append(el('div', { className: 'meta', textContent: slot.sample.width + ' × ' + slot.sample.height + ' · generated on ' + slot.sample.generated_from.model
        + ' · ' + slot.sample.generated_from.cost_cents + ' cent' + (slot.sample.generated_from.cost_cents === 1 ? '' : 's') }));
    }
    if (item.inputs) media.append(el('div', { className: 'label', textContent: 'Output' }));
    for (const output of item.run.outputs) {
      if (item.modality === 'VIDEO') {
        const video = el('video', { controls: true, preload: 'metadata', src: data.mediaBase + output.file });
        if (output.poster) video.poster = data.mediaBase + output.poster;
        media.append(video);
      } else {
        media.append(el('img', { loading: 'lazy', src: data.mediaBase + output.file, width: output.width, height: output.height, alt: '' }));
      }
      media.append(el('div', { className: 'meta', textContent: output.width + ' × ' + output.height + (output.duration_seconds ? ' · ' + output.duration_seconds + ' s' : '') }));
    }

    const body = el('div');
    body.append(el('h2', { textContent: item.title.en }));
    if (item.title.zh) body.append(el('div', { className: 'zh-title', lang: 'zh-Hans', textContent: item.title.zh }));
    const cost = item.run.cost_cents + ' cent' + (item.run.cost_cents === 1 ? '' : 's');
    body.append(el('div', { className: 'meta', textContent:
      [item.modality, item.category, item.run.model, JSON.stringify(item.run.params), cost, 'generated ' + item.run.generated_on, 'run on the ' + (item.run.prompt_lang === 'zh' ? 'Chinese' : 'English') + ' prompt', item.slug].filter(Boolean).join(' · ') }));

    const source = el('div', { className: 'meta' });
    source.append('by ', link(item.source.author, item.source.author_url), ' · ', link('original post', item.source.original_url),
      ' · ' + item.source.list + ' @ ' + item.source.list_commit.slice(0, 10) + ' · ' + item.source.licence);
    if (item.changes.length) source.append(' · changes: ' + item.changes.join(', '));
    body.append(source);

    body.append(el('div', { className: 'label', textContent: 'Prompt, English' }), el('pre', { textContent: item.prompt.en }));
    body.append(el('div', { className: 'label', textContent: 'Prompt, Chinese (' + (item.prompt.zh_origin === 'machine' ? 'machine-translated' : 'from the list') + ')' }),
      el('pre', { lang: 'zh-Hans', textContent: item.prompt.zh }));
    if (item.template) {
      // The template with its variables marked, then every default in both languages.
      const marked = (parts, lang) => {
        const pre = el('pre', lang ? { lang } : {});
        for (const part of parts) pre.append('name' in part ? el('mark', { textContent: '{{' + part.name + '}}' }) : part.text);
        return pre;
      };
      body.append(el('div', { className: 'label', textContent: 'Template, English' }), marked(item.template.en));
      body.append(el('div', { className: 'label', textContent: 'Template, Chinese' }), marked(item.template.zh, 'zh-Hans'));
      body.append(el('div', { className: 'label', textContent: 'Variables and their defaults (the standard applies to these too)' }));
      const table = el('table', { className: 'vars' });
      table.append(el('tr', {}, el('th', { textContent: 'Variable' }), el('th', { textContent: 'Default, English' }), el('th', { textContent: 'Default, Chinese' })));
      for (const v of item.prompt.variables) {
        const zh = v.zh_default === undefined ? v.default : v.zh_default;
        table.append(el('tr', {}, el('td', { textContent: v.name }), el('td', { textContent: v.default === '' ? '(empty)' : v.default }),
          el('td', { lang: 'zh-Hans', textContent: (zh === '' ? '(empty)' : zh) + (v.zh_default === undefined ? ' (same)' : '') })));
      }
      body.append(table);
    } else if (item.prompt.variables?.length) {
      body.append(el('div', { className: 'meta', textContent: 'Blanks filled with their defaults: ' + item.prompt.variables.map((v) => v.name + ' = ' + v.default).join('; ') }));
    }
    if (item.inputs) {
      // The slots: what the gallery will say to put in each, and what each sample was made from.
      const sampleCents = item.inputs.reduce((total, slot) => total + slot.sample.generated_from.cost_cents, 0);
      body.append(el('div', { className: 'label', textContent: 'Input slots (' + (item.input_subject === 'person' ? 'about a person' : 'about an object')
        + ') · samples ' + sampleCents + ' cent' + (sampleCents === 1 ? '' : 's') + ' + run ' + cost + '. The standard applies to each sample, and a hint never asks for a real person, a child, or anyone who has not agreed.' }));
      const earlier = decisions[item.id]?.slots ?? [];
      const table = el('table', { className: 'vars' });
      table.append(el('tr', {}, el('th', { textContent: 'Slot name' }), el('th', { textContent: 'Role' }), el('th', { textContent: 'Hint, English' }), el('th', { textContent: 'Hint, Chinese' })));
      item.inputs.forEach((slot, index) => {
        const name = el('input', { type: 'text', value: earlier[index]?.slot ?? slot.slot, ariaLabel: 'Slot name' });
        const en = el('input', { type: 'text', value: earlier[index]?.hint?.en ?? slot.hint.en, ariaLabel: 'Hint in English' });
        const zh = el('input', { type: 'text', lang: 'zh-Hans', value: earlier[index]?.hint?.zh ?? slot.hint.zh ?? '', ariaLabel: 'Hint in Chinese' });
        slotFields.push({ name, en, zh });
        table.append(el('tr', {}, el('td', {}, name), el('td', { textContent: slot.role }), el('td', {}, en), el('td', {}, zh)));
      });
      body.append(table);
      for (const slot of item.inputs) {
        body.append(el('div', { className: 'label', textContent: 'Prompt the sample for "' + slot.slot + '" was generated from' }),
          el('pre', { textContent: slot.sample.generated_from.prompt }));
      }
    }
    if (item.notes.length) {
      body.append(el('div', { className: 'label', textContent: 'Notes' }));
      const list = el('ul', { className: 'notes' });
      for (const note of item.notes) list.append(el('li', { textContent: note }));
      body.append(list);
    }

    const reason = el('input', { type: 'text', placeholder: 'Reason (needed for a rejection)', value: decisions[item.id]?.reason ?? '' });
    const warn = el('span', { className: 'warn' });
    // The slots as the operator left them: saved with the decision, and used when the recipe is written.
    const slotsNow = () => slotFields.map((field) => ({ slot: field.name.value, hint: { en: field.en.value, zh: field.zh.value } }));
    const choose = (decision) => {
      if (decision === null) delete decisions[item.id];
      else decisions[item.id] = { slug: item.slug, decision, reason: reason.value, decided_on: today(), ...(slotFields.length ? { slots: slotsNow() } : {}) };
      persist(); refresh();
    };
    reason.addEventListener('input', () => { if (decisions[item.id]) { decisions[item.id].reason = reason.value; persist(); refresh(); } });
    for (const field of slotFields) for (const input of [field.name, field.en, field.zh]) {
      input.addEventListener('input', () => { if (decisions[item.id]) { decisions[item.id].slots = slotsNow(); persist(); } });
    }
    const decide = el('div', { className: 'decide' },
      el('button', { textContent: 'Approve', onclick: () => choose('approved') }),
      el('button', { textContent: 'Reject', onclick: () => choose('rejected') }),
      el('button', { textContent: 'Clear', onclick: () => choose(null) }),
      reason, warn);
    body.append(decide);

    const card = el('section', { className: 'card' }, media, body);
    nodes.set(item.id, { card, warn });
    cards.append(card);
  }

  // New runs on published recipes: each beside the recipe's first run.
  if (data.reruns.length) {
    cards.append(el('h2', { className: 'section', textContent: 'New runs on published recipes' }));
    cards.append(el('div', { className: 'meta', textContent: 'Each new run is shown beside the recipe\\'s first run. Approving it adds it to the published recipe, after its other runs, with the day you approved it; the recipe\\'s own review stays as it is. The content standard applies to the new output as to any other.' }));
  }
  for (const item of data.reruns) {
    const media = el('div', { className: 'media' });
    const show = (run, label, published) => {
      media.append(el('div', { className: 'label', textContent: label }));
      for (const output of run.outputs) {
        const src = data.mediaBase + output.file;
        if (item.modality === 'VIDEO') {
          const video = el('video', { controls: true, preload: 'metadata', src });
          if (output.poster) video.poster = data.mediaBase + output.poster;
          media.append(video);
        } else {
          const img = el('img', { loading: 'lazy', src, width: output.width, height: output.height, alt: '' });
          // A published output may not be in this work folder: say where to see it instead.
          if (published) img.addEventListener('error', () => img.replaceWith(el('div', { className: 'meta' }, 'Not in this work folder: see it on ', link('the recipe page', item.recipe_url), '.')));
          media.append(img);
        }
        media.append(el('div', { className: 'meta', textContent: output.width + ' × ' + output.height + (output.duration_seconds ? ' · ' + output.duration_seconds + ' s' : '') }));
      }
    };
    if (item.first) show(item.first, 'First run, published: ' + item.first.model, true);
    show(item.run, 'New run: ' + item.model, false);

    const body = el('div');
    body.append(el('h2', { textContent: item.title.en }));
    if (item.title.zh) body.append(el('div', { className: 'zh-title', lang: 'zh-Hans', textContent: item.title.zh }));
    body.append(el('div', { className: 'meta', textContent: [item.modality, item.slug, 'run on the ' + (item.prompt_lang === 'zh' ? 'Chinese' : 'English') + ' prompt'].join(' · ') }));
    if (item.first) {
      body.append(el('div', { className: 'meta', textContent: 'First run: ' + [item.first.model, JSON.stringify(item.first.params), centsText(item.first.cost_cents), 'generated ' + item.first.generated_on].join(' · ') }));
    }
    body.append(el('div', { className: 'meta', textContent: 'New run: ' + [item.run.model, JSON.stringify(item.run.params), centsText(item.run.cost_cents), 'generated ' + item.run.generated_on].join(' · ') }));
    const source = el('div', { className: 'meta' });
    source.append(link('The recipe page', item.recipe_url));
    body.append(source);
    if (item.run.left.length) {
      body.append(el('div', { className: 'label', textContent: 'Left out of the first run\\'s parameters' }));
      const list = el('ul', { className: 'notes' });
      for (const left of item.run.left) list.append(el('li', { textContent: left.param + ' ' + JSON.stringify(left.value) + ': ' + left.why }));
      body.append(list);
    }
    const notes = [];
    if (!item.published) notes.push('The recipe is not in the gallery folder: publish cannot add this run to it.');
    if (item.already) notes.push('The recipe already has a run on ' + item.model + ': publish will leave it as it is.');
    if (notes.length) {
      const list = el('ul', { className: 'notes' });
      for (const note of notes) list.append(el('li', { textContent: note }));
      body.append(list);
    }
    body.append(el('div', { className: 'label', textContent: 'The prompt it was run with' }), el('pre', item.prompt_lang === 'zh' ? { lang: 'zh-Hans', textContent: item.prompt } : { textContent: item.prompt }));

    const reason = el('input', { type: 'text', placeholder: 'Reason (needed for a rejection)', value: runDecisions[item.key]?.reason ?? '' });
    const warn = el('span', { className: 'warn' });
    const choose = (decision) => {
      if (decision === null) delete runDecisions[item.key];
      else runDecisions[item.key] = { slug: item.slug, model: item.model, decision, reason: reason.value, decided_on: today() };
      persistRuns(); refresh();
    };
    reason.addEventListener('input', () => { if (runDecisions[item.key]) { runDecisions[item.key].reason = reason.value; persistRuns(); refresh(); } });
    body.append(el('div', { className: 'decide' },
      el('button', { textContent: 'Approve', onclick: () => choose('approved') }),
      el('button', { textContent: 'Reject', onclick: () => choose('rejected') }),
      el('button', { textContent: 'Clear', onclick: () => choose(null) }),
      reason, warn));

    const card = el('section', { className: 'card' }, media, body);
    runNodes.set(item.key, { card, warn });
    cards.append(card);
  }

  document.getElementById('undecided').addEventListener('change', refresh);
  document.getElementById('save').addEventListener('click', () => {
    const missing = [...Object.values(decisions), ...Object.values(runDecisions)].filter((made) => made.decision === 'rejected' && !made.reason.trim()).length;
    if (missing > 0) { alert(missing + ' rejection' + (missing === 1 ? ' has' : 's have') + ' no reason yet.'); return; }
    const withRuns = data.reruns.length > 0 || Object.keys(runDecisions).length > 0;
    const file = { standard_version: data.standardVersion, saved_at: new Date().toISOString(), decisions, ...(withRuns ? { runs: runDecisions } : {}) };
    const url = URL.createObjectURL(new Blob([JSON.stringify(file, null, 2) + '\\n'], { type: 'application/json' }));
    el('a', { href: url, download: 'decisions.json' }).click();
    URL.revokeObjectURL(url);
  });
  refresh();
})();
</script>
</body>
</html>
`;
}

/** The version the content standard gives itself ("**Version:** 1"), or null. */
export function standardVersionOf(markdown) {
  const match = /^\*\*Version:\*\*\s*(\d+)\s*$/m.exec(markdown ?? '');
  return match ? Number(match[1]) : null;
}

/**
 * Check a decisions file the operator saved. Returns what is wrong with it;
 * an empty list means `publish` may use it.
 */
export function checkDecisions(file) {
  const problems = [];
  if (!file || typeof file !== 'object' || typeof file.decisions !== 'object' || file.decisions === null) return ['it is not a decisions file'];
  if (!Number.isInteger(file.standard_version) || file.standard_version < 1) problems.push('it does not say which version of the content standard was applied');
  const check = (id, made) => {
    if (made?.decision !== 'approved' && made?.decision !== 'rejected') problems.push(`${id}: the decision is neither approved nor rejected`);
    else if (made.decision === 'rejected' && !String(made.reason ?? '').trim()) problems.push(`${id}: rejected without a reason`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(made?.decided_on ?? '')) problems.push(`${id}: no date`);
  };
  for (const [id, made] of Object.entries(file.decisions)) check(id, made);
  // New runs on published recipes (rerun), by <slug>@<model>, when the sheet had any.
  if (file.runs !== undefined) {
    if (typeof file.runs !== 'object' || file.runs === null || Array.isArray(file.runs)) problems.push('its runs are not decisions by run');
    else for (const [key, made] of Object.entries(file.runs)) check(key, made);
  }
  return problems;
}
