/**
 * Recipe templates (round 10 brief 02): the renderer's rules, read against
 * the round-10 coordination notes, and the templates the format accepts.
 * That each new rule rejects a recipe that breaks it is in
 * gallery-schema.test.js, with every other rule.
 *
 * The case table below is the one the backend's renderer is tested against
 * too (brief 03): the two implement the same rules.
 */
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import { checkRecipe } from '../../src/lib/gallery-schema.js';
import {
  TemplateError,
  checkTemplate,
  defaultsIn,
  isVariableName,
  markersOf,
  parseTemplate,
  renderTemplate,
} from '../../src/lib/gallery-template.js';
import { imageRecipe, templatedRecipe } from './example-recipes.js';

const v = (name, fallback) => ({ name, default: fallback });

describe('a variable name', () => {
  for (const name of ['a', 'x1', 'lantern color', 'boat-colour', 'sign_text', 'A B-c_9', '9 lives', 'a'.repeat(40)]) {
    test(`"${name.length > 20 ? `${name.length} letters` : name}" is a name`, () => assert.equal(isVariableName(name), true));
  }
  for (const [name, why] of [
    ['', 'empty'], [' a', 'starts with a space'], ['a ', 'ends with a space'], ['-a', 'starts with a hyphen'], ['a_', 'ends with an underscore'],
    ['a'.repeat(41), '41 characters'], ['colour!', 'punctuation'], ['颜色', 'not ASCII'], ['café', 'an accented letter'], ['a.b', 'a full stop'],
    ['a{b', 'a brace'], ['a\tb', 'a tab'],
  ]) {
    test(`a name that is ${why} is refused`, () => assert.equal(isVariableName(name), false));
  }
  test('only text is a name', () => {
    for (const value of [null, undefined, 7, ['a']]) assert.equal(isVariableName(value), false);
  });
});

/**
 * Rendering, case by case: [what, template, variables, values, result].
 * A result that is an array is the kinds of problem the template has.
 */
const RENDER_CASES = [
  // Rule 3: the value given, or the default. An empty string is a value.
  ['a default fills a variable with no value', 'A {{color}} mug.', [v('color', 'red')], {}, 'A red mug.'],
  ['a value replaces the default', 'A {{color}} mug.', [v('color', 'red')], { color: 'blue' }, 'A blue mug.'],
  ['an empty value is a value', 'A {{color}} mug.', [v('color', 'red')], { color: '' }, 'A  mug.'],
  ['an empty default is a default', 'A {{color}}mug.', [v('color', '')], {}, 'A mug.'],
  ['a variable used twice is filled twice', '{{a}} and {{a}}', [v('a', 'x')], {}, 'x and x'],
  ['two variables side by side', '{{a}}{{b}}', [v('a', '1'), v('b', '2')], { b: '3' }, '13'],
  ['a name with spaces, a hyphen and an underscore', '{{lantern color}} {{boat-colour}} {{sign_text}}',
    [v('lantern color', 'red'), v('boat-colour', 'blue'), v('sign_text', 'OPEN')], {}, 'red blue OPEN'],
  ['a value for a variable that is not declared is ignored', 'A {{color}} mug.', [v('color', 'red')], { size: 'big' }, 'A red mug.'],
  ['a value given through the prototype is not a value', 'A {{toString}} mug.', [v('toString', 'red')], {}, 'A red mug.'],

  // Rule 4: one pass. What is inserted is not read again.
  ['a value containing a marker is inserted as it is', 'A {{color}} mug.', [v('color', 'red')], { color: '{{color}}' }, 'A {{color}} mug.'],
  ['a value containing another variable is not expanded', '{{a}} {{b}}', [v('a', '1'), v('b', '2')], { a: '{{b}}' }, '{{b}} 2'],
  ['a default containing a marker is inserted as it is', '{{a}}', [v('a', '{{a}} {{zzz}}')], {}, '{{a}} {{zzz}}'],
  ['a value containing a stray "{{" is inserted as it is', '{{a}}!', [v('a', 'x')], { a: '{{' }, '{{!'],

  // Rule 5: a stray "}}" is allowed, a stray "{{" is not.
  ['a stray "}}" is text', 'x }} {{a}} }}', [v('a', '1')], {}, 'x }} 1 }}'],
  ['a JSON-shaped template that ends in "}}"', '{"subject":{"colour":"{{colour}}"}}', [v('colour', 'teal')], {}, '{"subject":{"colour":"teal"}}'],
  ['single braces are text', '{ {{a}} } {a}', [v('a', '1')], {}, '{ 1 } {a}'],
  ['a marker right after a closing pair', '}}{{a}}', [v('a', '1')], {}, '}}1'],
  ['a stray "{{"', 'x {{ y {{a}}', [v('a', '1')], {}, ['stray-open']],
  ['a "{{" at the very end', '{{a}} {{', [v('a', '1')], {}, ['stray-open']],
  ['spaces inside the braces', '{{ a }}', [v('a', '1')], {}, ['stray-open', 'unused']],
  ['a marker that is never closed', '{{a} {{a}}', [v('a', '1')], {}, ['stray-open']],
  ['an empty marker', '{{}} {{a}}', [v('a', '1')], {}, ['stray-open']],
  ['a name that breaks the naming rule', '{{a!}} {{a}}', [v('a', '1')], {}, ['stray-open']],
  ['three braces: the first two begin nothing', '{{{a}}}', [v('a', '1')], {}, ['stray-open']],
  ['a variable that is not declared', '{{a}} {{b}}', [v('a', '1')], {}, ['undeclared']],
  ['an undeclared variable is reported once', '{{b}} {{b}} {{a}}', [v('a', '1')], {}, ['undeclared']],
  ['names are compared exactly', '{{A}}', [v('a', '1')], {}, ['undeclared', 'unused']],

  // Rule 6: every declared variable appears at least once.
  ['a declared variable the template never uses', '{{a}}', [v('a', '1'), v('b', '2')], {}, ['unused']],
  ['a template with no markers and no variables', 'Just text, with {single} braces.', [], {}, 'Just text, with {single} braces.'],
];

