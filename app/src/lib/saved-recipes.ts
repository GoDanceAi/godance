import { apiFetch, type SavedRecipe } from './api';

// The gallery recipes a user saved to open in the Studio again. The API
// keeps the slugs; the recipes themselves are read from the gallery
// (lib/gallery).

/** GET /v1/saved-recipes: the user's saved recipes, newest first. */
export function listSavedRecipes(signal?: AbortSignal): Promise<SavedRecipe[]> {
  return apiFetch<SavedRecipe[]>('/v1/saved-recipes', { signal });
}

/** PUT /v1/saved-recipes/{slug}. A 409's `code` is `saved_recipes_limit_reached`. */
export function saveRecipe(slug: string): Promise<void> {
  return apiFetch<void>(`/v1/saved-recipes/${encodeURIComponent(slug)}`, { method: 'PUT' });
}

/** DELETE /v1/saved-recipes/{slug}. */
export function unsaveRecipe(slug: string): Promise<void> {
  return apiFetch<void>(`/v1/saved-recipes/${encodeURIComponent(slug)}`, { method: 'DELETE' });
}
