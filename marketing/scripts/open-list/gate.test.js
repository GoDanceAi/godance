/**
 * The open-list workflow (.github/workflows/open-list.yml) and its gate
 * (gate.js): it ships doing nothing, succeeds while it does, says why in one
 * line per unmet condition, never runs for forks, holds no secret in a file
 * and never force-pushes.
 *
 * GitHub cannot run a new workflow before it is on main, so its rules are
 * read from the file here, and the gate is run exactly as its step runs it.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import { mkdtemp, readFile, rm } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { DEFAULT_GALLERY_DIR as SCHEMA_DEFAULT_DIR, GALLERY_DIR_ENV as SCHEMA_DIR_ENV } from '../../src/lib/gallery-schema.js';
import { readRecipeFolder } from '../gallery-schema/read-recipes.js';
import { GateError, decide } from './gate.js';
import { DEFAULT_GALLERY_DIR, GALLERY_DIR_ENV, countRecipeFiles, galleryDir } from './switch.js';

const MARKETING = fileURLToPath(new URL('../../', import.meta.url));
const REPO = path.resolve(MARKETING, '..');
const FIXTURES = fileURLToPath(new URL('./fixtures/recipes/', import.meta.url));
const WORKFLOW = await readFile(path.join(REPO, '.github/workflows/open-list.yml'), 'utf8');
/** The workflow without its comments: what GitHub acts on. */
const YAML = WORKFLOW.split('\n').filter((line) => !line.trim().startsWith('#')).join('\n');

/** The gallery switched on against the fixtures. */
const GALLERY_ON = {
  GALLERY_CONTENT_DIR: FIXTURES,
  GALLERY_MEDIA_BASE_URL: 'https://media.example.test/gallery',
  GALLERY_REMOVAL_CONTACT: 'removals@example.test',
};
const SET = { OPEN_LIST_REPO: 'example-org/open-list', OPEN_LIST_DEPLOY_KEY_SET: 'true', GITHUB_REPOSITORY: 'GoDanceAi/godance' };
/** What the workflow passes when neither the variable nor the secret is set. */
const UNSET = { OPEN_LIST_REPO: '', OPEN_LIST_DEPLOY_KEY_SET: 'false', GITHUB_REPOSITORY: 'GoDanceAi/godance' };

const skips = (lines) => lines.filter((line) => line.startsWith('open-list: skip: '));

