import type { Page } from '@playwright/test';
import type {
  GodanceModelExtension,
  JobView,
  ModelEntry,
  ModelListResponse,
} from '../src/lib/api';
import { problem, type MockApi, type Reply } from './mock-api';

// Fixtures for the Studio and History: a catalog that publishes capabilities,
// jobs as GET /v1/jobs/{id} returns them, and the storage host the files are
// signed for. Shapes follow the round 7 contract (00-coordination.md), and
// round 9's for saving.

/** Where generated files live: a storage host apart from the API, as in production. */
export const MEDIA_ORIGIN = 'http://media.godance.localhost';

export function mediaUrl(name: string): string {
  return `${MEDIA_ORIGIN}/outputs/${name}?X-Amz-Signature=e2e`;
}

const PICTURE = `<svg xmlns="http://www.w3.org/2000/svg" width="64" height="64" viewBox="0 0 64 64">
  <rect width="64" height="64" fill="#7c3aed"/><circle cx="32" cy="32" r="14" fill="#06b6d4"/></svg>`;

/** Answers the storage host. Without this its requests are aborted, which no test fails on. */
export async function serveMedia(page: Page, files: Record<string, { contentType: string; body: string | Buffer }> = {}): Promise<void> {
  await page.route(`${MEDIA_ORIGIN}/**`, (route) => {
    const name = new URL(route.request().url()).pathname.split('/').pop() ?? '';
    const file = files[name];
    return route.fulfill(file ?? { contentType: 'image/svg+xml', body: PICTURE });
  });
}

function model(id: string, ownedBy: string, godance: GodanceModelExtension): ModelEntry {
  return { id, object: 'model', owned_by: ownedBy, godance };
}

function image(id: string, ownedBy: string, perImage: number, extra: Partial<GodanceModelExtension> = {}): ModelEntry {
  return model(id, ownedBy, {
    modality: 'IMAGE', requires_async: true, pricing: { per_image_microcents: perImage }, markup_percent: 12, ...extra,
  });
}

function video(id: string, ownedBy: string, perSecond: number, extra: Partial<GodanceModelExtension> = {}): ModelEntry {
  return model(id, ownedBy, {
    modality: 'VIDEO', requires_async: true, pricing: { per_second_microcents: perSecond }, markup_percent: 15, ...extra,
  });
}

/**
 * The cheapest image model is flux-2-pro ($0.0336 with the fee), which makes
 * one image at a time; gpt-image-1.5 makes up to four; sdxl publishes no
 * capabilities. The cheapest video model is kling-3.0.
 */
export const MODELS: ModelListResponse = {
  object: 'list',
  data: [
    image('gpt-image-1.5', 'openai', 40_000, {
      display_name: 'GPT Image 1.5',
      capabilities: { sizes: ['1024x1024', '1536x1024', '1024x1536'], default_size: '1024x1024', max_n: 4 },
    }),
    image('flux-2-pro', 'bfl', 30_000, {
      display_name: 'FLUX.2 [pro]',
      description: 'Photoreal detail and accurate text, one image per request.',
      capabilities: { sizes: ['1024x1024', '1440x1024'], default_size: '1024x1024', max_n: 1 },
    }),
    image('sdxl', 'stability', 50_000),
    video('kling-3.0', 'kling', 84_000, {
      display_name: 'Kling 3.0',
      capabilities: {
        sizes: ['1280x720', '1920x1080'], default_size: '1280x720', durations: [5, 10], default_seconds: 5, max_n: 1,
      },
    }),
    video('sora-2', 'openai', 100_000, {
      display_name: 'Sora 2',
      capabilities: { sizes: ['1280x720'], default_size: '1280x720', durations: [4, 8, 12], default_seconds: 4, max_n: 1 },
    }),
    model('gpt-4o-mini', 'openai', {
      modality: 'TEXT', requires_async: false, pricing: { input_microcents_per_1k: 15, output_microcents_per_1k: 60 },
    }),
    { id: 'godance-auto', object: 'model', owned_by: 'godance', godance: { virtual: true, modality: 'IMAGE' } },
  ],
};

// What each request costs in these tests: per image, or per second of video.
const CENTS: Record<string, number> = { 'flux-2-pro': 4, 'gpt-image-1.5': 5, sdxl: 6, 'kling-3.0': 10, 'sora-2': 12 };
const DEFAULT_SECONDS: Record<string, number> = { 'kling-3.0': 5, 'sora-2': 4 };

/** POST /v1/estimates, priced from the request. */
export function mockEstimates(api: MockApi): MockApi {
  return api.on('POST', '/v1/estimates', (call) => {
    const body = call.body as { model: string; n?: number; seconds?: number };
    const video = body.model in DEFAULT_SECONDS;
    const units = (body.n ?? 1) * (video ? body.seconds ?? DEFAULT_SECONDS[body.model] : 1);
    return {
      json: {
        model: body.model, modality: video ? 'VIDEO' : 'IMAGE', n: body.n ?? 1,
        seconds: video ? body.seconds ?? DEFAULT_SECONDS[body.model] : null,
        estimated_cost_cents: CENTS[body.model] * units, currency: 'USD',
      },
    };
  });
}

