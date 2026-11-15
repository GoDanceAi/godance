import { expect, test } from '@playwright/test';
import type { RecipeInputSlot } from '../src/lib/api';
import {
  autoMapping,
  defaultPlans,
  effectiveMapping,
  fileNameColumns,
  fitParams,
  matchByName,
  matchByOrder,
  matchSlot,
  nameOrder,
  requestRows,
  rowList,
  rowValues,
  tooLong,
} from '../src/lib/batch-table';
import { decodeTableFile, parseTable } from '../src/lib/csv';

// Round 12 brief 02. The table parser follows RFC 4180 and is written in the
// app, with no package behind it; these tests run it, and the rules that turn
// a table into a batch's rows, directly. No page is opened.

const vars = (...names: string[]) => names.map((name) => ({ name, default: `default ${name}` }));
const slot = (name: string): RecipeInputSlot => ({ slot: name, role: 'image', hint: '' });

test.describe('reading a table: RFC 4180', () => {
  test('comma-separated records, with or without a last line break', () => {
    const expected = { delimiter: ',', header: ['a', 'b', 'c'], rows: [['1', '2', '3'], ['4', '5', '6']], notes: [] };
    expect(parseTable('a,b,c\n1,2,3\n4,5,6')).toEqual(expected);
    expect(parseTable('a,b,c\n1,2,3\n4,5,6\n')).toEqual(expected);
  });

  test('CRLF, LF and a lone CR all end a record', () => {
    for (const eol of ['\r\n', '\n', '\r']) {
      expect(parseTable(['a,b', '1,2', '3,4'].join(eol)).rows).toEqual([['1', '2'], ['3', '4']]);
    }
  });

  test('a quoted field holds commas, and a quote written twice is one quote', () => {
    const table = parseTable('name,note\n"Mug, large","she said ""hello"""\n');
    expect(table.rows).toEqual([['Mug, large', 'she said "hello"']]);
    expect(table.notes).toEqual([]);
  });

  test('a quoted field holds line breaks, kept as one row', () => {
    const table = parseTable('name,note\n"two\nlines","three\r\nwith\rreturns"\nnext,row\n');
    expect(table.rows).toEqual([['two\nlines', 'three\nwith\nreturns'], ['next', 'row']]);
  });

  test('a quoted field may be empty, hold only a quote, or sit beside empty fields', () => {
    expect(parseTable('a,b,c\n"",x,\n""""," ",""\n').rows).toEqual([['', 'x', ''], ['"', ' ', '']]);
  });

  test('spaces are part of a field, and a delimiter at the end of a line is one more empty field', () => {
    expect(parseTable('a,b\n one , two \n').rows).toEqual([[' one ', ' two ']]);
    expect(parseTable('a,b,c\n1,2,\n').rows).toEqual([['1', '2', '']]);
    // Also with no line break after it.
    expect(parseTable('a,b,c\n1,2,').rows).toEqual([['1', '2', '']]);
  });

  test('a byte-order mark at the start is not part of the first column name', () => {
    const table = parseTable('\uFEFFcolor,surface\nblue,desk\n');
    expect(table.header).toEqual(['color', 'surface']);
    // A quoted first field after the mark is still a quoted field.
    expect(parseTable('\uFEFF"color",surface\nblue,desk\n').header).toEqual(['color', 'surface']);
  });
});

