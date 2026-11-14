import { expect, test } from '@playwright/test';
import { defaultsIn, parseRecipe, recipeLinkOf } from '../src/lib/recipe';
import {
  checkTemplate,
  formTemplate,
  isVariableName,
  makeVariable,
  markersOf,
  parseTemplate,
  renderForm,
  renderTemplate,
  variablesToSave,
  withText,
  withValue,
  type FormTemplate,
} from '../src/lib/template';
import { MUG_TEMPLATE, recipeId } from './user-recipes';

// Round 10 brief 03. The template rules are the coordination notes', and the
// API (RecipeTemplateTest) and the gallery (gallery-template tests) check the
// same table. These tests run the modules directly; no page is opened.

const vars = (...pairs: [string, string][]) => pairs.map(([name, def]) => ({ name, default: def }));

test.describe('variable names', () => {
  for (const name of ['a', 'A1', 'color', 'lantern color', 'time-of-day', 'snake_case', 'a b-c_d', 'x'.repeat(40)]) {
    test(`"${name.length > 20 ? `${name.length} letters` : name}" is a name`, () => {
      expect(isVariableName(name)).toBe(true);
    });
  }
  for (const [what, name] of [
    ['empty', ''], ['a leading space', ' a'], ['a trailing space', 'a '], ['a leading hyphen', '-a'],
    ['a trailing underscore', 'a_'], ['41 letters', 'x'.repeat(41)], ['a brace', 'a{b'], ['a dot', 'a.b'],
    ['a non-ASCII letter', 'café'], ['Chinese', '颜色'], ['a line break', 'a\nb'],
  ] as const) {
    test(`${what} is not a name`, () => {
      expect(isVariableName(name)).toBe(false);
    });
  }
  test('only a string is a name', () => {
    expect(isVariableName(7)).toBe(false);
    expect(isVariableName(null)).toBe(false);
  });
});

test.describe('rendering, by the contract', () => {
  const cases: { name: string; template: string; variables: [string, string][]; values?: Record<string, string>; out: string }[] = [
    { name: 'a default fills a variable with no value', template: 'A {{color}} mug', variables: [['color', 'red']], out: 'A red mug' },
    { name: 'a value replaces the default', template: 'A {{color}} mug', variables: [['color', 'red']], values: { color: 'blue' }, out: 'A blue mug' },
    { name: 'an empty value is a value', template: 'A {{color}} mug', variables: [['color', 'red']], values: { color: '' }, out: 'A  mug' },
    { name: 'an empty default is a default', template: 'A {{color}}mug', variables: [['color', '']], out: 'A mug' },
    { name: 'a value containing a marker is inserted as it is', template: '{{a}} and {{b}}', variables: [['a', '1'], ['b', '2']], values: { a: '{{b}}' }, out: '{{b}} and 2' },
    { name: 'a default containing a marker is inserted as it is', template: '{{a}}', variables: [['a', '{{a}}']], out: '{{a}}' },
    { name: 'a stray }} is ordinary text', template: '{"a": {"b": "{{value}}"}}', variables: [['value', 'x']], out: '{"a": {"b": "x"}}' },
    { name: 'a variable used twice is filled twice', template: '{{x}}, {{x}}', variables: [['x', 'y']], out: 'y, y' },
    { name: 'a name with spaces, hyphens and underscores', template: '{{time of-day_1}}', variables: [['time of-day_1', 'dusk']], out: 'dusk' },
    { name: 'no variables at all', template: 'Plain text with }} in it', variables: [], out: 'Plain text with }} in it' },
    { name: 'a value for a variable the template lacks is ignored', template: '{{a}}', variables: [['a', '1']], values: { a: '2', zz: '3' }, out: '2' },
    { name: 'text around markers is kept exactly', template: '  {{a}}\n\t{{b}}  ', variables: [['a', '1'], ['b', '2']], out: '  1\n\t2  ' },
    { name: 'a single brace is ordinary text', template: '{ {{a}} }', variables: [['a', '1']], out: '{ 1 }' },
    { name: 'Chinese text around a marker', template: '一盏{{lantern color}}的纸灯笼', variables: [['lantern color', '暖红色']], out: '一盏暖红色的纸灯笼' },
  ];
  for (const c of cases) {
    test(c.name, () => {
      expect(renderTemplate(c.template, vars(...c.variables), c.values)).toBe(c.out);
    });
  }
});

