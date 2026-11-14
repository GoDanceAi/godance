import type { JobView, MediaModality, ModelEntry, UserRecipe, UserRecipeVersion } from './api';
import { loadRecipe } from './gallery';
import { fetchJob, isJobId, mediaModality } from './jobs';
import { cheapest, controlsFor, modelName } from './media-models';
import { defaultsIn, isRecipeSlug, type GalleryRecipe, type RecipeLang } from './recipe';
import { PROMPT_MAX_LENGTH, type StudioValues } from './studio';
import { formTemplate, renderForm, type FormTemplate } from './template';
import { fetchRecipe, fetchVersions, isRecipeId } from './user-recipes';

/*
 * Everything the Studio's URL can ask it to load, read in this one module.
 * When several sources are present, the first wins:
 *
 *   1. ?job=<id>                             one of the caller's own jobs;
 *                                            also how a reload resumes a run.
 *                                            A job that ran one of the
 *                                            caller's recipes brings that
 *                                            recipe back, with its values
 *   2. ?recipe=<id>                          one of the caller's own recipes,
 *                                            at its latest version
 *   3. ?example=<slug>                       a recipe from the gallery, the
 *                                            "Run this" link (lib/gallery)
 *   4. ?model=&prompt=&size=&n=&seconds=     loose values
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
  | { kind: 'recipe'; recipeId: string }
  | { kind: 'example'; slug: string }
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

  const recipe = params.get('recipe');
  if (recipe && isRecipeId(recipe)) return { kind: 'recipe', recipeId: recipe };

  // Checked before any request is made: anything but a valid slug is ignored.
  const example = params.get('example');
  if (example !== null && isRecipeSlug(example)) return { kind: 'example', slug: example };

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
  recipe?: GalleryRecipe;            // ?example=: the recipe the form was filled from
  own?: OwnRecipe;                   // ?recipe=: the caller's recipe, and the version the form holds
  template?: FormTemplate;           // the template the form holds, when what was loaded has variables
  otherModality?: MediaModality;     // what was asked for is the other kind; the Studio moves to that page
  note?: string;                     // what the link asked for that could not be loaded
}

/** One of the caller's recipes in the form: the recipe, and the version it was filled from or saved as. */
export interface OwnRecipe {
  recipe: UserRecipe;
  version: UserRecipeVersion;
}

const JOB_NOT_LOADED = "That generation couldn't be loaded, so the form starts empty.";
const RECIPE_NOT_LOADED = "That recipe couldn't be loaded from the gallery, so the form starts empty.";
const OWN_RECIPE_NOT_LOADED = "That recipe of yours couldn't be loaded, so the form starts empty.";

/** A gallery recipe's template in `lang`, each variable at that language's default. Null when it has none. */
export function templateFromRecipe(recipe: GalleryRecipe, lang: RecipeLang): FormTemplate | null {
  const text = recipe.template?.text[lang];
  return recipe.template && text ? formTemplate(text, defaultsIn(recipe.template.variables, lang)) : null;
}

/** The template of one of the caller's recipes at `version`. Null when the version has no variables. */
export function templateFromVersion(version: UserRecipeVersion): FormTemplate | null {
  return version.variables.length > 0 ? formTemplate(version.template, version.variables) : null;
}

/**
 * The form values from a version of the caller's recipe: its model and
 * params, and its template filled with the defaults as the prompt.
 */
export function valuesFromVersion(version: UserRecipeVersion): PrefillValues {
  const template = templateFromVersion(version);
  const p = version.params ?? {};
  const values: PrefillValues = {
    model: version.model,
    prompt: ((template ? renderForm(template) : version.template) ?? '').slice(0, PROMPT_MAX_LENGTH),
  };
  if (typeof p.size === 'string' && p.size) values.size = p.size;
  if (Number.isInteger(p.n) && (p.n as number) > 0) values.n = p.n as number;
  if (Number.isInteger(p.seconds) && (p.seconds as number) > 0) values.seconds = p.seconds as number;
  return values;
}

/** The form values from a recipe's run, with its prompt in `lang`. Like any link's, fitValues checks them. */
export function valuesFromRecipe(recipe: GalleryRecipe, lang: RecipeLang): PrefillValues {
  const { model, params } = recipe.run;
  const values: PrefillValues = { model, prompt: (recipe.prompt[lang] ?? '').slice(0, PROMPT_MAX_LENGTH) };
  if (params.size !== undefined) values.size = params.size;
  if (params.n !== undefined) values.n = params.n;
  if (params.seconds !== undefined) values.seconds = params.seconds;
  return values;
}

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
        const ran = await recipeOfJob(job, modality);
        return { values: valuesFromJob(job), job, ...(ran ?? {}) };
      } catch {
        return { values: {}, note: JOB_NOT_LOADED };
      }
    }
    case 'recipe': {
      try {
        // Another account's recipe answers 404, as a missing one does.
        const recipe = await fetchRecipe(request.recipeId);
        if (recipe.modality !== 'IMAGE' && recipe.modality !== 'VIDEO') return { values: {}, note: OWN_RECIPE_NOT_LOADED };
        if (recipe.modality !== modality) return { values: {}, otherModality: recipe.modality };
        const version = recipe.latest_version;
        return {
          values: valuesFromVersion(version),
          own: { recipe, version },
          template: templateFromVersion(version) ?? undefined,
        };
      } catch {
        return { values: {}, note: OWN_RECIPE_NOT_LOADED };
      }
    }
    case 'example': {
      // Never rejects: missing, switched off, out of reach and misshapen all come back as null.
      const recipe = await loadRecipe(request.slug);
      if (!recipe) return { values: {}, note: RECIPE_NOT_LOADED };
      if (recipe.modality !== modality) return { values: {}, otherModality: recipe.modality };
      const lang = recipe.run.promptLang;
      return { values: valuesFromRecipe(recipe, lang), recipe, template: templateFromRecipe(recipe, lang) ?? undefined };
    }
  }
}

/**
 * The recipe a job ran, at the version it ran and with the values it ran
 * with, so the next run from the form is another attempt of it. Null when
 * the job ran none, its values were deleted, or the recipe can't be read
 * any more: the form then holds the job's prompt as plain text.
 */
async function recipeOfJob(job: JobView, modality: MediaModality): Promise<Pick<Prefill, 'own' | 'template'> | null> {
  const ran = job.recipe;
  if (!ran || ran.variables === null || !isRecipeId(ran.id)) return null;
  try {
    const recipe = await fetchRecipe(ran.id);
    if (recipe.modality !== modality) return null;
    const version = recipe.latest_version.number === ran.version
      ? recipe.latest_version
      : (await fetchVersions(recipe.id)).find((v) => v.number === ran.version);
    if (!version) return null;
    const template = templateFromVersion(version);
    if (template) {
      for (const variable of template.variables) {
        const value = ran.variables[variable.name];
        if (typeof value === 'string') template.values[variable.name] = value;
      }
    }
    return { own: { recipe, version }, template: template ?? undefined };
  } catch {
    return null;
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