test.describe('reading a table: what spreadsheets and people produce', () => {
  test('a paste from a spreadsheet is tab-separated, and commas in it are text', () => {
    const table = parseTable('color\tsurface\nblue, deep\toak desk\n');
    expect(table.delimiter).toBe('\t');
    expect(table.rows).toEqual([['blue, deep', 'oak desk']]);
  });

  test('a spreadsheet quotes a pasted cell that holds a tab, a line break or a quote', () => {
    const table = parseTable('color\tnote\nblue\t"first line\nsecond ""quoted"" line"\n');
    expect(table.rows).toEqual([['blue', 'first line\nsecond "quoted" line']]);
  });

  test('semicolons separate when there is no tab and no comma; one column has no delimiter at all', () => {
    expect(parseTable('a;b\n1;2\n')).toMatchObject({ delimiter: ';', rows: [['1', '2']] });
    expect(parseTable('name\nblue mug, large\ngreen mug\n')).toMatchObject(
      { delimiter: null, header: ['name'], rows: [['blue mug, large'], ['green mug']] });
  });

  test('the delimiter is the first record\'s, found outside its quotes', () => {
    // The header's only comma is inside quotes: the tab separates.
    expect(parseTable('"last, first"\tcity\nLovelace, Ada\tLondon\n')).toMatchObject(
      { delimiter: '\t', header: ['last, first', 'city'], rows: [['Lovelace, Ada', 'London']] });
    // Blank lines before the header do not hide it.
    expect(parseTable('\n\na,b\n1,2\n')).toMatchObject({ delimiter: ',', header: ['a', 'b'], rows: [['1', '2']] });
  });

  test('a cell that only begins with a quote is plain text', () => {
    const table = parseTable('quote\tby\n"hello" he said\tAda\n');
    expect(table.rows).toEqual([['"hello" he said', 'Ada']]);
    expect(table.notes).toEqual([]);
  });

  test('a quote that is never closed does not swallow the rows after it, and is reported with its line', () => {
    const table = parseTable('a,b\n1,2\n"open,3\n4,5\n');
    expect(table.rows).toEqual([['1', '2'], ['"open', '3'], ['4', '5']]);
    expect(table.notes).toEqual(['A quote opened on line 3 is never closed, so that field was read as plain text.']);
  });

  test('a short row is padded, and fields past the last column are left out and reported', () => {
    const table = parseTable('a,b,c\n1\n1,2,3,4\n1,2,3,4,5\n');
    expect(table.rows).toEqual([['1', '', ''], ['1', '2', '3'], ['1', '2', '3']]);
    expect(table.notes).toEqual(['Rows 2, 3 have more fields than the header has columns. The extra fields were left out.']);
  });

  test('blank lines are skipped, and rows of empty cells are left out and counted', () => {
    const table = parseTable('a\tb\n\n1\t2\n\t\n  \t \n3\t4\n\n');
    expect(table.rows).toEqual([['1', '2'], ['3', '4']]);
    expect(table.notes).toEqual(['2 empty rows were left out.']);
  });

  test('column names are trimmed, a nameless column is numbered, and a repeated name is told apart', () => {
    expect(parseTable(' color ,,color,\n1,2,3,4\n').header).toEqual(['color', 'Column 2', 'color (2)', 'Column 4']);
  });

  test('nothing, or only column names, is a table with no rows', () => {
    expect(parseTable('')).toEqual({ delimiter: null, header: [], rows: [], notes: [] });
    expect(parseTable('\n\n')).toMatchObject({ header: [], rows: [] });
    expect(parseTable('color,surface\n')).toMatchObject({ header: ['color', 'surface'], rows: [] });
  });

  test('500 rows of quoted fields are read well inside a tenth of a second', () => {
    const line = (i: number) => `"mug ${i}, large","a ""quoted"" note\nover two lines",plain ${i},${i}`;
    const text = ['name,note,plain,n', ...Array.from({ length: 500 }, (_, i) => line(i))].join('\r\n');
    const started = performance.now();
    const table = parseTable(text);
    const took = performance.now() - started;
    expect(table.rows).toHaveLength(500);
    expect(table.rows[499]).toEqual(['mug 499, large', 'a "quoted" note\nover two lines', 'plain 499', '499']);
    expect(took).toBeLessThan(100);
  });
});

test.describe('reading a file', () => {
  const text = 'color,surface\nblå,café\n';

  test('UTF-8 is read with or without a byte-order mark', () => {
    const plain = new Uint8Array(Buffer.from(text, 'utf8'));
    const marked = new Uint8Array(Buffer.concat([Buffer.from([0xef, 0xbb, 0xbf]), Buffer.from(text, 'utf8')]));
    expect(decodeTableFile(plain)).toEqual({ text, note: null });
    expect(decodeTableFile(marked)).toEqual({ text, note: null });
    expect(parseTable(decodeTableFile(marked).text).rows).toEqual([['blå', 'café']]);
  });

  test('UTF-16 is read when its mark says so', () => {
    const little = new Uint8Array(Buffer.concat([Buffer.from([0xff, 0xfe]), Buffer.from(text, 'utf16le')]));
    const big = new Uint8Array(Buffer.concat([Buffer.from([0xfe, 0xff]), Buffer.from(text, 'utf16le').swap16()]));
    expect(parseTable(decodeTableFile(little).text).rows).toEqual([['blå', 'café']]);
    expect(parseTable(decodeTableFile(big).text).rows).toEqual([['blå', 'café']]);
  });

  test('bytes that are not UTF-8 are read as Windows-1252, with a note', () => {
    const decoded = decodeTableFile(new Uint8Array(Buffer.from(text, 'latin1')));
    expect(decoded.text).toBe(text);
    expect(decoded.note).toContain('save it as “CSV UTF-8”');
  });
});

