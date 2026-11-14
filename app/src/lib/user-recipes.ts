import { apiFetch, type MediaModality, type RecipeVariable, type UserRecipe, type UserRecipeVersion } from './api';
import { isUuid } from './ids';
import type { GenerationBody } from './studio';

// The user's own recipes: a setup that works, kept with its versions. Not
// the gallery's recipes (lib/recipe, lib/gallery), nor the gallery recipes a
// user bookmarked (lib/saved-recipes).

/** Recipe ids are UUIDs. Anything else is never sent to the API. */
export function isRecipeId(value: string): boolean {
  return isUuid(value);
}

/** The longest title a recipe takes. */
export const TITLE_MAX_LENGTH = 200;

/** What a version holds, apart from its number: what "Save" sends. */
export interface RecipeSetup {
  template: string;
  variables: RecipeVariable[];
  model: string;
  params: Record<string, unknown>;
}

/** GET /v1/recipes: the caller's recipes, newest first; `before` is a recipe id, for the page after it. */
export function listRecipes(query: { limit: number; before?: string }, signal?: AbortSignal): Promise<UserRecipe[]> {
  const params = new URLSearchParams({ limit: String(query.limit) });
  if (query.before) params.set('before', query.before);
  return apiFetch<UserRecipe[]>(`/v1/recipes?${params}`, { signal });
}

/** GET /v1/recipes/{id}. Another account's recipe answers 404, as a missing one does. */
export function fetchRecipe(id: string, signal?: AbortSignal): Promise<UserRecipe> {
  return apiFetch<UserRecipe>(`/v1/recipes/${encodeURIComponent(id)}`, { signal });
}

/** GET /v1/recipes/{id}/versions: every version, newest first. */
export function fetchVersions(id: string, signal?: AbortSignal): Promise<UserRecipeVersion[]> {
  return apiFetch<UserRecipeVersion[]>(`/v1/recipes/${encodeURIComponent(id)}/versions`, { signal });
}

/** POST /v1/recipes: a recipe and its version 1. A 409's `code` is `recipe_limit_reached`. */
export function createRecipe(recipe: RecipeSetup & {
  title: string;
  modality: MediaModality;
  source_gallery_recipe?: string;
}): Promise<UserRecipe> {
  return apiFetch<UserRecipe>('/v1/recipes', { method: 'POST', json: recipe });
}

/**
 * POST /v1/recipes/{id}/versions. The API writes a version only when the
 * setup differs from the latest one; otherwise it answers with the latest,
 * so the caller tells a new version by its number.
 */
export function saveVersion(id: string, setup: RecipeSetup): Promise<UserRecipeVersion> {
  return apiFetch<UserRecipeVersion>(`/v1/recipes/${encodeURIComponent(id)}/versions`, { method: 'POST', json: setup });
}

/** PATCH /v1/recipes/{id}: the title is the one thing about a recipe itself that changes. */
export function renameRecipe(id: string, title: string): Promise<UserRecipe> {
  return apiFetch<UserRecipe>(`/v1/recipes/${encodeURIComponent(id)}`, { method: 'PATCH', json: { title } });
}

/** DELETE /v1/recipes/{id}: the recipe and its versions. Its jobs are kept. */
export function deleteRecipe(id: string): Promise<void> {
  return apiFetch<void>(`/v1/recipes/${encodeURIComponent(id)}`, { method: 'DELETE' });
}

/** The Studio page that opens a recipe. */
export function studioLink(recipe: Pick<UserRecipe, 'id' | 'modality'>): string {
  return `/create/${recipe.modality.toLowerCase()}?recipe=${encodeURIComponent(recipe.id)}`;
}

/** What a request asks for besides the model and the prompt: the params a version keeps. */
export function paramsOf(body: GenerationBody): Record<string, unknown> {
  const params: Record<string, unknown> = {};
  if (body.size !== undefined) params.size = body.size;
  if (body.n !== undefined) params.n = body.n;
  if (body.seconds !== undefined) params.seconds = body.seconds;
  return params;
}

const PARAM_NAMES: Record<string, string> = { size: 'Size', n: 'Count', seconds: 'Duration', style: 'Style', quality: 'Quality' };

/** A version's params as short text: "1024x1024 · 2 images". Empty when it keeps none. */
export function paramsText(params: Record<string, unknown>): string {
  return Object.entries(params)
    .filter(([, value]) => typeof value === 'string' || typeof value === 'number')
    .map(([key, value]) => (key === 'seconds' ? `${value} seconds` : key === 'size' ? String(value) : `${PARAM_NAMES[key] ?? key} ${value}`))
    .join(' · ');
}

/** "12 attempts", "1 attempt", "No attempts yet". */
export function attemptsText(attempts: number): string {
  return attempts === 0 ? 'No attempts yet' : `${attempts.toLocaleString('en-US')} ${attempts === 1 ? 'attempt' : 'attempts'}`;
}
