/**
 * The folder reader and the `validate` command: a whole folder is checked,
 * every problem names its file and its rule, and the exit code says whether
 * the folder is valid.
 */
import assert from 'node:assert/strict';
import { spawnSync } from 'node:child_process';
import path from 'node:path';
import { describe, test } from 'node:test';
import { fileURLToPath } from 'node:url';
import { galleryFolder, imageRecipe, videoRecipe } from './example-recipes.js';
import { MARKETING_ROOT, formatReport, readRecipeFolder, resolveGalleryDir } from './read-recipes.js';

const VALIDATE = fileURLToPath(new URL('./validate.js', import.meta.url));

/**
 * Run the command as `npm run validate` does. GALLERY_CONTENT_DIR is taken
 * only from `env`, never from whoever is running the tests.
 */
function validate(args = [], { env = {}, cwd = MARKETING_ROOT } = {}) {
  const { GALLERY_CONTENT_DIR: _ignored, ...inherited } = process.env;
  const result = spawnSync(process.execPath, [VALIDATE, ...args], {
    cwd,
    env: { ...inherited, ...env },
    encoding: 'utf8',
  });
  return { status: result.status, stdout: result.stdout, stderr: result.stderr };
}

const problemsOf = async (dir) =>
  (await readRecipeFolder(dir)).problems.map(({ file, rule }) => `${file}: ${rule}`).sort();

describe('reading a folder', () => {
  test('valid recipes are returned with their slug and file', async (t) => {
    const dir = await galleryFolder(t, {
      'paper-lantern-street-at-dusk.json': imageRecipe(),
      'kite-over-a-salt-flat.json': videoRecipe(),
    });
    const { recipes, problems } = await readRecipeFolder(dir);
    assert.deepEqual(problems, []);
    assert.deepEqual(
      recipes.map(({ file, slug }) => [file, slug]),
      [
        ['kite-over-a-salt-flat.json', 'kite-over-a-salt-flat'],
        ['paper-lantern-street-at-dusk.json', 'paper-lantern-street-at-dusk'],
      ],
    );
    assert.deepEqual(recipes[1].data, imageRecipe());
  });

  test('an empty folder is valid', async (t) => {
    const dir = await galleryFolder(t);
    assert.deepEqual(await readRecipeFolder(dir), { recipes: [], problems: [] });
  });

  test('files that are not .json are ignored', async (t) => {
    const dir = await galleryFolder(t, { '.gitkeep': '', 'NOTICE.md': '# Notice\n' });
    assert.deepEqual(await readRecipeFolder(dir), { recipes: [], problems: [] });
  });

  test('a folder that does not exist is a problem, not an empty gallery', async (t) => {
    const dir = path.join(await galleryFolder(t), 'missing');
    assert.deepEqual(await problemsOf(dir), ['.: folder']);
  });

  test('a file that is not valid JSON is named', async (t) => {
    const dir = await galleryFolder(t, { 'broken.json': '{ "slug": "broken", ' });
    assert.deepEqual(await problemsOf(dir), ['broken.json: json']);
  });

  test('a recipe whose slug is not its file name is rejected', async (t) => {
    const dir = await galleryFolder(t, { 'lantern-street.json': imageRecipe() });
    assert.deepEqual(await problemsOf(dir), ['lantern-street.json: slug-file-name']);
  });

  test('a recipe in a subfolder is reported rather than left out', async (t) => {
    const dir = await galleryFolder(t, { 'drafts/kite-over-a-salt-flat.json': videoRecipe() });
    assert.deepEqual(await problemsOf(dir), ['drafts/kite-over-a-salt-flat.json: slug-file-name']);
  });

  test('two recipes with the same slug are both reported', async (t) => {
    const dir = await galleryFolder(t, {
      'paper-lantern-street-at-dusk.json': imageRecipe(),
      'lantern-street-copy.json': imageRecipe(),
    });
    const { recipes, problems } = await readRecipeFolder(dir);
    assert.deepEqual(recipes, []);
    assert.deepEqual(
      problems.filter(({ rule }) => rule === 'duplicate-slug').map(({ file, message }) => [file, message]),
      [
        ['lantern-street-copy.json', '"paper-lantern-street-at-dusk" is also used by paper-lantern-street-at-dusk.json'],
        ['paper-lantern-street-at-dusk.json', '"paper-lantern-street-at-dusk" is also used by lantern-street-copy.json'],
      ],
    );
  });

  test('one bad recipe does not hide the good ones or the other bad ones', async (t) => {
    const noChinese = imageRecipe();
    delete noChinese.prompt.zh;
    const badLicence = { ...videoRecipe(), source: { ...videoRecipe().source, licence: 'GPL-3.0' } };
    const dir = await galleryFolder(t, {
      'paper-lantern-street-at-dusk.json': noChinese,
      'kite-over-a-salt-flat.json': badLicence,
      'good-one.json': { ...imageRecipe(), slug: 'good-one' },
    });
    const { recipes } = await readRecipeFolder(dir);
    assert.deepEqual(recipes.map(({ slug }) => slug), ['good-one']);
    assert.deepEqual(await problemsOf(dir), [
      'kite-over-a-salt-flat.json: licence',
      'paper-lantern-street-at-dusk.json: prompt-languages',
    ]);
  });

  test('the report groups problems by file and names each rule', () => {
    const report = formatReport('/some/folder', [
      { file: 'b.json', rule: 'licence', path: 'source.licence', message: 'must be one of …' },
      { file: 'a.json', rule: 'json', path: '', message: 'not valid JSON: …' },
      { file: 'b.json', rule: 'iso-date', path: 'published_on', message: 'must be an ISO date, YYYY-MM-DD' },
    ]);
    assert.equal(
      report,
      [
        'gallery: 3 problems in 2 files under /some/folder',
        '',
        '  a.json',
        '    [json] not valid JSON: …',
        '',
        '  b.json',
        '    [licence] source.licence: must be one of …',
        '    [iso-date] published_on: must be an ISO date, YYYY-MM-DD',
      ].join('\n'),
    );
  });
});

