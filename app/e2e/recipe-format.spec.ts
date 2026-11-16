import { expect, test } from '@playwright/test';
import { isRecipeSlug, parseGalleryIndex, parseRecipe, recipeLinkOf } from '../src/lib/recipe';

// The gallery's JSON is untrusted input. These tests run the parsers directly;
// no page is opened. The shapes are the round 8 coordination doc's endpoints.

const GALLERY = 'https://godance.ai';
const SLUG = 'paper-lantern-street-at-dusk';
const PROMPT_EN = 'A narrow street of paper lanterns at dusk, warm red light, wet cobblestones';
const PROMPT_ZH = '黄昏时分挂满纸灯笼的小街，暖红色的灯光，湿润的石板路';

function recipe(overrides: Record<string, unknown> = {}): Record<string, unknown> {
  return {
    slug: SLUG,
    title: { en: 'Paper lantern street at dusk', zh: '黄昏的纸灯笼街' },
    modality: 'IMAGE',
    prompt: { en: PROMPT_EN, zh: PROMPT_ZH },
    run: { model: 'gpt-image-2', prompt_lang: 'en', params: { size: '1024x1024', n: 1 } },
    url: `${GALLERY}/gallery/${SLUG}`,
    ...overrides,
  };
}

function run(overrides: Record<string, unknown>): Record<string, unknown> {
  return { model: 'gpt-image-2', prompt_lang: 'en', params: { size: '1024x1024', n: 1 }, ...overrides };
}

const parse = (json: unknown) => parseRecipe(json, { slug: SLUG, galleryOrigin: GALLERY });

test.describe('recipe slugs', () => {
  test('are lowercase letters, digits and hyphens, at most 80 characters', () => {
    for (const ok of ['a', '0', SLUG, 'koi-pond-2', 'a'.repeat(80)]) {
      expect(isRecipeSlug(ok), ok).toBe(true);
    }
    for (const bad of ['', 'A', 'Paper-lantern', 'a_b', 'a b', 'a/b', '../x', 'a.json', '%2e%2e', 'é', 'a'.repeat(81), null, 7, undefined]) {
      expect(isRecipeSlug(bad), String(bad)).toBe(false);
    }
  });
});

