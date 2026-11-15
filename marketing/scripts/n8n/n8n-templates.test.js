/**
 * The n8n templates in integrations/n8n/, checked as files.
 *
 * They live outside marketing/, but nothing else in the repository runs a
 * JavaScript test, so the check is here, where `npm test` picks it up.
 *
 * It reads each *.json there and holds it to what a template must be before
 * anyone imports it: an n8n export, built from n8n's own nodes only, with no
 * credential value and nothing that names an n8n instance or a spreadsheet,
 * calling https://api.godance.ai/v1 and nothing else. It also holds each
 * one to the rules the templates were written to: a row is sent once and
 * never again, the job is polled with a wait that grows up to a limit of
 * tries, and every outcome, a failure included, is written to the sheet
 * before the next row is taken.
 *
 * The last block turns the check on itself: a copy of a template that
 * breaks one rule must be refused by that rule.
 *
 * What it cannot do is run a workflow: that needs n8n. The README beside
 * the templates says how they were run.
 */
import assert from 'node:assert/strict';
import { readdirSync, readFileSync } from 'node:fs';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const DIR = fileURLToPath(new URL('../../../integrations/n8n/', import.meta.url));

const API_BASE = 'https://api.godance.ai/v1';
const CREDENTIAL_TYPE = 'httpHeaderAuth';
const CREDENTIAL_NAME = 'Godance API';

/**
 * The only node types a template may use, each at the version its parameters
 * were written against (n8n's own definition of that version). All are in
 * n8n's built-in package: no community node, and no Code node.
 */
const ALLOWED_NODES = new Map([
  ['n8n-nodes-base.manualTrigger', 1],
  ['n8n-nodes-base.googleSheets', 4.5],
  ['n8n-nodes-base.splitInBatches', 3],
  ['n8n-nodes-base.httpRequest', 4.2],
  ['n8n-nodes-base.if', 2.2],
  ['n8n-nodes-base.set', 3.4],
  ['n8n-nodes-base.wait', 1.1],
  ['n8n-nodes-base.stickyNote', 1],
]);

/** The columns a workflow writes. Anything else it sent to the sheet would be added as a new column. */
const WRITTEN_COLUMNS = ['status', 'job_id', 'output_url', 'cost_cents', 'error_message'];