describe('npm run validate', () => {
  test('passes a folder of valid recipes', async (t) => {
    const dir = await galleryFolder(t, {
      'paper-lantern-street-at-dusk.json': imageRecipe(),
      'kite-over-a-salt-flat.json': videoRecipe(),
    });
    const { status, stdout, stderr } = validate([dir]);
    assert.equal(status, 0, stderr);
    assert.equal(stdout.trim(), `gallery: 2 recipes in ${dir}, all valid`);
  });

  test('passes an empty folder', async (t) => {
    const dir = await galleryFolder(t);
    const { status, stdout } = validate([dir]);
    assert.equal(status, 0);
    assert.equal(stdout.trim(), `gallery: 0 recipes in ${dir}, all valid`);
  });

  test('passes the folder that is committed', () => {
    const { status, stdout, stderr } = validate();
    assert.equal(status, 0, stderr);
    assert.match(stdout, /src\/content\/gallery, all valid/);
  });

  test('fails a malformed recipe, naming the file and each rule it breaks', async (t) => {
    const recipe = imageRecipe();
    delete recipe.prompt.zh;
    recipe.source.original_url = 'http://x.com/example_author/status/0';
    recipe.runs[0].outputs[0].file = '../outside.webp';
    const dir = await galleryFolder(t, { 'paper-lantern-street-at-dusk.json': recipe });

    const { status, stdout, stderr } = validate([dir]);
    assert.equal(status, 1);
    assert.equal(stdout, '');
    assert.equal(
      stderr.trim(),
      [
        `gallery: 3 problems in 1 file under ${dir}`,
        '',
        '  paper-lantern-street-at-dusk.json',
        '    [prompt-languages] prompt.zh: required, and must not be empty',
        '    [media-path] runs[0].outputs[0].file: must be a relative path, but it contains ".."',
        '    [https-url] source.original_url: must be an https URL',
      ].join('\n'),
    );
  });

  test('catches a duplicate slug', async (t) => {
    const dir = await galleryFolder(t, {
      'paper-lantern-street-at-dusk.json': imageRecipe(),
      'lantern-street-copy.json': imageRecipe(),
    });
    const { status, stderr } = validate([dir]);
    assert.equal(status, 1);
    assert.match(stderr, /lantern-street-copy\.json\n(?:.*\n)*? {4}\[duplicate-slug\] slug: "paper-lantern-street-at-dusk" is also used by paper-lantern-street-at-dusk\.json/);
    assert.match(stderr, / {2}paper-lantern-street-at-dusk\.json\n {4}\[duplicate-slug\] slug: "paper-lantern-street-at-dusk" is also used by lantern-street-copy\.json/);
  });

  test('fails a folder that does not exist', async (t) => {
    const dir = path.join(await galleryFolder(t), 'missing');
    const { status, stderr } = validate([dir]);
    assert.equal(status, 1);
    assert.match(stderr, /\[folder\] the gallery folder does not exist/);
  });

  test('takes a folder relative to where it is run', async (t) => {
    const dir = await galleryFolder(t, { 'recipes/kite-over-a-salt-flat.json': { ...videoRecipe() } });
    // In recipes/, the file is at the top level of the folder being checked.
    const { status, stdout, stderr } = validate(['recipes'], { cwd: dir });
    assert.equal(status, 0, stderr);
    assert.match(stdout, /^gallery: 1 recipe in /);
  });

  test('refuses more than one folder', () => {
    const { status, stderr } = validate(['a', 'b']);
    assert.equal(status, 2);
    assert.match(stderr, /^Usage:/);
  });
});