test.describe('templates that break the rules', () => {
  test('an undeclared variable is refused, once however often it is used', () => {
    const problems = checkTemplate('{{a}} {{b}} {{b}}', ['a']);
    expect(problems.map((p) => [p.kind, p.name])).toEqual([['undeclared', 'b']]);
    expect(renderTemplate('{{a}} {{b}}', vars(['a', '1']))).toBeNull();
  });

  test('a declared variable that is never used is refused', () => {
    expect(checkTemplate('{{a}}', ['a', 'b']).map((p) => [p.kind, p.name])).toEqual([['unused', 'b']]);
    expect(renderTemplate('{{a}}', vars(['a', '1'], ['b', '2']))).toBeNull();
  });

  for (const [what, template] of [
    ['an empty name', 'a {{}} b'],
    ['a name that starts with a space', 'a {{ x}} b'],
    ['a name that ends with a space', 'a {{x }} b'],
    ['a name that is too long', `a {{${'x'.repeat(41)}}} b`],
    ['a marker that is never closed', 'a {{x b'],
    ['a name with a character outside the set', 'a {{x.y}} b'],
  ] as const) {
    test(`a "{{" that begins no variable is refused: ${what}`, () => {
      const problems = checkTemplate(template, ['x']);
      expect(problems.some((p) => p.kind === 'stray-open' && p.index === 2)).toBe(true);
      expect(renderTemplate(template, vars(['x', '1']))).toBeNull();
    });
  }

  test('three braces: the first "{{" is a stray, though a marker follows it', () => {
    expect(parseTemplate('{{{x}}}').strays).toEqual([0]);
    expect(markersOf('{{{x}}}')).toEqual(['x']);
    expect(renderTemplate('{{{x}}}', vars(['x', '1']))).toBeNull();
  });

  test('a problem says where, in words a person can act on', () => {
    expect(checkTemplate('ab {{', [])[0].message).toContain('position 4');
  });
});

test.describe("a form's template", () => {
  const form = () => formTemplate(MUG_TEMPLATE, vars(['color', 'red'], ['surface', 'kitchen shelf']));

  test('starts with every variable at its default', () => {
    expect(form().values).toEqual({ color: 'red', surface: 'kitchen shelf' });
    expect(renderForm(form())).toBe('A red mug on a kitchen shelf, soft morning light');
  });

  test('a changed value fills the prompt, and is what a save keeps as the default', () => {
    const changed = withValue(form(), 'color', 'blue');
    expect(renderForm(changed)).toBe('A blue mug on a kitchen shelf, soft morning light');
    expect(variablesToSave(changed)).toEqual(vars(['color', 'blue'], ['surface', 'kitchen shelf']));
  });

  test('a value is cut at the longest the API takes', () => {
    expect(withValue(form(), 'color', 'x'.repeat(2500)).values.color).toHaveLength(2000);
  });

  test('editing the text keeps the variables in step with its markers', () => {
    const edited = withText(withValue(form(), 'color', 'blue'), 'On a {{surface}}: a {{color}} {{thing}}');
    expect(edited.variables.map((v) => v.name)).toEqual(['surface', 'color', 'thing']);
    expect(edited.values).toEqual({ surface: 'kitchen shelf', color: 'blue', thing: '' });
    expect(withText(edited, 'A {{color}} mug').variables.map((v) => v.name)).toEqual(['color']);
  });

  test('a marker half typed is a problem, not a variable', () => {
    const typing = withText(form(), 'A {{color}} mug on a {{surf');
    expect(typing.variables.map((v) => v.name)).toEqual(['color']);
    expect(renderForm(typing)).toBeNull();
  });
});