test.describe('columns to variables', () => {
  test('a variable takes the column with its name; case, spaces, hyphens and underscores are set aside', () => {
    expect(autoMapping(['surface', 'Color'], vars('color', 'surface'))).toEqual({ color: 1, surface: 0 });
    expect(autoMapping(['Time of day', 'lantern_color'], vars('time-of-day', 'lantern color')))
      .toEqual({ 'time-of-day': 0, 'lantern color': 1 });
    expect(autoMapping(['sku', 'price'], vars('color'))).toEqual({ color: null });
  });

  test('an exact name wins over a near one, and a column goes to one variable only', () => {
    expect(autoMapping(['Color', 'color'], vars('color'))).toEqual({ color: 1 });
    expect(autoMapping(['my var'], vars('my var', 'my_var'))).toEqual({ 'my var': 0, my_var: null });
  });

  test('a choice by hand replaces the automatic one; none means the default; a column that is gone is dropped', () => {
    const header = ['color', 'where'];
    expect(effectiveMapping(header, vars('color', 'surface'), { surface: 1 })).toEqual({ color: 0, surface: 1 });
    expect(effectiveMapping(header, vars('color', 'surface'), { color: null })).toEqual({ color: null, surface: null });
    expect(effectiveMapping(header, vars('color'), { color: 7 })).toEqual({ color: null });
  });

  test('a row gives its mapped cells, trimmed; an empty cell and an unmapped variable are left to the default', () => {
    const mapping = { color: 0, surface: 1, finish: null };
    expect(rowValues([' blue ', 'oak desk'], mapping)).toEqual({ color: 'blue', surface: 'oak desk' });
    expect(rowValues(['', '   '], mapping)).toEqual({});
  });

  test('a value longer than 2,000 characters is found, by variable', () => {
    expect(tooLong({ color: 'blue', surface: 'x'.repeat(2000) })).toBeNull();
    expect(tooLong({ color: 'blue', surface: 'x'.repeat(2001) })).toBe('surface');
  });
});

test.describe('images to rows', () => {
  test('a column holds file names when most of its filled cells end in an image extension', () => {
    const table = parseTable('color,image,notes\nblue,mug-1.png,see a.png\ngreen,MUG-2.JPG,\nred,,x\nwhite,mug-4.webp,y\n');
    expect(fileNameColumns(table)).toEqual([1]);
    expect(fileNameColumns(parseTable('color,surface\nblue,desk\n'))).toEqual([]);
  });

  test('a slot starts by name where such a column exists, the one called as the slot is first; otherwise by order', () => {
    const table = parseTable('color,photo,product photo\nblue,a.png,p1.png\ngreen,b.png,p2.png\n');
    expect(defaultPlans(table, [slot('product photo'), slot('backdrop')])).toEqual({
      'product photo': { by: 'name', column: 2 },
      backdrop: { by: 'name', column: 1 },
    });
    expect(defaultPlans(parseTable('color\nblue\n'), [slot('product photo')])).toEqual({ 'product photo': { by: 'order' } });
  });

  test('by name: folders and case do not count, .jpeg is .jpg, and a cell with no extension matches the rest of the name', () => {
    const names = ['Mug-1.PNG', 'mug-2.jpeg', 'mug-3.webp'];
    expect(matchByName(['mug-1.png', 'photos/MUG-2.JPG', 'C:\\shots\\mug-3.webp', 'mug-3'], names)).toEqual([0, 1, 2, 2]);
  });

  test('by name: an empty cell, a name no file has, and a wrong extension get no image; one file serves many rows', () => {
    const names = ['mug-1.png', 'mug-2.png'];
    expect(matchByName(['mug-1.png', '', 'mug-9.png', 'mug-2.webp', 'mug-1.png'], names)).toEqual([0, null, null, null, 0]);
  });

  test('by order: images are taken in name order, numbers as numbers, and rows past the last image get none', () => {
    const names = ['photo 10.png', 'photo 2.png', 'Photo 1.png'];
    expect(nameOrder(names)).toEqual([2, 1, 0]);
    expect(matchByOrder(4, names)).toEqual([2, 1, 0, null]);
    expect(matchByOrder(2, names)).toEqual([2, 1]);
  });

  test('a slot is matched by its plan, and falls back to order when its column is gone', () => {
    const table = parseTable('color,image\nblue,b.png\ngreen,a.png\n');
    expect(matchSlot(table, 2, { by: 'name', column: 1 }, ['a.png', 'b.png'])).toEqual([1, 0]);
    expect(matchSlot(table, 2, { by: 'order' }, ['a.png', 'b.png'])).toEqual([0, 1]);
    expect(matchSlot(table, 2, { by: 'name', column: 5 }, ['a.png', 'b.png'])).toEqual([0, 1]);
  });

  test('500 files are matched to 500 rows by name well inside a tenth of a second', () => {
    const names = Array.from({ length: 500 }, (_, i) => `product-${i + 1}.png`);
    const cells = Array.from({ length: 500 }, (_, i) => `product-${500 - i}.png`);
    const started = performance.now();
    const matched = matchByName(cells, names);
    const ordered = matchByOrder(500, names);
    const took = performance.now() - started;
    expect(matched[0]).toBe(499);
    expect(matched[499]).toBe(0);
    expect(ordered.slice(0, 3)).toEqual([0, 1, 2]);
    expect(took).toBeLessThan(100);
  });
});

