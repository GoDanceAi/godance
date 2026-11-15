/**
 * From a table to the rows of a batch: which column each variable reads,
 * which image each row gets, and the request that says so. Nothing here
 * touches the network or the page, so the tests run it directly.
 *
 * Rows are counted from 0 here and in the API. People see them from 1
 * ({@link rowNumber}).
 */
import type { BatchRequestRow, InputRef, RecipeInputSlot, RecipeVariable } from './api';
import type { ParsedTable } from './csv';
import type { Controls } from './media-models';
import { VALUE_MAX_LENGTH } from './template';

/** The most rows a batch takes when the API has not said: its shipped default. */
export const DEFAULT_MAX_ROWS = 500;
/** The most rows at a time when the API has not said. */
export const DEFAULT_MAX_CONCURRENCY = 4;
/** The rows at a time a new batch starts with: the API's own default. */
export const DEFAULT_CONCURRENCY = 2;

/** A row's number as people count: from 1. */
export function rowNumber(index: number): number {
  return index + 1;
}

/** "rows 2, 5 and 9", "row 4", "rows 1, 2, 3, 4, 5 and 12 more". */
export function rowList(indexes: number[], most = 8): string {
  const numbers = indexes.map(rowNumber);
  if (numbers.length === 1) return `row ${numbers[0]}`;
  if (numbers.length > most) return `rows ${numbers.slice(0, most).join(', ')} and ${numbers.length - most} more`;
  return `rows ${numbers.slice(0, -1).join(', ')} and ${numbers.at(-1)}`;
}

// ===== Columns to variables =====

/** A name as it is compared: case, and the difference between a space, a hyphen and an underscore, do not count. */
function comparable(name: string): string {
  return name.trim().toLowerCase().replace(/[\s_-]+/g, ' ');
}

/** For each variable, the column it reads (an index into the header), or null: it keeps its default. */
export type Mapping = Record<string, number | null>;

/**
 * Each variable takes the column that has its name: exactly, or failing
 * that with case, spaces, hyphens and underscores set aside. A column goes
 * to one variable only.
 */
export function autoMapping(header: readonly string[], variables: readonly RecipeVariable[]): Mapping {
  const mapping: Mapping = {};
  const taken = new Set<number>();
  for (const variable of variables) {
    const at = header.findIndex((name, i) => !taken.has(i) && name === variable.name);
    if (at !== -1) {
      mapping[variable.name] = at;
      taken.add(at);
    }
  }
  for (const variable of variables) {
    if (variable.name in mapping) continue;
    const at = header.findIndex((name, i) => !taken.has(i) && comparable(name) === comparable(variable.name));
    mapping[variable.name] = at === -1 ? null : at;
    if (at !== -1) taken.add(at);
  }
  return mapping;
}

/**
 * The mapping in force: the automatic one, with each choice made by hand in
 * its place. A choice of a column the table no longer has is dropped.
 */
export function effectiveMapping(
  header: readonly string[],
  variables: readonly RecipeVariable[],
  chosen: Readonly<Record<string, number | null>> = {},
): Mapping {
  const mapping = autoMapping(header, variables);
  for (const variable of variables) {
    if (!Object.hasOwn(chosen, variable.name)) continue;
    const column = chosen[variable.name];
    mapping[variable.name] = column !== null && column >= 0 && column < header.length ? column : null;
  }
  return mapping;
}

/**
 * The values a row gives the variables. A variable with no column, or with
 * an empty cell, is left out, so it gets its default.
 */
export function rowValues(row: readonly string[], mapping: Mapping): Record<string, string> {
  const values: Record<string, string> = {};
  for (const [name, column] of Object.entries(mapping)) {
    if (column === null) continue;
    const value = (row[column] ?? '').trim();
    if (value !== '') values[name] = value;
  }
  return values;
}

// ===== Images to rows =====

const IMAGE_NAME = /\.(png|jpe?g|webp)$/i;

/** A file name without its folders. */
function baseName(path: string): string {
  return path.trim().split(/[\\/]/).pop() ?? '';
}

/** What two spellings of one file name share: no folders, lower case, and .jpeg for .jpg. */
function nameKey(name: string): string {
  return baseName(name).toLowerCase().replace(/\.jpeg$/, '.jpg');
}

/** The name without its extension. */
function stemKey(name: string): string {
  return nameKey(name).replace(/\.[a-z0-9]+$/, '');
}

/**
 * The columns that hold image file names: those where most of the cells
 * that have anything in them end in .png, .jpg, .jpeg or .webp.
 */
export function fileNameColumns(table: ParsedTable): number[] {
  return table.header.flatMap((_, column) => {
    let filled = 0;
    let named = 0;
    for (const row of table.rows) {
      const cell = row[column].trim();
      if (cell === '') continue;
      filled += 1;
      if (IMAGE_NAME.test(cell)) named += 1;
    }
    return filled > 0 && named * 2 > filled ? [column] : [];
  });
}

/** How a slot's images are given to rows: by the file names in a column, or in name order. */
export type SlotPlan = { by: 'name'; column: number } | { by: 'order' };

/**
 * The plan each slot starts with: by name where a column holds file names
 * (the column called as the slot is, else the next such column not yet
 * taken), otherwise by order.
 */