test.describe('turning a selection into a variable', () => {
  const plain: FormTemplate = { text: 'A red mug on a shelf', variables: [], values: {} };
  const made = (template: FormTemplate, start: number, end: number, name: string) => makeVariable(template, start, end, name);

  test('the selection becomes the marker, and is the default and the value', () => {
    const result = made(plain, 2, 5, 'color');
    expect(result).toEqual({
      template: { text: 'A {{color}} mug on a shelf', variables: [{ name: 'color', default: 'red' }], values: { color: 'red' } },
    });
  });

  test('a second variable joins the first, in the order the text uses them', () => {
    const first = made(plain, 15, 20, 'surface');
    if (!('template' in first)) throw new Error('refused');
    const second = made(first.template, 2, 5, 'color');
    if (!('template' in second)) throw new Error('refused');
    expect(second.template.text).toBe('A {{color}} mug on a {{surface}}');
    expect(second.template.variables.map((v) => v.name)).toEqual(['color', 'surface']);
    expect(renderForm(second.template)).toBe('A red mug on a shelf');
  });

  test('the name is trimmed', () => {
    const result = made(plain, 2, 5, '  color  ');
    expect('template' in result && result.template.variables[0].name).toBe('color');
  });

  for (const [what, start, end, name, said] of [
    ['nothing selected', 3, 3, 'color', 'Select the text'],
    ['a name that is not one', 2, 5, 'co.lor', 'A name is 1–40'],
    ['an empty name', 2, 5, '  ', 'A name is 1–40'],
  ] as const) {
    test(`refused: ${what}`, () => {
      const result = made(plain, start, end, name);
      expect('refused' in result && result.refused).toContain(said);
    });
  }

  test('refused: a name already in use', () => {
    const first = made(plain, 2, 5, 'color');
    if (!('template' in first)) throw new Error('refused');
    const again = made(first.template, 12, 15, 'color');
    expect('refused' in again && again.refused).toContain('already a variable called “color”');
  });

  test('refused: a selection that cuts into a marker', () => {
    const first = made(plain, 2, 5, 'color');
    if (!('template' in first)) throw new Error('refused');
    // "A {{color}} mug…": from inside the marker to after it.
    const cut = made(first.template, 6, 14, 'thing');
    expect('refused' in cut && cut.refused).toContain('overlaps a variable');
  });

  test('refused: a selection right after a "{", which would leave a stray "{{"', () => {
    const result = made({ text: 'A {red} mug', variables: [], values: {} }, 3, 6, 'color');
    expect('refused' in result && result.refused).toContain('right after a “{”');
  });

  test('refused: a twenty-first variable', () => {
    const names = Array.from({ length: 20 }, (_, i) => `v${i}`);
    const full: FormTemplate = {
      text: `${names.map((n) => `{{${n}}}`).join(' ')} and more`,
      variables: names.map((name) => ({ name, default: '' })),
      values: Object.fromEntries(names.map((n) => [n, ''])),
    };
    const result = made(full, full.text.length - 4, full.text.length, 'extra');
    expect('refused' in result && result.refused).toContain('at most 20 variables');
  });
});

