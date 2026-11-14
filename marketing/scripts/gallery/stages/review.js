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
 */
import { parseTemplate } from '../../../src/lib/gallery-template.js';

/** JSON safe inside a <script> element. */
const embed = (data) =>
  JSON.stringify(data)
    .replace(/</g, '\\u003c')
    .replaceAll(String.fromCharCode(0x2028), '\\u2028')
    .replaceAll(String.fromCharCode(0x2029), '\\u2029');

/** What the sheet shows for each candidate that has a finished run. */
export function reviewItems({ entries, screened, translated, runs }) {
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
 * @param {number} options.standardVersion The version of the content standard the review is made against.
 * @param {object | null} [options.decisions] An earlier decisions.json, to carry on from.
 * @param {string} [options.mediaBase] Where the media folder is, relative to the page.
 */
export function reviewSheet({ items, standardVersion, decisions = null, mediaBase = '../media/', builtAt = new Date().toISOString() }) {
  const data = { items, standardVersion, builtAt, mediaBase, earlier: decisions?.decisions ?? {} };
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

  const el = (tag, props = {}, ...children) => {
    const node = Object.assign(document.createElement(tag), props);
    for (const child of children) node.append(child);
    return node;
  };
  const link = (text, url) => (/^https:\\/\\//.test(url ?? '') ? el('a', { textContent: text, href: url, target: '_blank', rel: 'noopener noreferrer' }) : el('span', { textContent: text }));
  const today = () => new Date().toISOString().slice(0, 10);
  const persist = () => { try { localStorage.setItem(storeKey, JSON.stringify(decisions)); } catch {} };

  document.getElementById('standard').textContent = 'content standard version ' + data.standardVersion + ' · built ' + data.builtAt.slice(0, 16).replace('T', ' ');
  const cards = document.getElementById('cards');
  const nodes = new Map();

  function refresh() {
    let approved = 0, rejected = 0;
    for (const item of data.items) {
      const made = decisions[item.id];
      const node = nodes.get(item.id);
      node.card.classList.toggle('approved', made?.decision === 'approved');
      node.card.classList.toggle('rejected', made?.decision === 'rejected');
      node.warn.textContent = made?.decision === 'rejected' && !made.reason.trim() ? 'A rejection needs a reason.' : '';
      node.card.hidden = document.getElementById('undecided').checked && Boolean(made);
      if (made?.decision === 'approved') approved++;
      if (made?.decision === 'rejected') rejected++;
    }
    document.getElementById('counts').textContent =
      data.items.length + ' candidates · ' + approved + ' approved · ' + rejected + ' rejected · ' + (data.items.length - approved - rejected) + ' undecided';
  }

  for (const item of data.items) {
    const media = el('div', { className: 'media' });
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
    if (item.notes.length) {
      body.append(el('div', { className: 'label', textContent: 'Notes' }));
      const list = el('ul', { className: 'notes' });
      for (const note of item.notes) list.append(el('li', { textContent: note }));
      body.append(list);
    }

    const reason = el('input', { type: 'text', placeholder: 'Reason (needed for a rejection)', value: decisions[item.id]?.reason ?? '' });
    const warn = el('span', { className: 'warn' });
    const choose = (decision) => {
      if (decision === null) delete decisions[item.id];
      else decisions[item.id] = { slug: item.slug, decision, reason: reason.value, decided_on: today() };
      persist(); refresh();
    };
    reason.addEventListener('input', () => { if (decisions[item.id]) { decisions[item.id].reason = reason.value; persist(); refresh(); } });
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

  document.getElementById('undecided').addEventListener('change', refresh);
  document.getElementById('save').addEventListener('click', () => {
    const missing = Object.values(decisions).filter((made) => made.decision === 'rejected' && !made.reason.trim()).length;
    if (missing > 0) { alert(missing + ' rejection' + (missing === 1 ? ' has' : 's have') + ' no reason yet.'); return; }
    const file = { standard_version: data.standardVersion, saved_at: new Date().toISOString(), decisions };
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
  for (const [id, made] of Object.entries(file.decisions)) {
    if (made?.decision !== 'approved' && made?.decision !== 'rejected') problems.push(`${id}: the decision is neither approved nor rejected`);
    else if (made.decision === 'rejected' && !String(made.reason ?? '').trim()) problems.push(`${id}: rejected without a reason`);
    if (!/^\d{4}-\d{2}-\d{2}$/.test(made?.decided_on ?? '')) problems.push(`${id}: no date`);
  }
  return problems;
}
