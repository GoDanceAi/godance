import type { JobView, MediaModality, ModelEntry } from './api';
import { fetchJob, isJobId, mediaModality } from './jobs';
import { cheapest, controlsFor, modelName } from './media-models';
import { PROMPT_MAX_LENGTH, type StudioValues } from './studio';

/*
 * Everything the Studio's URL can ask it to load, read in this one module.
 * When several sources are present, the first wins:
 *
 *   1. ?job=<id>                             one of the caller's own jobs;
 *                                            also how a reload resumes a run
 *   2. ?model=&prompt=&size=&n=&seconds=     loose values
 *
 * A new source (a gallery recipe, ?example=<slug>) slots in between 1 and 2:
 * a kind in PrefillRequest, a branch in readPrefill and in resolvePrefill.
 *
 * Loading never generates. A link can fill the form; only the Generate
 * button spends credit.
 */

/** Values a link asks for. None is trusted: fitValues checks each against the model. */
export interface PrefillValues {
  model?: string;
  prompt?: string;
  size?: string | null;
  n?: number | null;
  seconds?: number | null;
}

export type PrefillRequest =
  | { kind: 'job'; jobId: string }
  | { kind: 'values'; values: PrefillValues }
  | { kind: 'none' };

const MODEL_ID_MAX = 100;

function positiveInt(raw: string | null): number | null {
  const s = raw?.trim() ?? '';
  return /^\d{1,4}$/.test(s) && Number(s) > 0 ? Number(s) : null;
}

/** What the URL asks for. A malformed value is ignored, as if it were absent. */
export function readPrefill(params: URLSearchParams): PrefillRequest {
  const job = params.get('job');
  if (job && isJobId(job)) return { kind: 'job', jobId: job };

  const values: PrefillValues = {};
  const model = params.get('model')?.trim();
  if (model) values.model = model.slice(0, MODEL_ID_MAX);
  const prompt = params.get('prompt');
  if (prompt?.trim()) values.prompt = prompt.slice(0, PROMPT_MAX_LENGTH);
  const size = params.get('size')?.trim();
  if (size) values.size = size;
  const n = positiveInt(params.get('n'));
  if (n !== null) values.n = n;
  const seconds = positiveInt(params.get('seconds'));
  if (seconds !== null) values.seconds = seconds;
  return Object.keys(values).length > 0 ? { kind: 'values', values } : { kind: 'none' };
}

export interface Prefill {
  values: PrefillValues;
  job?: JobView;                     // ?job=: shown as the result, and polled while it runs
  otherModality?: MediaModality;     // ?job= is the other kind; the Studio moves to that page
  note?: string;                     // what the link asked for that could not be loaded
}

const JOB_NOT_LOADED = "That generation couldn't be loaded, so the form starts empty.";

/** The form values from a job's own record. */
export function valuesFromJob(job: JobView): PrefillValues {
  const p = job.params ?? {};
  const values: PrefillValues = { model: job.model };
  if (job.prompt) values.prompt = job.prompt.slice(0, PROMPT_MAX_LENGTH);
  if (typeof p.size === 'string' && p.size) values.size = p.size;
  if (Number.isInteger(p.n) && (p.n as number) > 0) values.n = p.n;
  if (Number.isInteger(p.seconds) && (p.seconds as number) > 0) values.seconds = p.seconds;
  return values;
}

/** Fetches what the request names. Never rejects: what can't be loaded becomes a note. */
export async function resolvePrefill(request: PrefillRequest, modality: MediaModality): Promise<Prefill> {
  switch (request.kind) {
    case 'none':
      return { values: {} };
    case 'values':
      return { values: request.values };
    case 'job': {
      try {
        const job = await fetchJob(request.jobId);
        const kind = mediaModality(job);
        if (!kind) return { values: {}, note: JOB_NOT_LOADED };
        if (kind !== modality) return { values: {}, otherModality: kind };
        return { values: valuesFromJob(job), job };
      } catch {
        return { values: {}, note: JOB_NOT_LOADED };
      }
    }
  }
}

/**
 * Values fitted to the catalog. A model it lacks gives way to the cheapest
 * one, with a note saying so. Size, count and duration are kept only where
 * the model offers them and the value is one it allows; otherwise the model's
 * defaults apply.
 */
export function fitValues(
  values: PrefillValues,
  models: ModelEntry[],
  modality: MediaModality,
): { values: StudioValues; note?: string } {
  const asked = values.model ? models.find((m) => m.id === values.model) : undefined;
  const model = asked ?? cheapest(models);
  const controls = controlsFor(model, modality);
  const fitted: StudioValues = {
    model: model?.id ?? '',
    prompt: (values.prompt ?? '').slice(0, PROMPT_MAX_LENGTH),
    size: values.size && controls.sizes?.includes(values.size) ? values.size : controls.defaultSize,
    n: values.n && controls.maxN && values.n <= controls.maxN ? values.n : 1,
    seconds: values.seconds && controls.durations?.includes(values.seconds) ? values.seconds : controls.defaultSeconds,
  };
  const note = values.model && !asked && model
    ? `“${values.model}” isn't available, so ${modelName(model)} is selected.`
    : undefined;
  return { values: fitted, note };
}
