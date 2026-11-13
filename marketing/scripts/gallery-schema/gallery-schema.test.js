/**
 * The recipe format: what it accepts, and that each rule rejects a recipe
 * that breaks it.
 */
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  DEFAULT_GALLERY_DIR,
  RULES,
  checkRecipe,
  formatProblem,
  galleryContentDir,
  recipeFileName,
  recipeSchema,
} from '../../src/lib/gallery-schema.js';
import { imageRecipe, videoRecipe } from './example-recipes.js';

/** The rule and path of every problem, which is what the tests compare. */
const found = (data, options) => checkRecipe(data, options).map(({ rule, path }) => `${rule} @ ${path}`);

describe('a valid recipe', () => {
  test('the example from the coordination notes is accepted', () => {
    assert.deepEqual(checkRecipe(imageRecipe()), []);
  });

  test('it is accepted under its own file name', () => {
    assert.deepEqual(checkRecipe(imageRecipe(), { file: 'paper-lantern-street-at-dusk.json' }), []);
  });

  test('a video recipe is accepted', () => {
    assert.deepEqual(checkRecipe(videoRecipe(), { file: 'kite-over-a-salt-flat.json' }), []);
  });

  test('parsing changes nothing: no key is dropped, added or rewritten', () => {
    assert.deepEqual(recipeSchema.parse(imageRecipe()), imageRecipe());
    assert.deepEqual(recipeSchema.parse(videoRecipe()), videoRecipe());
  });

  test('title.zh, prompt.variables and a video poster are optional; tags and changes may be empty', () => {
    const image = imageRecipe();
    delete image.title.zh;
    delete image.prompt.variables;
    image.tags = [];
    image.source.changes = [];
    assert.deepEqual(checkRecipe(image), []);

    const video = videoRecipe();
    delete video.runs[0].outputs[0].poster;
    assert.deepEqual(checkRecipe(video), []);
  });

  test('a recipe can have more runs, and a run more outputs', () => {
    const recipe = imageRecipe();
    recipe.runs.push({ ...structuredClone(recipe.runs[0]), model: 'another-model', prompt_lang: 'zh' });
    recipe.runs[0].outputs.push({ ...recipe.runs[0].outputs[0], file: 'paper-lantern-street-at-dusk/gpt-image-2-1.webp' });
    assert.deepEqual(checkRecipe(recipe), []);
  });

  test('params takes whatever request fields the API has', () => {
    const recipe = imageRecipe();
    recipe.runs[0].params = { size: '1536x1024', n: 2, quality: 'high', nested: { any: ['thing'] } };
    assert.deepEqual(checkRecipe(recipe), []);
  });
});

/**
 * One row per way of breaking a rule: [rule, what is wrong, how to break a
 * valid recipe, where the problem is reported]. Each must produce exactly
 * that one problem.
 */