test.describe('a recipe', () => {
  test('keeps the fields the app uses and nothing else', () => {
    const extra = recipe({ tags: ['street'], cost_cents: 6, run: run({ extra: true }), author: '<b>x</b>' });

    expect(parse(extra)).toEqual({
      slug: SLUG,
      title: { en: 'Paper lantern street at dusk', zh: '黄昏的纸灯笼街' },
      modality: 'IMAGE',
      prompt: { en: PROMPT_EN, zh: PROMPT_ZH },
      // Round 10: a recipe's template and variables, null for one that has none (template.spec.ts).
      template: null,
      run: { model: 'gpt-image-2', promptLang: 'en', params: { size: '1024x1024', n: 1 } },
      // Round 13: every run; a recipe whose JSON lists none has its one run.
      runs: [{ model: 'gpt-image-2', promptLang: 'en', params: { size: '1024x1024', n: 1 } }],
      url: `${GALLERY}/gallery/${SLUG}`,
      // Round 11: a recipe's input slots, empty for one that takes no input.
      inputs: [],
    });
  });

  test('keeps its input slots as the round 11 contract has them', () => {
    const slots = [{ slot: 'product photo', role: 'image', hint: 'One product on a plain background',
      sample_url: 'https://media.godance.ai/gallery/paper/input-0.webp', extra: true }];

    expect(parse(recipe({ inputs: slots }))?.inputs).toEqual([{
      slot: 'product photo',
      role: 'image',
      hint: 'One product on a plain background',
      sampleUrl: 'https://media.godance.ai/gallery/paper/input-0.webp',
    }]);
    // A video takes a first frame, a last frame and reference images; a hint may be missing.
    const video = recipe({ modality: 'VIDEO', run: run({ model: 'seedance-2.0' }), inputs: [
      { slot: 'opening', role: 'first_frame', sample_url: 'https://media.godance.ai/a.webp' },
      { slot: 'style', role: 'reference_image', hint: 7, sample_url: 'https://media.godance.ai/b%20c.webp' },
    ] });
    expect(parse(video)?.inputs.map((slot) => [slot.slot, slot.role, slot.hint])).toEqual([
      ['opening', 'first_frame', ''], ['style', 'reference_image', ''],
    ]);
  });

  test('keeps every run of runs it can read, in order, as the round 13 contract has them', () => {
    const runs = [
      { model: 'gpt-image-2', prompt_lang: 'en', params: { size: '1024x1024', n: 1 }, cost_cents: 6 },
      { model: 'seedream-5.0', prompt_lang: 'zh', params: { n: 1, quality: 'high' }, cost_cents: 4 },
      { model: 'not a model id', prompt_lang: 'en', params: {}, cost_cents: 4 },
      { model: 'flux-2-pro', prompt_lang: 'fr', params: {}, cost_cents: 4 },
      'qwen-image-2.0',
      { model: 'qwen-image-2.0', prompt_lang: 'en', cost_cents: 4 },
    ];
    expect(parse(recipe({ runs }))?.runs).toEqual([
      { model: 'gpt-image-2', promptLang: 'en', params: { size: '1024x1024', n: 1 } },
      { model: 'seedream-5.0', promptLang: 'zh', params: { n: 1 } },
      { model: 'qwen-image-2.0', promptLang: 'en', params: {} },
    ]);
    // A run in a language the recipe has no prompt in can't be opened, so it is left out.
    expect(parse(recipe({ prompt: { en: PROMPT_EN }, runs: runs.slice(0, 2) }))?.runs.map((run) => run.model)).toEqual(['gpt-image-2']);
    // runs that are not a list, or hold nothing the app can read, leave the recipe its one run.
    for (const odd of ['gpt-image-2', {}, [], [{ model: '' }]]) {
      expect(parse(recipe({ runs: odd }))?.runs).toEqual([{ model: 'gpt-image-2', promptLang: 'en', params: { size: '1024x1024', n: 1 } }]);
    }
  });

  test('drops input slots it cannot read, whole, and keeps the recipe', () => {
    const good = { slot: 'product photo', role: 'image', hint: 'x', sample_url: 'https://media.godance.ai/a.webp' };
    const unreadable: unknown[] = [
      'one',
      [],
      [good, { ...good, slot: 'b' }, { ...good, slot: 'c' }, { ...good, slot: 'd' }, { ...good, slot: 'e' }],   // more than four
      [good, good],                                             // the same name twice
      [{ ...good, slot: 'product photo!' }],
      [{ ...good, role: 'first_frame' }],                       // not a role an image recipe has
      [{ ...good, role: 'mask' }],
      [{ ...good, sample_url: 'http://media.godance.ai/a.webp' }],   // not https
      [{ ...good, sample_url: 'javascript:alert(1)' }],
      [{ ...good, sample_url: '/a.webp' }],
      [{ ...good, sample_url: undefined }],
      [good, 'two'],
    ];
    for (const inputs of unreadable) {
      const parsed = parse(recipe({ inputs }));
      expect(parsed, JSON.stringify(inputs)).not.toBeNull();
      expect(parsed?.inputs, JSON.stringify(inputs)).toEqual([]);
    }
  });

  test('is ignored whole when its shape does not match', () => {
    const misshapen: Record<string, unknown>[] = [
      recipe({ slug: 'another-recipe' }),
      recipe({ slug: undefined }),
      recipe({ title: 'Paper lantern street' }),
      recipe({ title: { fr: 'Rue des lanternes' } }),
      recipe({ title: { en: '   ' } }),
      recipe({ modality: 'AUDIO' }),
      recipe({ modality: 'image' }),
      recipe({ prompt: PROMPT_EN }),
      recipe({ prompt: { en: 42 } }),
      recipe({ run: undefined }),
      recipe({ run: [] }),
      recipe({ run: run({ model: undefined }) }),
      recipe({ run: run({ model: '' }) }),
      recipe({ run: run({ model: '<img src=x onerror=alert(1)>' }) }),
      recipe({ run: run({ model: 'm'.repeat(129) }) }),
      recipe({ run: run({ prompt_lang: 'fr' }) }),
      recipe({ run: run({ prompt_lang: undefined }) }),
      // The language the run used must be there.
      recipe({ prompt: { en: PROMPT_EN }, run: run({ prompt_lang: 'zh' }) }),
    ];
    for (const json of misshapen) {
      expect(parse(json), JSON.stringify(json)).toBeNull();
    }
    for (const notARecipe of [null, undefined, [], 'recipe', 42, true]) {
      expect(parse(notARecipe)).toBeNull();
    }
  });

  test('takes only a size, a count and a duration, each of the right kind and range', () => {
    const params = (value: unknown) => parse(recipe({ run: run({ params: value }) }))?.run.params;

    expect(params({ size: '1280x720', n: 2, seconds: 10 })).toEqual({ size: '1280x720', n: 2, seconds: 10 });
    expect(params({ size: '1024x1024; drop table', n: 1000, seconds: -5, style: 'vivid', quality: 'hd' })).toEqual({});
    expect(params({ size: 1024, n: '2', seconds: '10' })).toEqual({});
    expect(params({ n: 1.5, seconds: 601 })).toEqual({});
    expect(params({ n: 0, seconds: 0 })).toEqual({});
    expect(params('size=1024x1024')).toEqual({});
    expect(params(undefined)).toEqual({});
  });

  test('drops a text that is too long, and the recipe when that is the run language', () => {
    const longTitle = 'x'.repeat(201);
    expect(parse(recipe({ title: { en: longTitle, zh: '黄昏的纸灯笼街' } }))?.title).toEqual({ en: null, zh: '黄昏的纸灯笼街' });
    expect(parse(recipe({ title: { en: longTitle } }))).toBeNull();

    const longPrompt = 'y'.repeat(20_001);
    expect(parse(recipe({ prompt: { en: PROMPT_EN, zh: longPrompt } }))?.prompt).toEqual({ en: PROMPT_EN, zh: null });
    expect(parse(recipe({ prompt: { en: longPrompt, zh: PROMPT_ZH } }))).toBeNull();
  });

  test('may have its prompt in one language only', () => {
    expect(parse(recipe({ prompt: { en: PROMPT_EN } }))?.prompt).toEqual({ en: PROMPT_EN, zh: null });
    expect(parse(recipe({ prompt: { zh: PROMPT_ZH }, run: run({ prompt_lang: 'zh' }) }))?.prompt)
      .toEqual({ en: null, zh: PROMPT_ZH });
  });

  test('links only to its own page on the gallery', () => {
    const url = (value: unknown) => parse(recipe({ url: value }))?.url;

    expect(url(`${GALLERY}/gallery/${SLUG}`)).toBe(`${GALLERY}/gallery/${SLUG}`);
    for (const elsewhere of [
      'https://evil.example/gallery/x',
      'http://godance.ai/gallery/x',
      'https://godance.ai.evil.example/gallery/x',
      'https://user:pass@godance.ai/gallery/x',
      'javascript:alert(1)',
      'data:text/html,hi',
      `/gallery/${SLUG}`,
      42,
      undefined,
    ]) {
      expect(url(elsewhere), String(elsewhere)).toBeNull();
    }
  });

  test('loses characters that could make its text read differently', () => {
    const parsed = parse(recipe({
      title: { en: 'Paper ‮lantern‬ street\n\tat dusk\u0007' },
      prompt: { en: 'line one\nline two\u0000⁦ here', zh: PROMPT_ZH },
    }));

    expect(parsed?.title.en).toBe('Paper lantern street at dusk');
    expect(parsed?.prompt.en).toBe('line one\nline two here');
  });
});

