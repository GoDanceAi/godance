import {
  API_BASE,
  type Batch,
  type JobView,
  type MediaModality,
  type UserRecipe,
  type UserRecipeVersion,
} from './api';
import { assistantPrompt, type PromptRecipe } from './assistant-prompt';
import { slotsToSave, type Slot } from './inputs';
import { mediaModality } from './jobs';
import { createPath, fileIdPlaceholder, type AnswerMode, type CodeRequest } from './run-code';
import { rerunBody, withRecipe, type GenerationBody, type RecipeRef } from './studio';
import type { OwnRecipe } from './studio-prefill';
import { variablesToSave, type FormTemplate } from './template';
import { fetchRecipe, fetchVersions, paramsOf } from './user-recipes';

// "Run at scale": what a recipe, a finished Studio result or a batch hands
// over to be run outside the app. One shape for all three, which the panel
// (components/run) turns into a prompt, code, an n8n template and a batch.

export interface HandOver {
  /** What it is called. A key made for it is named after this. */
  name: string;
  modality: MediaModality;
  /** The recipe as the assistant prompt carries it. Nothing else about the account goes there. */
  recipe: PromptRecipe;
  /** One run of it: the request the code is written from. */
  request: GenerationBody;
  /** The files a reader uploads first, and what stands for each one's id in `request`. */
  uploads: { slot: string; placeholder: string }[];
  /** The saved recipe a batch of this runs. Null when it is not one of the user's recipes. */
  batchRecipeId: string | null;
}

/** The fields of a request that a version keeps as its params. */
function paramFields(params: Record<string, unknown>): Partial<GenerationBody> {
  const positive = (value: unknown): value is number => Number.isInteger(value) && (value as number) > 0;
  const text = (value: unknown): value is string => typeof value === 'string' && value !== '';
  const fields: Partial<GenerationBody> = {};
  if (text(params.size)) fields.size = params.size;
  if (positive(params.n)) fields.n = params.n;
  if (positive(params.seconds)) fields.seconds = params.seconds;
  if (text(params.style)) fields.style = params.style;
  if (text(params.quality)) fields.quality = params.quality;
  return fields;
}

/** Images answer at once unless asked not to; the app's own requests always ask for a job. */
function asJob(modality: MediaModality, body: GenerationBody): GenerationBody {
  return modality === 'IMAGE' ? { ...body, async: true } : body;
}

/**
 * A version of one of the user's recipes, run by reference: `recipe` names
 * the version, each variable has its default, and each input slot has a
 * file id to fill in. `model` is the version's unless another is given (a
 * batch may have run it on another).
 */
export function recipeHandOver(
  recipe: UserRecipe,
  version: UserRecipeVersion = recipe.latest_version,
  model: string = version.model,
): HandOver {
  const slots = version.inputs ?? [];
  const uploads = slots.map((slot, index) => ({ slot: slot.slot, placeholder: fileIdPlaceholder(index, slots.length) }));
  const ref: RecipeRef = {
    version_id: version.id,
    variables: Object.fromEntries(version.variables.map((variable) => [variable.name, variable.default])),
    ...(uploads.length > 0
      ? { inputs: Object.fromEntries(uploads.map((upload) => [upload.slot, { file_id: upload.placeholder }])) }
      : {}),
  };
  return {
    name: recipe.title,
    modality: recipe.modality,
    recipe: {
      title: recipe.title,
      modality: recipe.modality,
      template: version.template,
      variables: version.variables,
      inputs: slots,
      model,
      params: version.params ?? {},
      version_id: version.id,
    },
    request: withRecipe(asJob(recipe.modality, { model, ...paramFields(version.params ?? {}) }), ref),
    uploads,
    batchRecipeId: recipe.id,
  };
}

/** A short name for a request that has none: the start of its prompt's first line. */
function nameFrom(prompt: string, modality: MediaModality): string {
  const line = prompt.trim().split('\n')[0].replace(/\s+/g, ' ');
  if (line === '') return modality === 'VIDEO' ? 'Video request' : 'Image request';
  return line.length > 60 ? `${line.slice(0, 59).trimEnd()}…` : line;
}