test.describe("a gallery recipe's template", () => {
  const GALLERY = 'https://godance.ai';
  const SLUG = 'paper-lantern-street-at-dusk';
  const EN = 'A street of warm red lanterns at dusk';
  const ZH = '黄昏时分挂满暖红色灯笼的小街';
  const recipe = (prompt: Record<string, unknown>) => ({
    slug: SLUG,
    title: { en: 'Lantern street', zh: '灯笼街' },
    modality: 'IMAGE',
    prompt,
    run: { model: 'gpt-image-2', prompt_lang: 'en', params: { size: '1024x1024' } },
    url: `${GALLERY}/gallery/${SLUG}`,
  });
  const templated = (extra: Record<string, unknown> = {}) => ({
    en: EN,
    zh: ZH,
    template: { en: 'A street of {{lantern color}} lanterns at {{time}}', zh: '{{time}}时分挂满{{lantern color}}灯笼的小街' },
    variables: [{ name: 'lantern color', default: 'warm red', zh_default: '暖红色' }, { name: 'time', default: 'dusk', zh_default: '黄昏' }],
    ...extra,
  });
  const parse = (prompt: Record<string, unknown>) => parseRecipe(recipe(prompt), { slug: SLUG, galleryOrigin: GALLERY });

  test('is read with its variables and each language\'s defaults', () => {
    const parsed = parse(templated());
    expect(parsed?.template?.text).toEqual({ en: 'A street of {{lantern color}} lanterns at {{time}}', zh: '{{time}}时分挂满{{lantern color}}灯笼的小街' });
    expect(defaultsIn(parsed!.template!.variables, 'en')).toEqual(vars(['lantern color', 'warm red'], ['time', 'dusk']));
    expect(defaultsIn(parsed!.template!.variables, 'zh')).toEqual(vars(['lantern color', '暖红色'], ['time', '黄昏']));
  });

  test('a variable without a Chinese default uses its default in both', () => {
    const parsed = parse(templated({
      en: 'A 16:9 street',
      zh: '一条 16:9 的街',
      template: { en: 'A {{ratio}} street', zh: '一条 {{ratio}} 的街' },
      variables: [{ name: 'ratio', default: '16:9' }],
    }));
    expect(defaultsIn(parsed!.template!.variables, 'zh')).toEqual(vars(['ratio', '16:9']));
  });

  test('a recipe without a template has none, and is still a recipe', () => {
    const parsed = parse({ en: EN, zh: ZH });
    expect(parsed?.prompt.en).toBe(EN);
    expect(parsed?.template).toBeNull();
  });

  for (const [what, extra] of [
    ['a template that does not give the prompt back', { template: { en: 'A lane of {{lantern color}} lanterns at {{time}}', zh: '{{time}}时分挂满{{lantern color}}灯笼的小街' } }],
    ['a template for only one of the prompt\'s languages', { template: { en: 'A street of {{lantern color}} lanterns at {{time}}' } }],
    ['an undeclared variable', { variables: [{ name: 'lantern color', default: 'warm red', zh_default: '暖红色' }] }],
    ['a name that is not one', { variables: [{ name: 'lantern.color', default: 'warm red' }, { name: 'time', default: 'dusk' }] }],
    ['two variables of one name', { variables: [{ name: 'time', default: 'dusk' }, { name: 'time', default: 'dusk' }] }],
    ['a default that is not text', { variables: [{ name: 'lantern color', default: 7 }, { name: 'time', default: 'dusk' }] }],
    ['a default longer than a value can be', { variables: [{ name: 'lantern color', default: 'x'.repeat(2001) }, { name: 'time', default: 'dusk' }] }],
    ['no variables', { variables: [] }],
    ['more variables than a recipe has', { variables: Array.from({ length: 21 }, (_, i) => ({ name: `v${i}`, default: '' })) }],
    ['a template that is not an object', { template: 'A street' }],
  ] as const) {
    test(`${what}: the template is dropped, and the plain prompt is kept`, () => {
      const parsed = parse(templated(extra as Record<string, unknown>));
      expect(parsed?.prompt).toEqual({ en: EN, zh: ZH });
      expect(parsed?.template).toBeNull();
    });
  }
});

test.describe('a link to a gallery recipe', () => {
  test('a link with ?recipe= opens one of the user\'s own recipes, not a gallery recipe', () => {
    expect(recipeLinkOf('/create/image?example=mug')).toEqual({ modality: 'IMAGE', slug: 'mug' });
    expect(recipeLinkOf(`/create/image?recipe=${recipeId(1)}&example=mug`)).toBeNull();
  });
});