test.describe('the gallery index', () => {
  const entry = (overrides: Record<string, unknown> = {}) => ({
    slug: SLUG,
    title: { en: 'Paper lantern street at dusk' },
    modality: 'IMAGE',
    model: 'gpt-image-2',
    tags: ['street'],
    thumb: 'https://media.godance.ai/paper-lantern-street-at-dusk/gpt-image-2-0-480.webp',
    published_on: '2026-11-21',
    ...overrides,
  });

  test('keeps the entries that match, in order, and drops the rest', () => {
    const index = {
      recipes: [
        entry(),
        entry({ slug: 'Bad_Slug' }),
        entry({ slug: 'koi-pond', modality: 'VIDEO', model: 'seedance-2.0', thumb: null }),
        entry({ slug: 'no-title', title: undefined }),
        entry({ slug: 'audio', modality: 'AUDIO' }),
        entry({ slug: 'bad-model', model: '' }),
        entry(), // the same slug twice
        'not an entry',
      ],
    };

    expect(parseGalleryIndex(index, GALLERY)).toEqual([
      {
        slug: SLUG,
        title: { en: 'Paper lantern street at dusk', zh: null },
        modality: 'IMAGE',
        model: 'gpt-image-2',
        thumb: 'https://media.godance.ai/paper-lantern-street-at-dusk/gpt-image-2-0-480.webp',
      },
      { slug: 'koi-pond', title: { en: 'Paper lantern street at dusk', zh: null }, modality: 'VIDEO', model: 'seedance-2.0', thumb: null },
    ]);
  });

  test('keeps only https thumbnails, or http ones while the gallery itself is on http', () => {
    const thumb = (value: unknown, gallery = GALLERY) => parseGalleryIndex({ recipes: [entry({ thumb: value })] }, gallery)[0].thumb;

    expect(thumb('http://media.godance.ai/x.webp')).toBeNull();
    expect(thumb('javascript:alert(1)')).toBeNull();
    expect(thumb('data:image/svg+xml,<svg/>')).toBeNull();
    expect(thumb('/relative.webp')).toBeNull();
    expect(thumb('http://media.godance.localhost/x.webp', 'http://godance.localhost')).toBe('http://media.godance.localhost/x.webp');
  });

  test('is empty when it is not the expected shape', () => {
    for (const json of [null, [], { recipes: 'x' }, { recipes: {} }, { items: [entry()] }, 'index']) {
      expect(parseGalleryIndex(json, GALLERY)).toEqual([]);
    }
  });
});

test.describe('recipe links', () => {
  test('are the Studio routes with a valid example slug and no job', () => {
    expect(recipeLinkOf(`/create/image?example=${SLUG}`)).toEqual({ modality: 'IMAGE', slug: SLUG });
    expect(recipeLinkOf('/create/video?example=koi-pond')).toEqual({ modality: 'VIDEO', slug: 'koi-pond' });
    expect(recipeLinkOf('/create/image/?example=koi-pond')).toEqual({ modality: 'IMAGE', slug: 'koi-pond' });
    expect(recipeLinkOf('/Create/Image?example=koi-pond')).toEqual({ modality: 'IMAGE', slug: 'koi-pond' });

    for (const path of [
      '/create/image',
      '/create/image?example=Koi-Pond',
      '/create/image?example=koi-pond&job=job_1',
      '/create/audio?example=koi-pond',
      '/keys?example=koi-pond',
      'https://evil.example/create/image?example=koi-pond',
      '//evil.example/create/image?example=koi-pond',
    ]) {
      expect(recipeLinkOf(path), path).toBeNull();
    }
  });
});