/** What a request asks for besides the model, the prompt and its inputs. */
function paramsOfRequest(body: GenerationBody): Record<string, unknown> {
  const params = paramsOf(body);
  if (body.style !== undefined) params.style = body.style;
  if (body.quality !== undefined) params.quality = body.quality;
  return params;
}

/**
 * A request the Studio sent, with the form as it stood then. `body` is the
 * request itself, so the code is the code for exactly that run. It ran one
 * of the user's recipes by reference when `own` is the version `body.recipe`
 * names; then it can also run as a batch.
 */
export function studioHandOver(form: {
  modality: MediaModality;
  body: GenerationBody;
  title: string;
  template: FormTemplate | null;
  own: OwnRecipe | null;
  /** The slots the request's inputs came from. */
  slots: Slot[];
}): HandOver {
  const { modality, body, template } = form;
  const ref = body.recipe;
  const own = ref && form.own && form.own.version.id === ref.version_id ? form.own : null;
  const prompt = body.prompt ?? '';
  return {
    name: own ? own.recipe.title : form.title.trim() || nameFrom(prompt, modality),
    modality,
    recipe: {
      ...(own ? { title: own.recipe.title } : {}),
      modality,
      template: own ? own.version.template : template ? template.text : prompt,
      // The values this run had are what the recipe starts from.
      variables: own
        ? own.version.variables.map((variable) => ({
            name: variable.name,
            default: ref?.variables[variable.name] ?? variable.default,
          }))
        : template ? variablesToSave(template) : [],
      inputs: own ? own.version.inputs ?? [] : slotsToSave(form.slots),
      model: body.model,
      params: paramsOfRequest(body),
      ...(own ? { version_id: own.version.id } : {}),
      ...(body.gallery_recipe ? { gallery_recipe: body.gallery_recipe } : {}),
    },
    request: body,
    uploads: [],
    batchRecipeId: own ? own.recipe.id : null,
  };
}

/**
 * A finished job, from the job alone: for a result the Studio opened but did
 * not send itself. The request is the job's own, with its prompt as it was
 * rendered. Null once the prompt has been deleted.
 */
export function jobHandOver(job: JobView): HandOver | null {
  const modality = mediaModality(job);
  const body = rerunBody(job, null);
  if (!modality || !body || !job.prompt) return null;
  const slots: Slot[] = (job.inputs ?? []).map((input, index) => ({ key: `job-${index}`, role: input.role, input: null }));
  return {
    name: nameFrom(job.prompt, modality),
    modality,
    recipe: {
      modality,
      template: job.prompt,
      variables: [],
      inputs: slotsToSave(slots),
      model: job.model,
      params: { ...(job.params ?? {}) },
      ...(job.galleryRecipe ? { gallery_recipe: job.galleryRecipe } : {}),
    },
    request: body,
    uploads: [],
    batchRecipeId: job.recipe?.id ?? null,
  };
}

/**
 * The recipe version a batch ran, read from the API: the batch itself holds
 * only ids. Null for a batch whose recipe has been deleted.
 */
export async function loadBatchHandOver(
  batch: Pick<Batch, 'recipe_id' | 'version_id' | 'model'>,
): Promise<HandOver | null> {
  if (!batch.recipe_id) return null;
  const recipe = await fetchRecipe(batch.recipe_id);
  let version = recipe.latest_version;
  if (batch.version_id && batch.version_id !== version.id) {
    const versions = await fetchVersions(recipe.id);
    version = versions.find((each) => each.id === batch.version_id) ?? version;
  }
  return recipeHandOver(recipe, version, batch.model);
}

/** The hand-over's request as the code is written from it, answered by waiting or by polling. */
export function codeRequest(handOver: HandOver, mode: AnswerMode): CodeRequest {
  return {
    apiBase: API_BASE,
    kind: handOver.modality === 'VIDEO' ? 'video' : 'image',
    body: handOver.request as unknown as Record<string, unknown>,
    mode,
  };
}

/** The prompt for an AI assistant, with this recipe in it. */
export function handOverPrompt(handOver: HandOver): string {
  const request = codeRequest(handOver, 'poll');
  return assistantPrompt(handOver.recipe, { base: API_BASE, createPath: createPath(request.kind, request.body) });
}
