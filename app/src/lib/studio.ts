import { useEffect, useState } from 'react';
import {
  apiFetch,
  type AsyncJobAcknowledged,
  type Estimate,
  type JobView,
  type MediaModality,
} from './api';
import { mediaModality } from './jobs';
import type { Controls } from './media-models';
import type { GalleryRecipe } from './recipe';

// The generation requests the Studio sends: the same calls an API key makes.

/** The longest prompt the Studio takes. */
export const PROMPT_MAX_LENGTH = 4000;

/** The Studio form. size and seconds are null when the model offers no such control. */
export interface StudioValues {
  model: string;
  prompt: string;
  size: string | null;
  n: number;
  seconds: number | null;
}

/** A body for POST /v1/images/generations or /v1/videos/generations. */
export interface GenerationBody {
  model: string;
  prompt: string;
  size?: string;
  n?: number;
  seconds?: number;
  style?: string;
  quality?: string;
  gallery_recipe?: string;   // the gallery recipe this is a run of (galleryRecipeOf)
  async?: true;
}

export function generationPath(modality: MediaModality): string {
  return modality === 'VIDEO' ? '/v1/videos/generations' : '/v1/images/generations';
}

// Images answer at once unless asked not to; video is always a job.
function withAsync(modality: MediaModality, body: GenerationBody): GenerationBody {
  return modality === 'IMAGE' ? { ...body, async: true } : body;
}

/**
 * The request for the form: the model, the prompt, and only what the model's
 * controls offer. `galleryRecipe` is the recipe it is still a run of, if any.
 */
export function generationBody(
  modality: MediaModality,
  values: StudioValues,
  controls: Controls,
  galleryRecipe: string | null = null,
): GenerationBody {
  const body: GenerationBody = { model: values.model, prompt: values.prompt };
  if (controls.sizes && values.size) body.size = values.size;
  if (controls.maxN) body.n = values.n;
  if (controls.durations && values.seconds) body.seconds = values.seconds;
  if (galleryRecipe) body.gallery_recipe = galleryRecipe;
  return withAsync(modality, body);
}

/**
 * The slug to send as `gallery_recipe`: the recipe the form was loaded from,
 * for as long as the form is still that recipe. It stops being one when the
 * prompt is no longer the recipe's text in either language, or the model is
 * not the one the recipe was run on. Size, count and duration may change.
 */
export function galleryRecipeOf(recipe: GalleryRecipe | null, values: StudioValues): string | null {
  if (!recipe || values.model !== recipe.run.model) return null;
  const unedited = (['en', 'zh'] as const).some((lang) => {
    const text = recipe.prompt[lang];
    return text !== null && values.prompt === text.slice(0, PROMPT_MAX_LENGTH);
  });
  return unedited ? recipe.slug : null;
}

function positiveInt(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) > 0;
}

function text(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}

/**
 * A job's own request, to send again, with its count fitted to what the model
 * makes per request now (`controls`): above the model's max_n it comes down to
 * it, and a model that makes one at a time gets no count, as from the Studio.
 * With `controls` null (the model list could not be read) the count goes as it
 * was and the API decides. Null once the job's prompt has been deleted.
 */
export function rerunBody(job: JobView, controls: Controls | null): GenerationBody | null {
  const modality = mediaModality(job);
  if (!modality || !job.prompt) return null;
  const p = job.params ?? {};
  const body: GenerationBody = { model: job.model, prompt: job.prompt };
  if (text(p.size)) body.size = p.size;
  if (positiveInt(p.n)) {
    if (!controls) body.n = p.n;
    else if (controls.maxN) body.n = Math.min(p.n, controls.maxN);
  }
  if (modality === 'VIDEO' && positiveInt(p.seconds)) body.seconds = p.seconds;
  if (modality === 'IMAGE' && text(p.style)) body.style = p.style;
  if (modality === 'IMAGE' && text(p.quality)) body.quality = p.quality;
  return withAsync(modality, body);
}

/** Submits a generation as a job. */
export async function startGeneration(modality: MediaModality, body: GenerationBody): Promise<AsyncJobAcknowledged> {
  const ack = await apiFetch<AsyncJobAcknowledged>(generationPath(modality), { method: 'POST', json: body });
  if (typeof ack?.job_id !== 'string') throw new Error('The generation could not be started.');
  return ack;
}

/** What POST /v1/estimates prices: the model and the parameters that change the price. */
function estimateRequest(body: GenerationBody): Record<string, unknown> {
  const req: Record<string, unknown> = { model: body.model };
  if (body.n !== undefined) req.n = body.n;
  if (body.size !== undefined) req.size = body.size;
  if (body.seconds !== undefined) req.seconds = body.seconds;
  return req;
}

const ESTIMATE_DELAY_MS = 400;

/**
 * The exact price of a request in cents, from POST /v1/estimates once its
 * priced parameters have been still for a moment. Null while asking or when
 * the API could not say; the request can be sent either way.
 */
export function useEstimate(body: GenerationBody | null): number | null {
  const key = body ? JSON.stringify(estimateRequest(body)) : null;
  const [answer, setAnswer] = useState<{ key: string; cents: number } | null>(null);

  useEffect(() => {
    if (!key) return;
    const controller = new AbortController();
    const timer = setTimeout(() => {
      apiFetch<Estimate>('/v1/estimates', { method: 'POST', json: JSON.parse(key), signal: controller.signal })
        .then((estimate) => {
          if (Number.isInteger(estimate?.estimated_cost_cents)) {
            setAnswer({ key, cents: estimate.estimated_cost_cents });
          }
        })
        .catch(() => { /* no price shown */ });
    }, ESTIMATE_DELAY_MS);
    return () => {
      clearTimeout(timer);
      controller.abort();
    };
  }, [key]);

  return answer !== null && answer.key === key ? answer.cents : null;
}