describe('the gate', () => {
  test('with the variable and the secret unset and the gallery off, as it ships, it skips and says why in one line each', async () => {
    // No GALLERY_* overrides: the committed gallery-config.ts and src/content/gallery, as on main.
    const { publish, lines } = await decide(UNSET);
    assert.equal(publish, false);
    assert.deepEqual(skips(lines), [
      'open-list: skip: the repository variable OPEN_LIST_REPO is not set',
      'open-list: skip: the secret OPEN_LIST_DEPLOY_KEY is not set',
      'open-list: skip: the gallery is off: no media base URL in marketing/src/lib/gallery-config.ts; no removal contact in marketing/src/lib/gallery-config.ts; no recipes in marketing/src/content/gallery',
    ]);
    assert.ok(lines.includes('open-list: skipped: nothing is generated and nothing is pushed, and this run succeeds'));
    assert.ok(lines.some((line) => line.startsWith('::notice title=Open list skipped::')));
  });

  test('each unmet condition alone is enough to skip, and is the only one named', async () => {
    const cases = {
      variable: [{ ...SET, ...GALLERY_ON, OPEN_LIST_REPO: '' }, 'the repository variable OPEN_LIST_REPO is not set'],
      secret: [{ ...SET, ...GALLERY_ON, OPEN_LIST_DEPLOY_KEY_SET: 'false' }, 'the secret OPEN_LIST_DEPLOY_KEY is not set'],
      gallery: [{ ...SET, ...GALLERY_ON, GALLERY_MEDIA_BASE_URL: '' }, 'the gallery is off: no media base URL in marketing/src/lib/gallery-config.ts'],
    };
    for (const [name, [env, line]] of Object.entries(cases)) {
      const { publish, lines } = await decide(env);
      assert.equal(publish, false, name);
      assert.deepEqual(skips(lines), [`open-list: skip: ${line}`], name);
      assert.equal(lines.filter((each) => each.startsWith('open-list: ok: ')).length, 2, `${name}: the other two hold`);
    }
  });

  test('with all three it publishes', async () => {
    const { publish, lines } = await decide({ ...SET, ...GALLERY_ON });
    assert.equal(publish, true);
    assert.deepEqual(skips(lines), []);
    assert.ok(lines.includes('open-list: publishing the list to example-org/open-list'));
  });

  test('a value that is set but wrong fails the run instead of skipping it, and the secret is never echoed', async () => {
    const fakeKey = '-----BEGIN OPENSSH PRIVATE KEY-----\nopen-list-test-not-a-real-key\n-----END OPENSSH PRIVATE KEY-----';
    const wrong = {
      'not owner/name': { ...UNSET, OPEN_LIST_REPO: 'not a repository' },
      'this repository': { ...UNSET, OPEN_LIST_REPO: 'godanceai/GODANCE' },
      'the key itself instead of whether it is set': { ...UNSET, OPEN_LIST_DEPLOY_KEY_SET: fakeKey },
      'a media base that is not a URL': { ...UNSET, GALLERY_MEDIA_BASE_URL: 'media host' },
    };
    for (const [name, env] of Object.entries(wrong)) {
      await assert.rejects(decide(env), (error) => {
        assert.ok(error instanceof GateError, name);
        assert.ok(!error.message.includes('PRIVATE KEY'), `${name}: the key is not in the message`);
        return true;
      }, name);
    }
  });

  test('it counts the recipe files the gallery reader reads, and finds the gallery where the reader does', async () => {
    assert.equal(GALLERY_DIR_ENV, SCHEMA_DIR_ENV);
    assert.equal(DEFAULT_GALLERY_DIR, SCHEMA_DEFAULT_DIR);
    const { recipes } = await readRecipeFolder(FIXTURES);
    assert.equal(await countRecipeFiles(FIXTURES), recipes.length);
    assert.equal(await countRecipeFiles(path.join(MARKETING, 'src/content/gallery')), 0, 'main has no published recipes yet');
    assert.equal(await countRecipeFiles(path.join(MARKETING, 'no-such-folder')), 0);
    assert.equal(galleryDir(MARKETING, {}), path.join(MARKETING, 'src/content/gallery'));
    assert.equal(galleryDir(MARKETING, { GALLERY_CONTENT_DIR: FIXTURES }), path.resolve(FIXTURES));
  });

  test('it imports nothing that npm ci installs, so it can run before the install', async () => {
    for (const file of ['scripts/open-list/gate.js', 'scripts/open-list/switch.js', 'src/lib/gallery-config.ts']) {
      const source = await readFile(path.join(MARKETING, file), 'utf8');
      for (const [, from] of source.matchAll(/^import\b[^;]*?from\s+'([^']+)'/gm)) {
        assert.ok(from.startsWith('node:') || from.startsWith('.'), `${file} imports ${from}`);
      }
    }
  });

  test('run as the workflow step runs it, it writes publish=false and exits 0', async (t) => {
    const dir = await mkdtemp(path.join(os.tmpdir(), 'open-list-gate-'));
    t.after(() => rm(dir, { recursive: true, force: true }));
    const output = path.join(dir, 'github-output');
    // The step's environment: its two variables, and what Actions provides. Nothing from this machine.
    const result = spawnSync(process.execPath, ['scripts/open-list/gate.js'], {
      cwd: MARKETING,
      env: { PATH: process.env.PATH, ...UNSET, GITHUB_OUTPUT: output, GITHUB_ACTIONS: 'true' },
      encoding: 'utf8',
    });
    assert.equal(result.status, 0, result.stderr);
    assert.equal(result.stdout.split('\n').filter((line) => line.startsWith('open-list: skip: ')).length, 3);
    assert.equal(await readFile(output, 'utf8'), 'publish=false\n');
  });
});