describe('GALLERY_CONTENT_DIR', () => {
  test('the command checks the folder it names', async (t) => {
    const dir = await galleryFolder(t, { 'kite-over-a-salt-flat.json': videoRecipe() });
    const { status, stdout, stderr } = validate([], { env: { GALLERY_CONTENT_DIR: dir } });
    assert.equal(status, 0, stderr);
    assert.equal(stdout.trim(), `gallery: 1 recipe in ${dir}, all valid`);
  });

  test('a malformed recipe there fails the command', async (t) => {
    const recipe = videoRecipe();
    delete recipe.runs[0].params.seconds;
    const dir = await galleryFolder(t, { 'kite-over-a-salt-flat.json': recipe });
    const { status, stderr } = validate([], { env: { GALLERY_CONTENT_DIR: dir } });
    assert.equal(status, 1);
    assert.match(stderr, /kite-over-a-salt-flat\.json\n {4}\[video-seconds\] runs\[0\]\.params\.seconds:/);
  });

  test('a folder given on the command line wins over it', async (t) => {
    const bad = await galleryFolder(t, { 'broken.json': 'not json' });
    const good = await galleryFolder(t);
    assert.equal(validate([good], { env: { GALLERY_CONTENT_DIR: bad } }).status, 0);
    assert.equal(validate([bad], { env: { GALLERY_CONTENT_DIR: good } }).status, 1);
  });

  test('a relative value is relative to the marketing project, wherever the command is run', async (t) => {
    const elsewhere = await galleryFolder(t);
    const { status, stdout, stderr } = validate([], { env: { GALLERY_CONTENT_DIR: 'src/content/gallery' }, cwd: elsewhere });
    assert.equal(status, 0, stderr);
    assert.ok(stdout.includes(path.join(MARKETING_ROOT, 'src/content/gallery')), stdout);
  });

  test('resolveGalleryDir applies it to a root', () => {
    assert.equal(resolveGalleryDir({ root: '/site', env: {} }), '/site/src/content/gallery');
    assert.equal(resolveGalleryDir({ root: '/site', env: { GALLERY_CONTENT_DIR: 'fixtures/gallery' } }), '/site/fixtures/gallery');
    assert.equal(resolveGalleryDir({ root: '/site', env: { GALLERY_CONTENT_DIR: '/tmp/recipes' } }), '/tmp/recipes');
  });
});
