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
  async?: true;
}

export function generationPath(modality: MediaModality): string {
  return modality === 'VIDEO' ? '/v1/videos/generations' : '/v1/images/generations';
}

// Images answer at once unless asked not to; video is always a job.
function withAsync(modality: MediaModality, body: GenerationBody): GenerationBody {
  return modality === 'IMAGE' ? { ...body, async: true } : body;
}

/** The request for the form: the model, the prompt, and only what the model's controls offer. */
export function generationBody(modality: MediaModality, values: StudioValues, controls: Controls): GenerationBody {
  const body: GenerationBody = { model: values.model, prompt: values.prompt };
  if (controls.sizes && values.size) body.size = values.size;
  if (controls.maxN) body.n = values.n;
  if (controls.durations && values.seconds) body.seconds = values.seconds;
  return withAsync(modality, body);
}

function positiveInt(value: unknown): value is number {
  return Number.isInteger(value) && (value as number) > 0;
}

function text(value: unknown): value is string {
  return typeof value === 'string' && value !== '';
}

/** A job's own request, to send again. Null once its prompt has been deleted. */
export function rerunBody(job: JobView): GenerationBody | null {
  const modality = mediaModality(job);
  if (!modality || !job.prompt) return null;
  const p = job.params ?? {};
  const body: GenerationBody = { model: job.model, prompt: job.prompt };
  if (text(p.size)) body.size = p.size;
  if (positiveInt(p.n)) body.n = p.n;
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