const BREAKS = [
  // slug: lowercase letters, digits and hyphens
  ['slug-format', 'an uppercase letter', (r) => (r.slug = 'Paper-lantern'), 'slug'],
  ['slug-format', 'an underscore', (r) => (r.slug = 'paper_lantern'), 'slug'],
  ['slug-format', 'a space', (r) => (r.slug = 'paper lantern'), 'slug'],
  ['slug-format', 'a slash', (r) => (r.slug = 'paper/lantern'), 'slug'],
  ['slug-format', 'a non-ASCII letter', (r) => (r.slug = 'café-terrace'), 'slug'],
  ['slug-format', 'an empty slug', (r) => (r.slug = ''), 'slug'],
  ['slug-format', 'no slug', (r) => delete r.slug, 'slug'],

  // both prompt.en and prompt.zh present and non-empty
  ['prompt-languages', 'no prompt.zh', (r) => delete r.prompt.zh, 'prompt.zh'],
  ['prompt-languages', 'no prompt.en', (r) => delete r.prompt.en, 'prompt.en'],
  ['prompt-languages', 'an empty prompt.zh', (r) => (r.prompt.zh = ''), 'prompt.zh'],
  ['prompt-languages', 'a blank prompt.en', (r) => (r.prompt.en = '  \n'), 'prompt.en'],
  ['prompt-languages', 'no prompt at all', (r) => delete r.prompt, 'prompt'],

  // at least one run
  ['runs-min', 'an empty list of runs', (r) => (r.runs = []), 'runs'],
  ['runs-min', 'no runs key', (r) => delete r.runs, 'runs'],

  // every run has a cost_cents
  ['run-cost', 'a run without cost_cents', (r) => delete r.runs[0].cost_cents, 'runs[0].cost_cents'],
  ['run-cost', 'a cost that is not a whole number', (r) => (r.runs[0].cost_cents = 6.5), 'runs[0].cost_cents'],
  ['run-cost', 'a cost given as text', (r) => (r.runs[0].cost_cents = '6'), 'runs[0].cost_cents'],
  ['run-cost', 'a negative cost', (r) => (r.runs[0].cost_cents = -1), 'runs[0].cost_cents'],

  // every run has at least one output
  ['run-outputs', 'a run with an empty list of outputs', (r) => (r.runs[0].outputs = []), 'runs[0].outputs'],
  ['run-outputs', 'a run with no outputs key', (r) => delete r.runs[0].outputs, 'runs[0].outputs'],

  // media paths are relative: no scheme, no leading slash, no ".."
  ['media-path', 'a URL', (r) => (r.runs[0].outputs[0].file = 'https://cdn.example/a.webp'), 'runs[0].outputs[0].file'],
  ['media-path', 'a data: URL', (r) => (r.runs[0].outputs[0].thumb = 'data:image/webp;base64,AAAA'), 'runs[0].outputs[0].thumb'],
  ['media-path', 'a leading slash', (r) => (r.runs[0].outputs[0].file = '/a/b.webp'), 'runs[0].outputs[0].file'],
  ['media-path', 'a protocol-relative path', (r) => (r.runs[0].outputs[0].thumb = '//cdn.example/a.webp'), 'runs[0].outputs[0].thumb'],
  ['media-path', 'a ".." segment', (r) => (r.runs[0].outputs[0].file = 'a/../../b.webp'), 'runs[0].outputs[0].file'],
  ['media-path', 'a path that starts with ".."', (r) => (r.runs[0].outputs[0].file = '../b.webp'), 'runs[0].outputs[0].file'],
  ['media-path', 'a backslash', (r) => (r.runs[0].outputs[0].file = 'a\\b.webp'), 'runs[0].outputs[0].file'],
  ['media-path', 'an empty path', (r) => (r.runs[0].outputs[0].file = ''), 'runs[0].outputs[0].file'],
  ['media-path', 'no file', (r) => delete r.runs[0].outputs[0].file, 'runs[0].outputs[0].file'],
  ['media-path', 'an image output with no thumb', (r) => delete r.runs[0].outputs[0].thumb, 'runs[0].outputs[0].thumb'],

  // source.licence is one of the four
  ['licence', 'a licence that is not on the list', (r) => (r.source.licence = 'CC-BY-NC-4.0'), 'source.licence'],
  ['licence', 'a licence in the wrong case', (r) => (r.source.licence = 'mit'), 'source.licence'],
  ['licence', 'no licence', (r) => delete r.source.licence, 'source.licence'],

  // source.original_url and source.author_url are https URLs
  ['https-url', 'an http original_url', (r) => (r.source.original_url = 'http://x.com/a/status/1'), 'source.original_url'],
  ['https-url', 'an http author_url', (r) => (r.source.author_url = 'http://x.com/a'), 'source.author_url'],
  ['https-url', 'a URL without a scheme', (r) => (r.source.author_url = 'x.com/a'), 'source.author_url'],
  ['https-url', 'https with no slashes', (r) => (r.source.author_url = 'https:x.com/a'), 'source.author_url'],
  ['https-url', 'another scheme', (r) => (r.source.original_url = 'javascript:alert(1)'), 'source.original_url'],
  ['https-url', 'a URL with a space in it', (r) => (r.source.original_url = 'https://x.com/a b'), 'source.original_url'],
  ['https-url', 'no original_url', (r) => delete r.source.original_url, 'source.original_url'],
  ['https-url', 'no author_url', (r) => delete r.source.author_url, 'source.author_url'],

  // dates are ISO dates
  ['iso-date', 'published_on with a time', (r) => (r.published_on = '2026-11-21T10:00:00Z'), 'published_on'],
  ['iso-date', 'published_on in another order', (r) => (r.published_on = '21/11/2026'), 'published_on'],
  ['iso-date', 'no published_on', (r) => delete r.published_on, 'published_on'],
  ['iso-date', 'a day that does not exist', (r) => (r.review.approved_on = '2026-02-30'), 'review.approved_on'],
  ['iso-date', 'a date without leading zeros', (r) => (r.runs[0].generated_on = '2026-1-5'), 'runs[0].generated_on'],
  ['iso-date', 'a date given as a number', (r) => (r.runs[0].generated_on = 20261120), 'runs[0].generated_on'],

  // the rest of the shape
  ['shape', 'an unknown key', (r) => (r.notes = 'x'), ''],
  ['shape', 'an unknown key in an output', (r) => (r.runs[0].outputs[0].alt = 'x'), 'runs[0].outputs[0]'],
  ['shape', 'a misspelt key', (r) => ((r.source.license = r.source.licence), 0), 'source'],
  ['shape', 'a modality the gallery does not have', (r) => (r.modality = 'AUDIO'), 'modality'],
  ['shape', 'no modality', (r) => delete r.modality, 'modality'],
  ['shape', 'no title.en', (r) => delete r.title.en, 'title.en'],
  ['shape', 'tags that is not a list', (r) => (r.tags = 'street'), 'tags'],
  ['shape', 'an unknown zh_origin', (r) => (r.prompt.zh_origin = 'human'), 'prompt.zh_origin'],
  ['shape', 'a variable with no default', (r) => delete r.prompt.variables[0].default, 'prompt.variables[0].default'],
  ['shape', 'an unknown prompt_lang', (r) => (r.runs[0].prompt_lang = 'fr'), 'runs[0].prompt_lang'],
  ['shape', 'no params', (r) => delete r.runs[0].params, 'runs[0].params'],
  ['shape', 'a width that is not a whole number', (r) => (r.runs[0].outputs[0].width = 10.5), 'runs[0].outputs[0].width'],
  ['shape', 'a video content type on an image', (r) => (r.runs[0].outputs[0].content_type = 'video/mp4'), 'runs[0].outputs[0].content_type'],
  ['shape', 'no source', (r) => delete r.source, 'source'],
  ['shape', 'no review', (r) => delete r.review, 'review'],
  ['shape', 'a standard_version of zero', (r) => (r.review.standard_version = 0), 'review.standard_version'],
];