test.describe('the request', () => {
  test('each row carries the values its cells give, and an input for each slot', () => {
    const table = parseTable('color,surface\nblue,desk\ngreen,\n');
    const mapping = autoMapping(table.header, vars('color', 'surface'));
    expect(requestRows(table, 2, mapping)).toEqual([
      { variables: { color: 'blue', surface: 'desk' } },
      { variables: { color: 'green' } },
    ]);
    expect(requestRows(table, 2, mapping, { photo: [{ file_id: 'f-1' }, { file_id: 'f-2' }] })).toEqual([
      { variables: { color: 'blue', surface: 'desk' }, inputs: { photo: { file_id: 'f-1' } } },
      { variables: { color: 'green' }, inputs: { photo: { file_id: 'f-2' } } },
    ]);
  });

  test('with no table, each row is an image and every variable is left to its default', () => {
    const none = parseTable('');
    expect(requestRows(none, 2, {}, { photo: [{ file_id: 'f-1' }, { file_id: 'f-2' }] })).toEqual([
      { variables: {}, inputs: { photo: { file_id: 'f-1' } } },
      { variables: {}, inputs: { photo: { file_id: 'f-2' } } },
    ]);
  });

  test('on the version\'s own model its params go as they are; on another, what the model does not offer is left out', () => {
    const params = { size: '1536x1024', n: 2, quality: 'high' };
    const controls = { sizes: ['1024x1024'], defaultSize: '1024x1024', durations: null, defaultSeconds: null, maxN: null };
    expect(fitParams(params, true, controls)).toEqual({ params, changes: [] });
    expect(fitParams(params, false, controls)).toEqual({
      params: { quality: 'high' },
      changes: [{ key: 'size', from: '1536x1024', to: null }, { key: 'n', from: 2, to: 1 }],
    });
    // A model that takes the size and makes up to four keeps both; a count above its most comes down.
    const roomy = { ...controls, sizes: ['1536x1024'], maxN: 4 };
    expect(fitParams(params, false, roomy)).toEqual({ params, changes: [] });
    expect(fitParams({ n: 8 }, false, roomy)).toEqual({ params: { n: 4 }, changes: [{ key: 'n', from: 8, to: 4 }] });
    // The model list could not be read: they go as they are and the API decides.
    expect(fitParams(params, false, null)).toEqual({ params, changes: [] });
  });

  test('a video length the other model does not offer is left out', () => {
    const controls = { sizes: ['1280x720'], defaultSize: '1280x720', durations: [4, 8], defaultSeconds: 4, maxN: null };
    expect(fitParams({ size: '1280x720', seconds: 5 }, false, controls)).toEqual({
      params: { size: '1280x720' }, changes: [{ key: 'seconds', from: 5, to: null }],
    });
  });

  test('rows are listed as people count them, from 1', () => {
    expect(rowList([3])).toBe('row 4');
    expect(rowList([1, 4, 8])).toBe('rows 2, 5 and 9');
    expect(rowList([0, 1, 2, 3, 4, 5, 6, 7, 8, 9])).toBe('rows 1, 2, 3, 4, 5, 6, 7, 8 and 2 more');
  });
});
