import type { Page } from '@playwright/test';
import type { InputsStatus, MeResponse, ModelEntry, ModelListResponse } from '../src/lib/api';
import { MODELS, mockEstimates, serveMedia } from './generation';
import { expect, makeUser, problem, type MockApi } from './mock-api';

/** Inputs switched on for the account, with the current terms accepted unless a test says otherwise. */
export function inputsUser(inputs: Partial<InputsStatus> = {}, overrides: Partial<MeResponse> = {}): MeResponse {
  return makeUser({
    inputs: {
      enabled: true, terms_version: 1, accepted: true, accepted_at: '2026-11-14T09:00:00Z', allow_people: false, ...inputs,
    },
    ...overrides,
  });
}

function withInputs(id: string, inputs: NonNullable<ModelEntry['godance']['capabilities']>['inputs'],
                    pricing: Partial<ModelEntry['godance']['pricing']> = {}): ModelEntry {
  const model = MODELS.data.find((m) => m.id === id) as ModelEntry;
  return {
    ...model,
    godance: {
      ...model.godance,
      pricing: { ...model.godance.pricing, ...pricing },
      capabilities: { ...(model.godance.capabilities ?? {}), inputs },
    },
  };
}

/**
 * The catalog of generation.ts, with what each model takes as the round 11
 * contract says it: gpt-image-1.5 takes up to four images and a mask;
 * flux-2-pro up to two and charges for each; sdxl takes none. kling-3.0
 * takes a first and a last frame; seedance-2.0 also reference images;
 * sora-2 takes none. gpt-4o-mini can look at images.
 */
export const INPUT_MODELS: ModelListResponse = {
  object: 'list',
  data: [
    ...MODELS.data.map((m) => {
      switch (m.id) {
        case 'gpt-image-1.5': return withInputs(m.id, { images: { max: 4, mask: true } });
        case 'flux-2-pro': return withInputs(m.id, { images: { max: 2 } }, { per_input_image_microcents: 60_000 });
        case 'kling-3.0': return withInputs(m.id, { first_frame: true, last_frame: true });
        case 'gpt-4o-mini': return withInputs(m.id, { images: { max: 10 } });
        default: return m;
      }
    }),
    {
      id: 'seedance-2.0', object: 'model', owned_by: 'volcengine-ark',
      godance: {
        modality: 'VIDEO', requires_async: true, pricing: { per_second_microcents: 140_000 }, markup_percent: 15,
        display_name: 'Seedance 2.0',
        capabilities: {
          sizes: ['1280x720'], default_size: '1280x720', durations: [5, 10], default_seconds: 5, max_n: 1,
          inputs: { first_frame: true, last_frame: true, reference_images: { max: 3 } },
        },
      },
    } as ModelEntry,
  ],
};

/** A 1×1 PNG, as a file a user would choose. */
export const PNG = Buffer.from(
  'iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAACklEQVR4nGMAAQAABQABDQottAAAAABJRU5ErkJggg==', 'base64');

export function fileId(n: number): string {
  return `f11e0000-0000-4000-8000-${String(n).padStart(12, '0')}`;
}

/** POST /v1/files stores each upload under the next id. Returns the ids given out, in order. */
export function serveUploads(api: MockApi): string[] {
  const stored: string[] = [];
  api.on('POST', '/v1/files', () => {
    const id = fileId(stored.length + 1);
    stored.push(id);
    return {
      status: 201,
      json: { id, object: 'file', purpose: 'generation_input', bytes: PNG.length, content_type: 'image/png',
        width: 1, height: 1, contains_person: false, created_at: 1794700000, expires_at: 1794786400 },
    };
  });
  return stored;
}

/** POST /v1/files refuses with this problem. */
export function refuseUploads(api: MockApi, status: number, code: string): void {
  api.on('POST', '/v1/files', problem(status, `refused: ${code}`, code));
}

/** The Studio with the inputs catalog, signed in as `user`, on a still clock. */
export async function openInputsStudio(page: Page, api: MockApi, path = '/create/image', user: MeResponse = inputsUser()) {
  await api.signIn(user);
  await page.clock.install();
  api.on('GET', '/v1/models', { json: INPUT_MODELS });
  mockEstimates(api);
  // An edit's input images are part of its price: flux-2-pro adds 6 cents for each.
  api.on('POST', '/v1/estimates', (call) => {
    const body = call.body as { model: string; input_images?: number; seconds?: number };
    const base: Record<string, number> = { 'flux-2-pro': 4, 'gpt-image-1.5': 5, sdxl: 6, 'kling-3.0': 50, 'seedance-2.0': 81, 'sora-2': 48 };
    const inputs = body.model === 'flux-2-pro' ? (body.input_images ?? 0) * 6 : 0;
    return { json: { model: body.model, modality: 'IMAGE', n: 1, seconds: null, estimated_cost_cents: base[body.model] + inputs, currency: 'USD' } };
  });
  await serveMedia(page);
  await page.goto(path);
  await expect(page.getByLabel('Model')).toBeEnabled();
}

/** Chooses a file for the control labelled `label`, as a person would with the file dialog. */
export async function chooseFile(page: Page, label: string | RegExp, name = 'product.png') {
  await page.getByLabel(label).setInputFiles({ name, mimeType: 'image/png', buffer: PNG });
}

/** The multipart body of an upload, as text, to look for a field or a file name in. */
export function uploadsTo(api: MockApi): number {
  return api.callsTo('POST', '/v1/files').length;
}