// ---------------------------------------------------------------------------

/** The workflow's steps, each as its lines of YAML. */
const steps = WORKFLOW.split('\n    steps:\n')[1].split(/\n(?=      - )/);
const stepNamed = (name) => {
  const step = steps.find((each) => each.includes(`name: ${name}\n`));
  assert.ok(step, `a step named ${name}`);
  return step;
};

describe('the workflow', () => {
  test('runs on pushes to main that touch the gallery or the generator, and by hand; never on pull requests', () => {
    const on = WORKFLOW.split('\non:\n')[1].split('\n\n')[0];
    assert.match(on, /^ {2}push:\n {4}branches: \[main\]\n {4}paths:\n/);
    const paths = [...on.matchAll(/^ {6}- '([^']+)'$/gm)].map((match) => match[1]);
    assert.deepEqual(paths, [
      'marketing/src/content/gallery/**',
      'marketing/scripts/open-list/**',
      'marketing/src/lib/gallery-config.ts',
      'marketing/scripts/gallery/config/removal-list.json',
      '.github/workflows/open-list.yml',
    ]);
    assert.match(on, /^ {2}workflow_dispatch:$/m);
    assert.doesNotMatch(YAML, /pull_request|workflow_run|schedule:|repository_dispatch/);
  });

  test('never runs for a fork, and needs no more than read access to this repository', () => {
    assert.match(WORKFLOW, /\n {4}if: github\.repository == 'GoDanceAi\/godance'\n/);
    assert.match(WORKFLOW, /\npermissions:\n {2}contents: read\n\n/);
    assert.equal(YAML.match(/permissions:/g).length, 1, 'no job asks for more');
    assert.doesNotMatch(YAML, /: write\b/);
    assert.match(steps[0], /uses: actions\/checkout@v4\n {8}with:\n {10}persist-credentials: false/);
  });

  test('pins each action as the other workflows pin it', async () => {
    const pinned = new Set();
    for (const file of ['ci.yml', 'deploy-frontends.yml']) {
      const other = await readFile(path.join(REPO, '.github/workflows', file), 'utf8');
      for (const [, uses] of other.matchAll(/uses: (\S+)/g)) pinned.add(uses);
    }
    const used = [...WORKFLOW.matchAll(/uses: (\S+)/g)].map((match) => match[1]);
    assert.deepEqual(used, ['actions/checkout@v4', 'actions/setup-node@v4']);
    for (const uses of used) assert.ok(pinned.has(uses), uses);
  });

  test('the gate comes first, as gate.js, told only whether the secret is set; every later step waits for it', () => {
    const gate = stepNamed('Gate');
    assert.match(gate, /id: gate\n/);
    assert.match(gate, /working-directory: marketing\n/);
    assert.match(gate, /OPEN_LIST_REPO: \$\{\{ vars\.OPEN_LIST_REPO \}\}\n/);
    assert.match(gate, /OPEN_LIST_DEPLOY_KEY_SET: \$\{\{ secrets\.OPEN_LIST_DEPLOY_KEY != '' \}\}\n/);
    assert.match(gate, /run: node scripts\/open-list\/gate\.js$/);
    assert.doesNotMatch(gate, /if:/);
    const after = steps.slice(steps.indexOf(gate) + 1);
    assert.ok(after.length >= 4);
    for (const step of after) assert.match(step, /\n {8}if: steps\.gate\.outputs\.publish == 'true'\n/, step.split('\n')[0]);
    // Before the gate: only the checkout and Node.
    assert.deepEqual(steps.slice(0, steps.indexOf(gate)).map((step) => /uses: (\S+)/.exec(step)?.[1]), ['actions/checkout@v4', 'actions/setup-node@v4']);
  });

  test('the deploy key reaches only the push step, through an ssh-agent, and is never written to a file', () => {
    const uses = [...WORKFLOW.matchAll(/secrets\.OPEN_LIST_DEPLOY_KEY\b[^}]*\}\}/g)].map((match) => match[0]);
    assert.deepEqual(uses, ["secrets.OPEN_LIST_DEPLOY_KEY != '' }}", 'secrets.OPEN_LIST_DEPLOY_KEY }}']);
    const push = stepNamed('Push the list to its repository');
    assert.match(push, /OPEN_LIST_DEPLOY_KEY: \$\{\{ secrets\.OPEN_LIST_DEPLOY_KEY \}\}/);
    const run = push.split('run: |\n')[1];
    const keyLines = run.split('\n').filter((line) => line.includes('OPEN_LIST_DEPLOY_KEY'));
    assert.deepEqual(keyLines.map((line) => line.trim()), [
      `printf '%s\\n' "$OPEN_LIST_DEPLOY_KEY" | ssh-add -q -`,
      'unset OPEN_LIST_DEPLOY_KEY',
    ]);
    assert.match(run, /eval "\$\(ssh-agent -s\)"/);
    assert.match(run, /trap 'ssh-agent -k > \/dev\/null' EXIT/);
    assert.doesNotMatch(run, /ssh-add[^\n]*(-f|\.pem|id_)|tee |>>?\s*"?\$?[A-Za-z_]*KEY/);
    assert.doesNotMatch(WORKFLOW, /ssh-key:|webfactory|persist-credentials: true/);
  });

  test("pins GitHub's published host keys, and checks them", () => {
    const run = stepNamed('Push the list to its repository');
    assert.match(run, /https:\/\/api\.github\.com\/meta \(ssh_keys\)/);
    for (const prefix of ['github.com ssh-ed25519 AAAAC3NzaC1lZDI1NTE5AAAAIOMqqnkVzrm0SdG6UOoqKLsabgH5C9okWi0dh2l9GKJl', 'github.com ecdsa-sha2-nistp256 ', 'github.com ssh-rsa ']) {
      assert.ok(run.includes(`'${prefix}`), prefix);
    }
    assert.match(run, /StrictHostKeyChecking=yes/);
    assert.match(run, /UserKnownHostsFile=\$known_hosts/);
    assert.doesNotMatch(WORKFLOW, /ssh-keyscan|StrictHostKeyChecking=(no|accept-new)/);
  });

  test('pushes through publish.js, and nothing anywhere forces a push', async () => {
    const run = stepNamed('Push the list to its repository');
    assert.match(run, /node scripts\/open-list\/publish\.js \\\n\s+--from "\$RUNNER_TEMP\/open-list" \\\n\s+--to "git@github\.com:\$OPEN_LIST_REPO\.git"/);
    assert.match(stepNamed('Generate the list'), /run: npm run open-list -- --out "\$RUNNER_TEMP\/open-list"$/);
    const publish = await readFile(path.join(MARKETING, 'scripts/open-list/publish.js'), 'utf8');
    for (const source of [WORKFLOW, publish]) {
      assert.doesNotMatch(source, /--force|--mirror|--delete|\+refs\/|\+HEAD|'-f'|push -f\b/);
    }
    assert.match(publish, /\['push', '--quiet', 'origin', `HEAD:refs\/heads\/\$\{branch\}`\]/);
  });

  test('runs the generator\'s tests before it publishes, and one run at a time', () => {
    assert.match(stepNamed('Test the generator'), /run: node --test --test-concurrency=1 "scripts\/open-list\/\*\.test\.js"$/);
    assert.match(WORKFLOW, /\nconcurrency:\n {2}group: open-list\n {2}cancel-in-progress: false\n/);
  });
});