/** What each template must send, by file name. */
const TEMPLATES = {
  'godance-sheet-to-images.json': {
    createPath: '/images/generations',
    // An image request made with a key set to answer synchronously would wait; this asks for the job.
    body: [/\basync: true\b/, /\bmodel: \$json\.model\b/, /\bprompt: \$json\.prompt\b/],
    readColumns: ['prompt', 'model', 'size', 'status'],
  },
  'godance-sheet-to-videos.json': {
    createPath: '/videos/generations',
    body: [
      /\bseconds: Number\(\$json\.seconds\)/,
      /\bfirst_frame: \$json\.first_frame_file_id \? \{ file_id: /,
      /\bmodel: \$json\.model\b/,
      /\bprompt: \$json\.prompt\b/,
    ],
    readColumns: ['prompt', 'model', 'seconds', 'size', 'first_frame_file_id', 'status'],
  },
};

/** Anything shaped like a key or a token. A template holds the credential's name, never its value. */
const SECRET_PATTERNS = [
  /gd_(live|test)_[A-Za-z0-9_-]{6,}/,
  /\bsk-[A-Za-z0-9_-]{16,}/,
  /\bwhsec_[A-Za-z0-9]{6,}/,
  /Bearer\s+[A-Za-z0-9._~+/-]{16,}/,
  /\beyJ[A-Za-z0-9_-]{10,}\.[A-Za-z0-9_-]{10,}/,
];

const files = readdirSync(DIR).filter((name) => name.endsWith('.json')).sort();

// ----- reading a workflow -------------------------------------------------

/** Every string anywhere in a JSON value. */
function strings(value, out = []) {
  if (typeof value === 'string') out.push(value);
  else if (Array.isArray(value)) value.forEach((v) => strings(v, out));
  else if (value && typeof value === 'object') Object.values(value).forEach((v) => strings(v, out));
  return out;
}

/** A node's outgoing connections: for each output, the names of the nodes it leads to. */
function outputsOf(workflow, name) {
  return (workflow.connections[name]?.main ?? []).map((targets) => (targets ?? []).map((t) => t.node));
}

/** Every node reachable from `start` by following connections, never passing through `without`. */
function reachable(workflow, start, without = new Set()) {
  const seen = new Set();
  const queue = [start];
  while (queue.length) {
    const name = queue.shift();
    for (const next of outputsOf(workflow, name).flat()) {
      if (seen.has(next) || without.has(next)) continue;
      seen.add(next);
      queue.push(next);
    }
  }
  return seen;
}

function onlyNode(workflow, predicate, what) {
  const found = workflow.nodes.filter(predicate);
  assert.equal(found.length, 1, `exactly one ${what}, found ${found.map((n) => n.name).join(', ') || 'none'}`);
  return found[0];
}

/** The nodes the rules are about. */
function parts(workflow) {
  const is = (type) => (n) => n.type === `n8n-nodes-base.${type}`;
  return {
    requests: workflow.nodes.filter(is('httpRequest')),
    create: onlyNode(workflow, (n) => is('httpRequest')(n) && n.parameters.method === 'POST', 'request that creates a job'),
    poll: onlyNode(workflow, (n) => is('httpRequest')(n) && n.parameters.method === 'GET', 'request that reads a job'),
    loop: onlyNode(workflow, is('splitInBatches'), 'Loop Over Items node'),
    wait: onlyNode(workflow, is('wait'), 'Wait node'),
    read: onlyNode(workflow, (n) => is('googleSheets')(n) && n.parameters.operation === 'read',
      'Google Sheets node that reads the rows'),
    update: onlyNode(workflow, (n) => is('googleSheets')(n) && n.parameters.operation === 'update',
      'Google Sheets node that writes a row'),
  };
}

/** The fields a Set node assigns, by name. */
function assignmentsOf(node) {
  return Object.fromEntries((node.parameters.assignments?.assignments ?? []).map((a) => [a.name, a]));
}

/** A template as the rules read it: its text, the workflow in it, and what its file name promises. */
function template(file, text) {
  const workflow = JSON.parse(text);
  return { file, text, workflow, expected: TEMPLATES[file], byName: new Map(workflow.nodes.map((n) => [n.name, n])) };
}

// ----- the rules ------------------------------------------------------------

const RULES = new Map();

RULES.set('parses as an n8n export', ({ workflow, byName }) => {
  assert.equal(typeof workflow.name, 'string');
  assert.ok(workflow.name.trim().length > 0, 'the workflow has a name');
  assert.ok(Array.isArray(workflow.nodes) && workflow.nodes.length > 0, 'nodes is a non-empty array');
  assert.ok(workflow.connections && typeof workflow.connections === 'object' && !Array.isArray(workflow.connections),
    'connections is an object');
  assert.equal(workflow.active, false, 'a template is imported switched off');
  assert.equal(workflow.settings?.executionOrder, 'v1');

  const ids = new Set();
  for (const node of workflow.nodes) {
    const where = `node ${JSON.stringify(node.name)}`;
    assert.match(String(node.id), /^[0-9a-f]{8}(-[0-9a-f]{4}){3}-[0-9a-f]{12}$/, `${where} has a UUID id`);
    assert.ok(!ids.has(node.id), `${where} has an id of its own`);
    ids.add(node.id);
    assert.ok(typeof node.name === 'string' && node.name.length > 0, 'every node has a name');
    assert.equal(typeof node.type, 'string', `${where} has a type`);
    assert.equal(typeof node.typeVersion, 'number', `${where} has a numeric typeVersion`);
    assert.ok(Array.isArray(node.position) && node.position.length === 2
      && node.position.every((n) => Number.isFinite(n)), `${where} has an [x, y] position`);
    assert.ok(node.parameters && typeof node.parameters === 'object' && !Array.isArray(node.parameters),
      `${where} has parameters`);
  }
  assert.equal(byName.size, workflow.nodes.length, 'node names are unique: connections are by name');

  for (const [source, outputs] of Object.entries(workflow.connections)) {
    assert.ok(byName.has(source), `connections start at a node that exists: ${source}`);
    assert.deepEqual(Object.keys(outputs), ['main'], `${source} has main connections only`);
    assert.ok(Array.isArray(outputs.main), `${source}.main is a list of outputs`);
    for (const targets of outputs.main) {
      assert.ok(Array.isArray(targets), `each output of ${source} is a list`);
      for (const target of targets) {
        assert.ok(byName.has(target.node), `${source} leads to a node that exists: ${target.node}`);
        assert.equal(target.type, 'main');
        assert.equal(target.index, 0, `${source} → ${target.node} goes to the node's one input`);
        assert.notEqual(byName.get(target.node).type, 'n8n-nodes-base.stickyNote', 'a note has no input');
      }
    }
  }

  assert.equal(workflow.nodes.filter((n) => n.type === 'n8n-nodes-base.manualTrigger').length, 1,
    'it starts from one manual trigger');
});

RULES.set('uses only allowed node types, at the versions they were written for', ({ workflow }) => {
  for (const node of workflow.nodes) {
    assert.ok(ALLOWED_NODES.has(node.type),
      `${JSON.stringify(node.name)} is a ${node.type}, which is not one of the n8n core nodes a template may use`);
    assert.equal(node.typeVersion, ALLOWED_NODES.get(node.type), `${JSON.stringify(node.name)} typeVersion`);
  }
});

RULES.set('holds no credential value, and names no n8n instance, account or spreadsheet', ({ text, workflow }) => {
  for (const pattern of SECRET_PATTERNS) {
    assert.doesNotMatch(text, pattern, 'nothing in the file looks like a key or a token');
  }
  for (const node of workflow.nodes) {
    const where = JSON.stringify(node.name);
    if (node.type === 'n8n-nodes-base.httpRequest') {
      // The key reaches the request only through the credential, which the file names and nothing more.
      assert.deepEqual(node.credentials, { [CREDENTIAL_TYPE]: { id: null, name: CREDENTIAL_NAME } },
        `${where} names the "${CREDENTIAL_NAME}" Header Auth credential, with no id and no value`);
      assert.equal(node.parameters.authentication, 'genericCredentialType', `${where} authentication`);
      assert.equal(node.parameters.genericAuthType, CREDENTIAL_TYPE, `${where} genericAuthType`);
      for (const off of ['sendHeaders', 'sendQuery']) {
        assert.ok(!node.parameters[off],
          `${where} sets no ${off}: a header or a query written into the node could carry a key`);
      }
    } else {
      // Google's credential is the importer's to choose: a template carries none.
      assert.equal(node.credentials, undefined, `${where} carries no credential`);
    }
    if (node.type === 'n8n-nodes-base.googleSheets') {
      for (const locator of ['documentId', 'sheetName']) {
        assert.deepEqual(node.parameters[locator], { __rl: true, mode: 'list', value: '' },
          `${where} ${locator} is left for the importer to choose`);
      }
    }
  }
  // What an export from a running instance adds, and a template must not keep.
  for (const key of ['id', 'versionId', 'meta', 'shared', 'staticData', 'triggerCount']) {
    assert.equal(workflow[key], undefined, `no ${key}: it would name the instance the file came from`);
  }
  assert.deepEqual(workflow.pinData ?? {}, {}, 'no pinned data');
});

RULES.set(`points at ${API_BASE}, and nowhere else`, ({ workflow }) => {
  const { requests } = parts(workflow);
  assert.ok(requests.length >= 2, 'it creates a job and reads it');
  for (const node of requests) {
    const url = String(node.parameters.url).replace(/^=/, '');
    assert.ok(url.startsWith(`${API_BASE}/`), `${JSON.stringify(node.name)} calls ${url}`);
  }
  for (const value of strings(workflow)) {
    for (const [url] of value.matchAll(/https?:\/\/[^\s"'`)]+/g)) {
      assert.ok(url.startsWith(`${API_BASE}/`), `the only address in the file is the API's, found ${url}`);
    }
  }
});

RULES.set('creates one job per row from the row\'s own columns, and never sends a row twice', ({ workflow, expected }) => {
  const { create, loop } = parts(workflow);
  assert.equal(create.parameters.url, `${API_BASE}${expected.createPath}`);
  assert.equal(create.parameters.sendBody, true);
  assert.equal(create.parameters.specifyBody, 'json');
  for (const pattern of expected.body) assert.match(create.parameters.jsonBody, pattern);
  // The request is built with JSON.stringify, so a prompt with quotes or line breaks cannot break it.
  assert.match(create.parameters.jsonBody, /^=\{\{ JSON\.stringify\(\{ .* \}\) \}\}$/);
  const used = new Set([...create.parameters.jsonBody.matchAll(/\$json\.([a-z_]+)/g)].map((m) => m[1]));
  assert.deepEqual([...used, 'status'].sort(), [...expected.readColumns].sort(), 'the columns it reads');

  assert.notEqual(create.retryOnFail, true, 'a create request is never retried: a retry could be a second paid job');
  assert.equal(loop.parameters.batchSize, 1, 'rows are taken one at a time');
  // The only way back to the create request is through the loop, which hands it the next row.
  assert.ok(!reachable(workflow, create.name, new Set([loop.name])).has(create.name),
    'nothing leads back to the create request except the next row');
});

RULES.set('polls with a Wait and If nodes, a wait that grows, and a limit of tries', ({ workflow, byName }) => {
  const { create, poll, wait, loop, update } = parts(workflow);
  assert.equal(poll.parameters.url, `=${API_BASE}/jobs/{{ $json.job_id }}`);
  assert.equal(wait.parameters.resume, 'timeInterval');
  assert.equal(wait.parameters.unit, 'seconds');
  assert.equal(wait.parameters.amount, '={{ $json.wait_seconds }}');

  // Wait → read the job → … → Wait again: a loop of its own, inside the row's.
  const inside = new Set([loop.name, create.name]);
  const afterWait = reachable(workflow, wait.name, inside);
  assert.ok(afterWait.has(poll.name), 'the job is read after the wait');
  assert.ok(afterWait.has(wait.name), 'and a job still running is waited for again');

  const cycle = [...afterWait].map((name) => byName.get(name))
    .filter((node) => reachable(workflow, node.name, inside).has(wait.name));
  const ifs = cycle.filter((n) => n.type === 'n8n-nodes-base.if');
  assert.ok(ifs.length >= 2, 'If nodes decide whether to stop');
  const limit = ifs.find((n) => /max_tries/.test(JSON.stringify(n.parameters)));
  assert.ok(limit, 'one of them stops at max_tries');
  // Its "no" must leave the polling loop: otherwise the limit limits nothing. From there the only
  // way to another wait is to be written to the sheet first, as a row that has stopped being checked.
  const [, no] = outputsOf(workflow, limit.name);
  const beforeTheSheet = new Set([...inside, update.name]);
  assert.ok(no.length > 0
    && no.every((name) => name !== wait.name && !reachable(workflow, name, beforeTheSheet).has(wait.name)),
    'when no tries are left, the row leaves the polling loop');

  const backoff = cycle.filter((n) => n.type === 'n8n-nodes-base.set').map(assignmentsOf)
    .find((fields) => fields.wait_seconds);
  assert.ok(backoff, 'the next wait is set on the way back to the Wait node');
  assert.match(backoff.wait_seconds.value, /Math\.min\(.*wait_seconds \* 2, .*max_wait_seconds\)/,
    'each wait is twice the last, up to max_wait_seconds');
  assert.match(backoff.tries.value, /tries \+ 1/, 'and the try is counted');

  // Where the numbers start: one Set node an importer can edit.
  const start = workflow.nodes.filter((n) => n.type === 'n8n-nodes-base.set').map(assignmentsOf)
    .find((fields) => fields.max_tries && typeof fields.max_tries.value === 'number');
  assert.ok(start, 'the first wait, the longest wait and the number of tries are set in one node');
  for (const field of ['tries', 'wait_seconds', 'max_wait_seconds', 'max_tries']) {
    assert.equal(typeof start[field].value, 'number', `${field} starts as a number`);
  }
  assert.equal(start.tries.value, 0);
  assert.ok(start.wait_seconds.value >= 1 && start.wait_seconds.value <= start.max_wait_seconds.value);
  // n8n keeps a wait under 65 seconds in the running execution; a longer one parks it.
  assert.ok(start.max_wait_seconds.value <= 60, 'no wait is longer than a minute');
  assert.ok(start.max_tries.value >= 2 && start.max_tries.value <= 60);
});

RULES.set('writes a failed row\'s error to the sheet and carries on with the next row', ({ workflow, byName }) => {
  const { create, poll, loop, update } = parts(workflow);
  for (const request of [create, poll]) {
    // A refusal comes back as data (the problem's detail), not as a stopped workflow.
    assert.equal(request.onError, 'continueRegularOutput', `${JSON.stringify(request.name)} carries on after an error`);
    assert.deepEqual(request.parameters.options?.response?.response, { neverError: true, responseFormat: 'json' });
  }
  assert.deepEqual(update.parameters.columns.matchingColumns, ['row_number'], 'a row is found by its number');
  assert.equal(update.parameters.columns.mappingMode, 'autoMapInputData');

  const body = reachable(workflow, create.name, new Set([loop.name]));
  // Every step of a row's work leads back to the loop: no outcome ends the run early.
  for (const name of body) {
    assert.ok(reachable(workflow, name).has(loop.name), `${JSON.stringify(name)} leads back to the loop`);
    if (byName.get(name).type === 'n8n-nodes-base.if') {
      const outputs = outputsOf(workflow, name);
      assert.ok(outputs.length === 2 && outputs.every((targets) => targets.length > 0),
        `both answers of ${JSON.stringify(name)} lead somewhere`);
    }
  }
  // And the only way back is through the node that writes the row.
  assert.ok(!reachable(workflow, create.name, new Set([update.name])).has(loop.name),
    'no row goes back to the loop without being written to the sheet');
  // That holds for each outcome, not only the first write: a node that gives a row its status
  // hands it to the sheet and to nothing else.
  for (const name of body) {
    const node = byName.get(name);
    if (node.type === 'n8n-nodes-base.set' && assignmentsOf(node).status) {
      assert.deepEqual(outputsOf(workflow, name), [[update.name]], `${JSON.stringify(name)} goes to the sheet, and only there`);
    }
  }

  // What is written: every node that feeds the sheet names the row, and the failures say why.
  const writers = workflow.nodes.filter((n) => outputsOf(workflow, n.name).flat().includes(update.name));
  const statuses = new Set();
  for (const writer of writers) {
    const where = JSON.stringify(writer.name);
    assert.equal(writer.type, 'n8n-nodes-base.set', `${where} shapes the row to write`);
    const fields = assignmentsOf(writer);
    assert.ok(fields.row_number, `${where} says which row`);
    assert.ok(fields.status, `${where} sets a status`);
    statuses.add(fields.status.value);
    for (const field of Object.keys(fields)) {
      // n8n reads a field called "error" on an item as the node having failed, so no column is called that.
      assert.ok(field === 'row_number' || WRITTEN_COLUMNS.includes(field),
        `${where} writes ${field}, which is not one of the sheet's columns`);
    }
    if (['FAILED', 'TIMEOUT'].includes(fields.status.value)) {
      assert.ok(fields.error_message && fields.error_message.value.length > 0, `${where} writes the reason to error_message`);
    }
    if (fields.status.value === 'DONE') {
      assert.equal(fields.output_url.value, '={{ $json.outputUrl }}');
      assert.equal(fields.cost_cents.value, '={{ $json.actualCostCents }}');
    }
  }
  assert.deepEqual([...statuses].sort(), ['DONE', 'FAILED', 'RUNNING', 'TIMEOUT'], 'the statuses a row can be given');
});

RULES.set('says how to set it up on the canvas: one overview note, and the credential by name', ({ workflow, expected }) => {
  const notes = workflow.nodes.filter((n) => n.type === 'n8n-nodes-base.stickyNote');
  const overviews = notes.filter((n) => /### How it works/.test(n.parameters.content));
  assert.equal(overviews.length, 1, 'one overview note');
  const overview = overviews[0].parameters.content;
  for (const heading of ['### How it works', '### Setup', '### Customization']) {
    assert.ok(overview.includes(heading), `the overview has ${heading}`);
  }
  const words = overview.split(/\s+/).filter(Boolean).length;
  assert.ok(words >= 100 && words <= 300, `the overview is 100 to 300 words, it is ${words}`);
  assert.ok(overview.includes(`**${CREDENTIAL_NAME}**`), 'it names the credential to create');
  assert.ok(overview.includes('/v1/estimates'), 'and says to price a row first');
  for (const column of [...expected.readColumns, ...WRITTEN_COLUMNS]) {
    assert.ok(overview.includes(column), `it lists the ${column} column`);
  }
  for (const note of notes.filter((n) => n !== overviews[0])) {
    const count = note.parameters.content.split(/\s+/).filter(Boolean).length;
    assert.ok(count < 50, `the note ${JSON.stringify(note.name)} is under 50 words, it is ${count}`);
  }
});

// ----- the templates --------------------------------------------------------

test('the folder holds the two templates and their README', () => {
  assert.deepEqual(files, Object.keys(TEMPLATES).sort());
  assert.ok(readdirSync(DIR).includes('README.md'), 'README.md is beside them');
});

for (const file of files) {
  describe(file, () => {
    const loaded = template(file, readFileSync(path.join(DIR, file), 'utf8'));
    for (const [name, rule] of RULES) test(name, () => rule(loaded));
  });
}

describe('README.md', () => {
  const readme = readFileSync(path.join(DIR, 'README.md'), 'utf8');

  test('names each template, the credential and every sheet column', () => {
    for (const [file, expected] of Object.entries(TEMPLATES)) {
      assert.ok(readme.includes(file), `it names ${file}`);
      for (const column of [...expected.readColumns, ...WRITTEN_COLUMNS]) {
        assert.ok(readme.includes(`\`${column}\``), `it lists the ${column} column`);
      }
    }
    assert.ok(readme.includes(CREDENTIAL_NAME), 'it names the credential');
    assert.ok(readme.includes('Header Auth'), 'and its type');
  });

  test('carries the cost warning: estimate first', () => {
    assert.ok(readme.includes('POST /v1/estimates'), 'it says to estimate first');
    assert.match(readme, /daily_cap_cents/, 'and to cap the key');
  });

  test('holds nothing that looks like a key', () => {
    for (const pattern of SECRET_PATTERNS) assert.doesNotMatch(readme, pattern);
  });
});

// ----- the check, checked ----------------------------------------------------

describe('a template that breaks a rule is refused by that rule', () => {
  const file = 'godance-sheet-to-images.json';
  const original = readFileSync(path.join(DIR, file), 'utf8');
  const node = (workflow, name) => workflow.nodes.find((n) => n.name === name);
  const connect = (name) => [{ node: name, type: 'main', index: 0 }];

  /** [what was done to the template, the rule that must refuse it, how] */
  const breakages = [
    ['a Code node', 'uses only allowed node types, at the versions they were written for', (w) => {
      w.nodes.push({ parameters: { jsCode: 'return items;' }, id: '00000000-0000-4000-a000-000000000001',
        name: 'Code', type: 'n8n-nodes-base.code', typeVersion: 2, position: [0, 0] });
    }],
    ['a community node', 'uses only allowed node types, at the versions they were written for', (w) => {
      node(w, 'Get the job').type = 'n8n-nodes-godance.godance';
    }],
    ['a node at a version its parameters were not written for', 'uses only allowed node types, at the versions they were written for', (w) => {
      node(w, 'Job done?').typeVersion = 1;
    }],
    ['a key written into a header', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      const create = node(w, 'Create the image job');
      create.parameters.sendHeaders = true;
      create.parameters.headerParameters = { parameters: [{ name: 'Authorization', value: `Bearer gd_${'live'}_0123456789abcdef` }] };
    }],
    ['a key in a note', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      node(w, 'Overview').parameters.content += `\nMy key is gd_${'live'}_0123456789abcdef`;
    }],
    ['a credential id from someone\'s n8n', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      node(w, 'Get the job').credentials.httpHeaderAuth.id = 'Zq3x9LmK2pWv7RtY';
    }],
    ['a credential with its data', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      node(w, 'Get the job').credentials.httpHeaderAuth.data = { name: 'Authorization', value: 'x' };
    }],
    ['no credential on a request', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      const poll = node(w, 'Get the job');
      delete poll.credentials;
      poll.parameters.authentication = 'none';
    }],
    ['someone\'s spreadsheet', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      node(w, 'Get the rows').parameters.documentId = { __rl: true, mode: 'id', value: '1AbCdEfGhIjKlMnOpQrStUvWxYz0123456789' };
    }],
    ['a Google credential left in', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      node(w, 'Update the row').credentials = { googleSheetsOAuth2Api: { id: 'aBcD1234', name: 'Google Sheets account' } };
    }],
    ['the instance it was exported from', 'holds no credential value, and names no n8n instance, account or spreadsheet', (w) => {
      w.meta = { instanceId: '0f'.repeat(32) };
    }],
    ['another host', `points at ${API_BASE}, and nowhere else`, (w) => {
      node(w, 'Get the job').parameters.url = '=https://api.example.com/v1/jobs/{{ $json.job_id }}';
    }],
    ['a staging host', `points at ${API_BASE}, and nowhere else`, (w) => {
      node(w, 'Create the image job').parameters.url = 'https://api.godance.ai.example.com/v1/images/generations';
    }],
    ['a link in a note', `points at ${API_BASE}, and nowhere else`, (w) => {
      node(w, 'Overview').parameters.content += '\nSee https://example.com/setup';
    }],
    ['a create request that is retried', 'creates one job per row from the row\'s own columns, and never sends a row twice', (w) => {
      node(w, 'Create the image job').retryOnFail = true;
    }],
    ['a way back to the create request', 'creates one job per row from the row\'s own columns, and never sends a row twice', (w) => {
      w.connections['Row failed'].main[0] = connect('Create the image job');
    }],
    ['a body pasted together as text', 'creates one job per row from the row\'s own columns, and never sends a row twice', (w) => {
      node(w, 'Create the image job').parameters.jsonBody = '={ "model": "{{ $json.model }}", "prompt": "{{ $json.prompt }}", "async": true }';
    }],
    ['an image request that may wait', 'creates one job per row from the row\'s own columns, and never sends a row twice', (w) => {
      const create = node(w, 'Create the image job');
      create.parameters.jsonBody = create.parameters.jsonBody.replace(', async: true', '');
    }],
    ['no limit of tries', 'polls with a Wait and If nodes, a wait that grows, and a limit of tries', (w) => {
      w.connections['Checks left?'].main[1] = connect('Wait longer');
    }],
    ['a wait that does not grow', 'polls with a Wait and If nodes, a wait that grows, and a limit of tries', (w) => {
      const again = node(w, 'Wait longer').parameters.assignments.assignments.find((a) => a.name === 'wait_seconds');
      again.value = "={{ $('Wait before checking').item.json.wait_seconds }}";
    }],
    ['a wait of five minutes', 'polls with a Wait and If nodes, a wait that grows, and a limit of tries', (w) => {
      node(w, 'Start checking').parameters.assignments.assignments.find((a) => a.name === 'max_wait_seconds').value = 300;
    }],
    ['a webhook in place of polling', 'polls with a Wait and If nodes, a wait that grows, and a limit of tries', (w) => {
      node(w, 'Wait before checking').parameters = { resume: 'webhook' };
    }],
    ['a refusal that goes nowhere', 'writes a failed row\'s error to the sheet and carries on with the next row', (w) => {
      w.connections['Job accepted?'].main[1] = [];
    }],
    ['a failure that skips the sheet', 'writes a failed row\'s error to the sheet and carries on with the next row', (w) => {
      w.connections['Row failed'].main[0] = connect('Loop Over Items');
    }],
    ['a failure with no reason', 'writes a failed row\'s error to the sheet and carries on with the next row', (w) => {
      const fields = node(w, 'Row was refused').parameters.assignments;
      fields.assignments = fields.assignments.filter((a) => a.name !== 'error_message');
    }],
    ['a request whose error stops the run', 'writes a failed row\'s error to the sheet and carries on with the next row', (w) => {
      delete node(w, 'Create the image job').onError;
    }],
    ['a column called error', 'writes a failed row\'s error to the sheet and carries on with the next row', (w) => {
      node(w, 'Row failed').parameters.assignments.assignments.find((a) => a.name === 'error_message').name = 'error';
    }],
    ['a connection to a node that is not there', 'parses as an n8n export', (w) => {
      w.connections['Row is done'].main[0] = connect('Write the row');
    }],
    ['two nodes with one name', 'parses as an n8n export', (w) => {
      node(w, 'Row failed').name = 'Row is done';
    }],
    ['a template left switched on', 'parses as an n8n export', (w) => {
      w.active = true;
    }],
    ['no overview note', 'says how to set it up on the canvas: one overview note, and the credential by name', (w) => {
      w.nodes = w.nodes.filter((n) => n.name !== 'Overview');
    }],
  ];

  test('the template itself passes every rule', () => {
    for (const rule of RULES.values()) rule(template(file, original));
  });

  for (const [what, ruleName, breakIt] of breakages) {
    test(`${what} → not "${ruleName}"`, () => {
      const workflow = JSON.parse(original);
      breakIt(workflow);
      const broken = template(file, JSON.stringify(workflow, null, 2));
      assert.ok(RULES.has(ruleName), 'the rule exists');
      assert.throws(() => RULES.get(ruleName)(broken), assert.AssertionError);
    });
  }

  test('a file that is not JSON is refused', () => {
    assert.throws(() => template(file, original.slice(0, -20)), SyntaxError);
  });
});