/** The nth job id. Ids are UUIDs, as the API's are. */
export function jobId(n: number): string {
  return `7a0b0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
}

/** A finished one-image job, as GET /v1/jobs/{id} returns it. */
export function makeJob(overrides: Partial<JobView> = {}): JobView {
  return {
    id: jobId(1),
    status: 'DONE',
    modality: 'IMAGE',
    provider: 'BFL',
    model: 'flux-2-pro',
    estimatedCostCents: 4,
    actualCostCents: 4,
    outputUrl: mediaUrl('out-1.png'),
    errorMessage: null,
    createdAt: '2026-11-13T12:00:00Z',
    startedAt: '2026-11-13T12:00:02Z',
    completedAt: '2026-11-13T12:00:20Z',
    prompt: 'A lighthouse on a sea cliff at dusk',
    params: { size: '1024x1024' },
    outputs: [{ index: 0, url: mediaUrl('out-1.png'), contentType: 'image/png', sizeBytes: 812_345, revisedPrompt: null }],
    purged: false,
    mediaExpiresAt: '2027-02-11T12:00:00Z',
    saved: false,
    ...overrides,
  };
}

/** The same job while it waits or runs: no outputs, no charge settled. */
export function inProgress(job: JobView, status: 'QUEUED' | 'RUNNING' = 'RUNNING'): JobView {
  return {
    ...job, status, outputs: [], outputUrl: null, actualCostCents: null, completedAt: null,
    startedAt: status === 'QUEUED' ? null : job.startedAt,
  };
}

/** n outputs, image or video. */
export function outputsOf(n: number, kind: 'image' | 'video' = 'image'): JobView['outputs'] {
  return Array.from({ length: n }, (_, i) => ({
    index: i,
    url: mediaUrl(`out-${i + 1}.${kind === 'video' ? 'mp4' : 'png'}`),
    contentType: kind === 'video' ? 'video/mp4' : 'image/png',
    sizeBytes: 500_000 + i,
    revisedPrompt: null,
  }));
}

/**
 * GET /v1/jobs/{id} answers the job as it stands; the test moves it on with
 * `set`. Driven by state, not by counting calls, so an extra poll changes nothing.
 */
export function serveJob(api: MockApi, job: JobView): { set: (next: JobView) => void } {
  let current = job;
  api.on('GET', `/v1/jobs/${job.id}`, () => ({ json: current }));
  return { set: (next) => { current = next; } };
}

/**
 * GET /v1/jobs over a fixed list, newest first, as the API pages it: limit,
 * before (a job id; only older jobs), modality, saved and recipe (a recipe
 * id; only the jobs that ran one of its versions).
 */
export function serveJobList(api: MockApi, jobs: JobView[]): MockApi {
  return api.on('GET', '/v1/jobs', (call) => {
    let list = jobs;
    const modality = call.query.get('modality');
    if (modality) list = list.filter((j) => j.modality === modality);
    if (call.query.get('saved') === 'true') list = list.filter((j) => j.saved);
    const recipe = call.query.get('recipe');
    if (recipe) list = list.filter((j) => j.recipe?.id === recipe);
    const before = call.query.get('before');
    if (before) {
      const at = list.findIndex((j) => j.id === before);
      list = at === -1 ? [] : list.slice(at + 1);
    }
    const limit = call.query.get('limit');
    return { json: limit ? list.slice(0, Number(limit)) : list };
  });
}

const SAVED_ROUTE = /^\/v1\/jobs\/([0-9a-f-]{36})\/saved$/;
const RETENTION_MS = 90 * 24 * 60 * 60 * 1000;

/**
 * Saving, as the API does it, over `jobs`: PUT and DELETE /v1/jobs/{id}/saved
 * and the count at GET /v1/jobs/saved. A job is changed in place, so the
 * list and job routes serving the same objects answer with its new state.
 */
export function serveSaves(api: MockApi, jobs: JobView[], limit = 500): MockApi {
  const find = (path: string) => jobs.find((j) => j.id === SAVED_ROUTE.exec(path)?.[1]);
  const count = () => jobs.filter((j) => j.saved).length;
  return api
    .on('GET', '/v1/jobs/saved', () => ({ json: { count: count(), limit } }))
    .on('PUT', SAVED_ROUTE, (call) => {
      const job = find(call.path);
      if (!job) return problem(404, 'job not found');
      if (job.saved) return { status: 204 };
      if (job.status !== 'DONE' || job.purged) {
        return problem(409, 'Only a finished generation can be saved.', 'job_not_saveable');
      }
      if (count() >= limit) {
        return problem(409,
          `You have saved ${limit} generations, the most an account can keep. Un-save one to save another.`,
          'saved_limit_reached');
      }
      job.saved = true;
      job.mediaExpiresAt = null;
      return { status: 204 };
    })
    .on('DELETE', SAVED_ROUTE, (call) => {
      const job = find(call.path);
      if (!job) return problem(404, 'job not found');
      if (job.saved) {
        job.saved = false;
        job.mediaExpiresAt = new Date(Date.parse(job.createdAt) + RETENTION_MS).toISOString();
      }
      return { status: 204 };
    });
}

/** The 202 a generation request gets. */
export function accepted(id: string): Reply {
  return { status: 202, json: { job_id: id, status: 'QUEUED', poll_url: `/v1/jobs/${id}` } };
}