export function defaultPlans(table: ParsedTable, slots: readonly RecipeInputSlot[]): Record<string, SlotPlan> {
  const columns = fileNameColumns(table);
  const plans: Record<string, SlotPlan> = {};
  const taken = new Set<number>();
  for (const slot of slots) {
    const column = columns.find((c) => !taken.has(c) && comparable(table.header[c]) === comparable(slot.slot));
    if (column !== undefined) {
      plans[slot.slot] = { by: 'name', column };
      taken.add(column);
    }
  }
  for (const slot of slots) {
    if (plans[slot.slot]) continue;
    const column = columns.find((c) => !taken.has(c));
    if (column === undefined) {
      plans[slot.slot] = { by: 'order' };
    } else {
      plans[slot.slot] = { by: 'name', column };
      taken.add(column);
    }
  }
  return plans;
}

/** File names in the order people expect: "photo 2" before "photo 10". Returns indexes into `names`. */
export function nameOrder(names: readonly string[]): number[] {
  const collator = new Intl.Collator('en', { numeric: true, sensitivity: 'base' });
  return names.map((_, i) => i).sort((a, b) => collator.compare(names[a], names[b]) || a - b);
}

/**
 * By name: for each cell, the file it names (an index into `names`), or
 * null when it names none or is empty. Folders and case do not count; a
 * cell with no extension matches a file by the rest of its name. One file
 * can serve many rows.
 */
export function matchByName(cells: readonly string[], names: readonly string[]): (number | null)[] {
  const byName = new Map<string, number>();
  const byStem = new Map<string, number>();
  for (const i of nameOrder(names)) {
    if (!byName.has(nameKey(names[i]))) byName.set(nameKey(names[i]), i);
    if (!byStem.has(stemKey(names[i]))) byStem.set(stemKey(names[i]), i);
  }
  return cells.map((cell) => {
    if (cell.trim() === '') return null;
    const exact = byName.get(nameKey(cell));
    if (exact !== undefined) return exact;
    // "mug-01" names mug-01.png. A cell that gives an extension must match it.
    return IMAGE_NAME.test(cell.trim()) ? null : byStem.get(nameKey(cell)) ?? null;
  });
}

/** By order: row n gets the nth file in name order, or null once the files run out. */
export function matchByOrder(rowCount: number, names: readonly string[]): (number | null)[] {
  const order = nameOrder(names);
  return Array.from({ length: rowCount }, (_, row) => order[row] ?? null);
}

/** For each row, the file a slot gets under its plan (an index into `names`), or null. */
export function matchSlot(table: ParsedTable, rowCount: number, plan: SlotPlan, names: readonly string[]): (number | null)[] {
  if (plan.by === 'name' && plan.column < table.header.length) {
    return matchByName(table.rows.map((row) => row[plan.column]), names);
  }
  return matchByOrder(rowCount, names);
}

// ===== The request =====

/** The first value of a row that is longer than a variable's value may be, or null. */
export function tooLong(values: Readonly<Record<string, string>>): string | null {
  return Object.keys(values).find((name) => values[name].length > VALUE_MAX_LENGTH) ?? null;
}

/**
 * The rows of a request. `inputs` holds, for each slot, the reference each
 * row was given; a row is sent only with every slot filled, so the caller
 * builds the request once nothing is missing.
 */
export function requestRows(
  table: ParsedTable,
  rowCount: number,
  mapping: Mapping,
  inputs: Readonly<Record<string, readonly (InputRef | null)[]>> = {},
): BatchRequestRow[] {
  const slots = Object.keys(inputs);
  return Array.from({ length: rowCount }, (_, i) => {
    const row: BatchRequestRow = { variables: table.rows[i] ? rowValues(table.rows[i], mapping) : {} };
    if (slots.length > 0) {
      row.inputs = {};
      for (const slot of slots) {
        const ref = inputs[slot][i];
        if (ref) row.inputs[slot] = ref;
      }
    }
    return row;
  });
}

/** A setting of the version that another model does not take as it is: left out (`to` null) or lowered. */
export interface ParamChange {
  key: 'size' | 'seconds' | 'n';
  from: string | number;
  to: number | null;
}

/**
 * The params a batch sends: the version's own on the version's model. On
 * another model a size or a duration the model does not offer is left out
 * and a count above what it makes per request comes down, because params
 * that are sent replace the version's as a whole; `changes` says which.
 * With `controls` null (the model list could not be read) they go as they
 * are and the API decides.
 */
export function fitParams(
  params: Readonly<Record<string, unknown>>,
  sameModel: boolean,
  controls: Controls | null,
): { params: Record<string, unknown>; changes: ParamChange[] } {
  if (sameModel || controls === null) return { params: { ...params }, changes: [] };
  const out: Record<string, unknown> = {};
  const changes: ParamChange[] = [];
  for (const [key, value] of Object.entries(params)) {
    if (key === 'size' && typeof value === 'string') {
      if (controls.sizes?.includes(value)) out.size = value;
      else changes.push({ key, from: value, to: null });
    } else if (key === 'seconds' && typeof value === 'number') {
      if (controls.durations?.includes(value)) out.seconds = value;
      else changes.push({ key, from: value, to: null });
    } else if (key === 'n' && typeof value === 'number') {
      const most = controls.maxN ?? 1;
      // A model that makes one at a time gets no count, as from the Studio.
      if (controls.maxN) out.n = Math.min(value, most);
      if (value > most) changes.push({ key, from: value, to: most });
    } else {
      out[key] = value;
    }
  }
  return { params: out, changes };
}