describe('rendering a template', () => {
  for (const [what, template, variables, values, result] of RENDER_CASES) {
    test(what, () => {
      const names = variables.map((variable) => variable.name);
      if (typeof result === 'string') {
        assert.deepEqual(checkTemplate(template, names), []);
        assert.equal(renderTemplate(template, variables, values), result);
      } else {
        assert.deepEqual(checkTemplate(template, names).map((problem) => problem.kind), result);
        assert.throws(() => renderTemplate(template, variables, values), TemplateError);
      }
    });
  }

  test('a problem says what is wrong, and where', () => {
    const [stray] = checkTemplate('ab {{ c', []);
    assert.equal(stray.index, 3);
    assert.match(stray.message, /"\{\{" at position 3 does not begin a variable/);
    assert.match(checkTemplate('{{b}}', [])[0].message, /\{\{b\}\} is not a declared variable/);
    assert.match(checkTemplate('x', ['b'])[0].message, /"b" is declared but the template never uses it/);
  });

  test('the markers of a template, in order', () => {
    assert.deepEqual(markersOf('{{b}} x {{a}} }} {{b}}'), ['b', 'a', 'b']);
    assert.deepEqual(markersOf('no markers {here}'), []);
    assert.deepEqual(parseTemplate('a{{b}}c').parts, [{ text: 'a' }, { name: 'b' }, { text: 'c' }]);
  });

  test('Chinese uses zh_default where a variable has one, and default otherwise', () => {
    const variables = [{ name: 'color', default: 'warm red', zh_default: '暖红色' }, { name: 'lens', default: '35mm' }];
    assert.deepEqual(defaultsIn(variables, 'zh'), [v('color', '暖红色'), v('lens', '35mm')]);
    assert.deepEqual(defaultsIn(variables, 'en'), [v('color', 'warm red'), v('lens', '35mm')]);
    assert.equal(renderTemplate('一盏{{color}}的灯笼，{{lens}}', defaultsIn(variables, 'zh')), '一盏暖红色的灯笼，35mm');
  });

  test('an empty zh_default is used, not skipped', () => {
    assert.deepEqual(defaultsIn([{ name: 'a', default: 'the', zh_default: '' }], 'zh'), [v('a', '')]);
  });
});

describe('templates the format accepts', () => {
  test('the example from the round-10 contract', () => {
    assert.deepEqual(checkRecipe(templatedRecipe()), []);
  });

  test('a JSON-shaped template that ends in "}}"', () => {
    const recipe = templatedRecipe();
    recipe.prompt.en = '{"scene":{"subject":"a paper lantern","colour":"warm red"}}';
    recipe.prompt.zh = '{"scene":{"subject":"一盏纸灯笼","colour":"暖红色"}}';
    recipe.prompt.template = {
      en: '{"scene":{"subject":"a paper lantern","colour":"{{colour}}"}}',
      zh: '{"scene":{"subject":"一盏纸灯笼","colour":"{{colour}}"}}',
    };
    recipe.prompt.variables = [{ name: 'colour', default: 'warm red', zh_default: '暖红色' }];
    assert.deepEqual(checkRecipe(recipe), []);
  });

  test('a variable with no zh_default: both languages use the default', () => {
    const recipe = templatedRecipe();
    recipe.prompt.zh = recipe.prompt.zh.replace('35毫米', '35mm');
    recipe.prompt.variables[1] = { name: 'lens', default: '35mm' };
    assert.deepEqual(checkRecipe(recipe), []);
  });

  test('a default that itself contains a marker', () => {
    const recipe = templatedRecipe();
    recipe.prompt.variables[1] = { name: 'lens', default: '{{lens}}' };
    recipe.prompt.en = recipe.prompt.en.replace('35mm', '{{lens}}');
    recipe.prompt.zh = recipe.prompt.zh.replace('35毫米', '{{lens}}');
    assert.deepEqual(checkRecipe(recipe), []);
  });

  test('a recipe published before templates is still valid: variables, and no template', () => {
    const recipe = imageRecipe();
    assert.equal(recipe.prompt.template, undefined);
    assert.ok(recipe.prompt.variables.length > 0);
    assert.deepEqual(checkRecipe(recipe), []);
    // Its names were never held to the naming rule, and may repeat with another default.
    recipe.prompt.variables = [{ name: 'hair color', default: 'dark brown' }, { name: 'hair color', default: 'black' }, { name: '颜色', default: '红' }];
    assert.deepEqual(checkRecipe(recipe), []);
  });
});