/** The same, starting from the video recipe. */
const VIDEO_BREAKS = [
  // a video run states seconds
  ['video-seconds', 'a video run without seconds', (r) => delete r.runs[0].params.seconds, 'runs[0].params.seconds'],
  ['video-seconds', 'seconds given as text', (r) => (r.runs[0].params.seconds = '5'), 'runs[0].params.seconds'],
  ['video-seconds', 'seconds of zero', (r) => (r.runs[0].params.seconds = 0), 'runs[0].params.seconds'],
  ['video-seconds', 'seconds that is not a whole number', (r) => (r.runs[0].params.seconds = 2.5), 'runs[0].params.seconds'],

  ['media-path', 'a poster that is a URL', (r) => (r.runs[0].outputs[0].poster = 'https://cdn.example/p.webp'), 'runs[0].outputs[0].poster'],
  ['shape', 'a video output with no duration_seconds', (r) => delete r.runs[0].outputs[0].duration_seconds, 'runs[0].outputs[0].duration_seconds'],
  ['shape', 'an image content type on a video', (r) => (r.runs[0].outputs[0].content_type = 'image/webp'), 'runs[0].outputs[0].content_type'],
];

describe('each rule rejects a recipe that breaks it', () => {
  for (const [rule, what, breakIt, path] of BREAKS) {
    test(`${rule}: ${what}`, () => {
      const recipe = imageRecipe();
      breakIt(recipe);
      assert.deepEqual(found(recipe), [`${rule} @ ${path}`]);
    });
  }

  for (const [rule, what, breakIt, path] of VIDEO_BREAKS) {
    test(`${rule}: ${what}`, () => {
      const recipe = videoRecipe();
      breakIt(recipe);
      assert.deepEqual(found(recipe), [`${rule} @ ${path}`]);
    });
  }

  test('a second run is checked like the first', () => {
    const recipe = videoRecipe();
    recipe.runs.push(structuredClone(recipe.runs[0]));
    delete recipe.runs[1].params.seconds;
    assert.deepEqual(found(recipe), ['video-seconds @ runs[1].params.seconds']);
  });

  test('an image recipe cannot carry a video output, or the other way round', () => {
    const image = imageRecipe();
    image.runs[0].outputs = videoRecipe().runs[0].outputs;
    assert.ok(checkRecipe(image).length > 0);

    const video = videoRecipe();
    video.runs[0].outputs = imageRecipe().runs[0].outputs;
    assert.ok(checkRecipe(video).length > 0);
  });

  test('every problem in a recipe is reported, not only the first', () => {
    const recipe = imageRecipe();
    recipe.slug = 'Bad Slug';
    delete recipe.prompt.zh;
    recipe.source.licence = 'GPL-3.0';
    assert.deepEqual(found(recipe).sort(), ['licence @ source.licence', 'prompt-languages @ prompt.zh', 'slug-format @ slug']);
  });

  for (const value of [null, [], 'a recipe', 42]) {
    test(`a file holding ${JSON.stringify(value)} is not a recipe`, () => {
      assert.deepEqual(found(value, { file: 'x.json' }), ['shape @ ']);
    });
  }

  test('the table above covers every rule a single recipe can break', () => {
    const covered = new Set([...BREAKS, ...VIDEO_BREAKS].map(([rule]) => rule));
    const folderRules = ['slug-file-name', 'json', 'duplicate-slug', 'folder']; // covered below and in validate.test.js
    for (const rule of Object.keys(RULES)) {
      if (!folderRules.includes(rule)) assert.ok(covered.has(rule), `no test breaks the rule "${rule}"`);
    }
  });
});

