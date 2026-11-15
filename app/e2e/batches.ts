import type { Batch, BatchEstimate, BatchRequest, BatchRow } from '../src/lib/api';
import { jobId, mediaUrl } from './generation';
import { problem, type ApiCall, type MockApi, type Reply } from './mock-api';
import { recipeId, versionId } from './user-recipes';

// Recipe batches as /v1/recipe-batches serves them: the round 12 contract
// (00-coordination.md) with the fields brief 01 added to it. Fixtures, and
// the routes over state a test can move on and read back.

/** The nth batch id. Ids are UUIDs, as the API's are. */
export function batchId(n: number): string {
  return `ba7c0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
}

/** A batch of the first recipe, 120 rows, running two at a time: 40 done, 1 failed, 2 running. */
export function makeBatch(overrides: Partial<Batch> = {}): Batch {
  return {
    id: batchId(1),
    recipe_id: recipeId(1),
    version_id: versionId(1, 3),
    model: 'gpt-image-1.5',
    status: 'RUNNING',
    pause_reason: null,
    rows_total: 120,
    rows_done: 40,
    rows_failed: 1,
    estimated_total_cents: 720,
    spent_cents: 238,
    created_at: '2026-11-14T09:00:00Z',
    completed_at: null,
    recipe_title: 'Mug on a kitchen shelf',
    max_concurrency: 2,
    pause_detail: null,
    rows_running: 2,
    rows_skipped: 0,
    started_at: '2026-11-14T09:00:03Z',
    ...overrides,
  };
}

const COLORS = ['blue', 'green', 'yellow', 'white', 'black'];

/** A finished row: its job, what it cost, and one output. */
export function doneRow(index: number, overrides: Partial<BatchRow> = {}): BatchRow {
  return {
    index,
    status: 'DONE',
    variables: { color: COLORS[index % COLORS.length], surface: `shelf ${index + 1}` },
    job_id: jobId(1000 + index),
    cost_cents: 6,
    error: null,
    outputs: [{ index: 0, url: mediaUrl(`row-${index + 1}.png`), content_type: 'image/png' }],
    ...overrides,
  };
}

/** A row that has not started. */
export function pendingRow(index: number, overrides: Partial<BatchRow> = {}): BatchRow {
  return {
    index,
    status: 'PENDING',
    variables: { color: COLORS[index % COLORS.length], surface: `shelf ${index + 1}` },
    job_id: null,
    cost_cents: null,
    error: null,
    outputs: [],
    ...overrides,
  };
}

/**
 * `total` rows as a batch part-way through has them: the first `done`
 * finished, then `running` under way, the rest waiting. `failed` names the
 * finished rows that failed.
 */
export function makeRows(total: number, done: number, running = 0, failed: number[] = []): BatchRow[] {
  return Array.from({ length: total }, (_, index) => {
    if (failed.includes(index)) {
      return doneRow(index, { status: 'FAILED', cost_cents: 0, outputs: [], error: 'The provider refused this prompt.' });
    }
    if (index < done) return doneRow(index);
    if (index < done + running) return pendingRow(index, { status: 'RUNNING', job_id: jobId(1000 + index) });
    return pendingRow(index);
  });
}

const ONE = /^\/v1\/recipe-batches\/([0-9a-f-]{36})$/;
const ROWS = /^\/v1\/recipe-batches\/([0-9a-f-]{36})\/rows$/;
const CANCEL = /^\/v1\/recipe-batches\/([0-9a-f-]{36})\/cancel$/;
const RESUME = /^\/v1\/recipe-batches\/([0-9a-f-]{36})\/resume$/;
const CSV = /^\/v1\/recipe-batches\/([0-9a-f-]{36})\/results\.csv$/;
const ZIP = /^\/v1\/recipe-batches\/([0-9a-f-]{36})\/results\.zip$/;

/** What results.csv holds in these tests. */
export const RESULTS_CSV = 'row,status,color,surface,job_id,cost_cents,output_1\r\n'
  + `0,DONE,blue,shelf 1,${jobId(1000)},6,${mediaUrl('row-1.png')}\r\n`;

/** What results.zip holds: the bytes a zip starts with, and a little more. */
export const RESULTS_ZIP = Buffer.from([0x50, 0x4b, 0x03, 0x04, 0x14, 0x00, 0x00, 0x00, 0x08, 0x00, 0xde, 0xad, 0xbe, 0xef]);

export interface BatchState {
  batch: Batch;
  rows: BatchRow[];
}

/**
 * One batch, as the API serves it: the batch, its rows a page at a time
 * (`limit`, and `after` as the index of the last row already held), cancel,
 * resume and the two result files. Everything answers from `state` as it
 * stands, so a test moves the batch on by changing it, and an extra poll
 * changes nothing. Another id answers 404.
 */
export function serveBatch(api: MockApi, state: BatchState): MockApi {
  const mine = (call: ApiCall, pattern: RegExp) => pattern.exec(call.path)?.[1] === state.batch.id;
  const notFound = problem(404, 'Batch not found.', 'batch_not_found');
  const guard = (pattern: RegExp, answer: (call: ApiCall) => Reply) =>
    (call: ApiCall) => (mine(call, pattern) ? answer(call) : notFound);

  return api
    .on('GET', ONE, guard(ONE, () => ({ json: state.batch })))
    .on('GET', ROWS, guard(ROWS, (call) => {
      const after = call.query.get('after');
      const limit = Number(call.query.get('limit') ?? 50);
      const from = after === null ? 0 : state.rows.findIndex((row) => row.index > Number(after));
      return { json: from === -1 ? [] : state.rows.slice(from, from + limit) };
    }))
    .on('POST', CANCEL, guard(CANCEL, () => {
      // Rows that are running finish; the ones that had not started are skipped.
      let skipped = 0;
      state.rows = state.rows.map((row) => {
        if (row.status !== 'PENDING') return row;
        skipped += 1;
        return { ...row, status: 'SKIPPED' };
      });
      state.batch = {
        ...state.batch, status: 'CANCELLED', pause_reason: null, pause_detail: null,
        rows_skipped: (state.batch.rows_skipped ?? 0) + skipped,
        completed_at: (state.batch.rows_running ?? 0) > 0 ? null : '2026-11-14T09:20:00Z',
      };
      return { json: state.batch };
    }))
    .on('POST', RESUME, guard(RESUME, () => {
      if (state.batch.status !== 'PAUSED') {
        return problem(409, 'Only a paused batch can be resumed.', 'batch_not_resumable');
      }
      state.batch = { ...state.batch, status: 'RUNNING', pause_reason: null, pause_detail: null };
      return { json: state.batch };
    }))
    .on('GET', CSV, guard(CSV, () => ({ body: RESULTS_CSV, contentType: 'text/csv; charset=utf-8' })))
    .on('GET', ZIP, guard(ZIP, () => ({ body: RESULTS_ZIP, contentType: 'application/zip' })));
}

/** results.zip refuses as the API does when the outputs are more than one zip may hold. */
export function refuseZip(api: MockApi): MockApi {
  return api.on('GET', ZIP, problem(413,
    'The outputs of this batch come to 612 MB, more than the 500 MB one zip may hold.', 'results_too_large'));
}

/**
 * GET /v1/recipe-batches over a list, newest first, as the API pages it:
 * limit, before (a batch id; only older batches) and recipe_id.
 */
export function serveBatchList(api: MockApi, batches: Batch[]): MockApi {
  return api.on('GET', '/v1/recipe-batches', (call) => {
    let list = batches;
    const recipe = call.query.get('recipe_id');
    if (recipe) list = list.filter((b) => b.recipe_id === recipe);
    const before = call.query.get('before');
    if (before) {
      const at = list.findIndex((b) => b.id === before);
      list = at === -1 ? [] : list.slice(at + 1);
    }
    const limit = call.query.get('limit');
    return { json: limit ? list.slice(0, Number(limit)) : list };
  });
}

export interface EstimateOptions {
  /** What one row costs, in cents. */
  perRow?: number;
  balance?: number;
  maxRows?: number;
  maxConcurrency?: number;
}

/** The estimate the API would give this request under `options`. */
export function estimateOf(body: BatchRequest, options: EstimateOptions = {}): BatchEstimate {
  const { perRow = 10, balance = 12_34, maxRows = 500, maxConcurrency = 4 } = options;
  const total = body.rows.length * perRow;
  return {
    rows: body.rows.length,
    total_cents: total,
    balance_cents: balance,
    enough: total <= balance,
    limits: { max_rows: maxRows, max_concurrency: maxConcurrency },
  };
}

/**
 * POST /v1/recipe-batches/estimate, priced from the request, and refusing as
 * the API does: more rows than a batch takes, or more rows at a time than
 * it allows. Nothing is started by it.
 */
export function mockBatchEstimate(api: MockApi, options: EstimateOptions = {}): MockApi {
  const { maxRows = 500, maxConcurrency = 4 } = options;
  return api.on('POST', '/v1/recipe-batches/estimate', (call) => {
    const body = call.body as BatchRequest;
    if (body.rows.length > maxRows) {
      return problem(400, `A batch takes at most ${maxRows} rows; this one has ${body.rows.length}.`, 'batch_too_large');
    }
    if (body.max_concurrency < 1 || body.max_concurrency > maxConcurrency) {
      return problem(400, `max_concurrency must be between 1 and ${maxConcurrency}.`, 'invalid_batch');
    }
    return { json: estimateOf(body, options) };
  });
}

/**
 * POST /v1/recipe-batches starts the batch `started` describes: 201 with
 * the batch, queued, with as many rows as the request has. The batch and
 * its rows are then served from the state returned.
 */
export function mockBatchCreate(api: MockApi, started: Partial<Batch> = {}): BatchState {
  const state: BatchState = { batch: makeBatch(started), rows: [] };
  api.on('POST', '/v1/recipe-batches', (call) => {
    const body = call.body as BatchRequest;
    state.batch = makeBatch({
      status: 'QUEUED', rows_total: body.rows.length, rows_done: 0, rows_failed: 0, rows_running: 0,
      spent_cents: 0, estimated_total_cents: body.rows.length * 10, model: body.model,
      max_concurrency: body.max_concurrency, started_at: null, ...started,
    });
    state.rows = body.rows.map((row, index) => pendingRow(index, { variables: row.variables }));
    return { status: 201, json: state.batch };
  });
  serveBatch(api, state);
  return state;
}

/** The bodies sent to the estimate route, in order. */
export function estimatesAsked(api: MockApi): BatchRequest[] {
  return api.callsTo('POST', '/v1/recipe-batches/estimate').map((call) => call.body as BatchRequest);
}