describe('slug equals the file name', () => {
  test('a recipe under another file name is rejected', () => {
    assert.deepEqual(found(imageRecipe(), { file: 'lantern-street.json' }), ['slug-file-name @ slug']);
  });

  test('a recipe in a subfolder is rejected, even under its own name', () => {
    assert.deepEqual(found(imageRecipe(), { file: 'drafts/paper-lantern-street-at-dusk.json' }), ['slug-file-name @ slug']);
  });

  test('the file name is compared exactly', () => {
    assert.deepEqual(found(imageRecipe(), { file: 'Paper-Lantern-Street-At-Dusk.json' }), ['slug-file-name @ slug']);
  });

  test('without a file name, only the recipe itself is checked', () => {
    assert.deepEqual(found(imageRecipe()), []);
  });

  test('recipeFileName gives the file a slug belongs in', () => {
    assert.equal(recipeFileName('paper-lantern-street-at-dusk'), 'paper-lantern-street-at-dusk.json');
  });
});

describe('reporting', () => {
  test('a problem names its rule, where it is, and what is wrong', () => {
    const recipe = imageRecipe();
    recipe.source.licence = 'GPL-3.0';
    const [problem] = checkRecipe(recipe);
    assert.deepEqual(problem, {
      rule: 'licence',
      path: 'source.licence',
      message: 'must be one of CC0-1.0, CC-BY-4.0, MIT, Apache-2.0',
    });
    assert.equal(formatProblem(problem), '[licence] source.licence: must be one of CC0-1.0, CC-BY-4.0, MIT, Apache-2.0');
  });

  test('a media path problem says what is wrong with the path', () => {
    const recipe = imageRecipe();
    recipe.runs[0].outputs[0].file = '/abs/a.webp';
    assert.match(checkRecipe(recipe)[0].message, /starts with a slash/);
  });
});

describe('the gallery folder setting', () => {
  test('defaults to src/content/gallery', () => {
    assert.equal(DEFAULT_GALLERY_DIR, 'src/content/gallery');
    assert.equal(galleryContentDir({}), 'src/content/gallery');
  });

  test('GALLERY_CONTENT_DIR overrides it', () => {
    assert.equal(galleryContentDir({ GALLERY_CONTENT_DIR: 'fixtures/gallery' }), 'fixtures/gallery');
  });

  test('an empty GALLERY_CONTENT_DIR is the same as none', () => {
    assert.equal(galleryContentDir({ GALLERY_CONTENT_DIR: '' }), 'src/content/gallery');
    assert.equal(galleryContentDir({ GALLERY_CONTENT_DIR: '  ' }), 'src/content/gallery');
  });
});
