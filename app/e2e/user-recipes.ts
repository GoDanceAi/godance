import type { UserRecipe, UserRecipeVersion } from '../src/lib/api';
import { problem, type MockApi } from './mock-api';

// The user's own recipes, as /v1/recipes serves them (round 10 contract):
// fixtures, and the routes over a list a test can read back.

/** The nth recipe id. Ids are UUIDs, as the API's are. */
export function recipeId(n: number): string {
  return `5ec10000-0000-4000-8000-${String(n).padStart(12, '0')}`;
}

/** The id of version `number` of the nth recipe. */
export function versionId(n: number, number: number): string {
  return `5ec1${String(number).padStart(4, '0')}-0000-4000-8000-${String(n).padStart(12, '0')}`;
}

export const MUG_TEMPLATE = 'A {{color}} mug on a {{surface}}, soft morning light';

/** Version 3 of the first recipe: two variables, on gpt-image-1.5 of the e2e catalog. */
export function makeVersion(overrides: Partial<UserRecipeVersion> = {}): UserRecipeVersion {
  return {
    id: versionId(1, 3),
    number: 3,
    template: MUG_TEMPLATE,
    variables: [{ name: 'color', default: 'red' }, { name: 'surface', default: 'kitchen shelf' }],
    model: 'gpt-image-1.5',
    params: { size: '1536x1024', n: 2 },
    created_at: '2026-11-13T09:00:00Z',
    ...overrides,
  };
}

/** An image recipe with three versions, twelve attempts and $0.74 spent. */
export function makeRecipe(overrides: Partial<UserRecipe> = {}): UserRecipe {
  return {
    id: recipeId(1),
    title: 'Mug on a kitchen shelf',
    modality: 'IMAGE',
    source_gallery_recipe: null,
    created_at: '2026-11-10T08:00:00Z',
    updated_at: '2026-11-13T09:00:00Z',
    latest_version: makeVersion(),
    attempts: 12,
    spent_cents: 74,
    ...overrides,
  };
}

const ONE = /^\/v1\/recipes\/([0-9a-f-]{36})$/;
const VERSIONS = /^\/v1\/recipes\/([0-9a-f-]{36})\/versions$/;

/** What a version holds besides its number: what the API compares to decide whether a save is a new version. */
function setupOf(v: { template: unknown; variables: unknown; model: unknown; params: unknown }): string {
  return JSON.stringify([v.template, v.variables, v.model, v.params]);
}

/**
 * /v1/recipes as the API serves it, over `recipes` (newest first). The list
 * is changed in place, so a test reads what the app did from it. `older`
 * holds each recipe's versions before its latest, newest first; a recipe
 * without an entry has only its latest.
 */
export function serveRecipes(
  api: MockApi,
  recipes: UserRecipe[],
  older: Record<string, UserRecipeVersion[]> = {},
): MockApi {
  const find = (path: string, pattern: RegExp) => recipes.find((r) => r.id === pattern.exec(path)?.[1]);
  const notFound = problem(404, 'Recipe not found.', 'recipe_not_found');
  return api
    .on('GET', '/v1/recipes', (call) => {
      let list = recipes;
      const before = call.query.get('before');
      if (before) {
        const at = list.findIndex((r) => r.id === before);
        list = at === -1 ? [] : list.slice(at + 1);
      }
      const limit = call.query.get('limit');
      return { json: limit ? list.slice(0, Number(limit)) : list };
    })
    .on('POST', '/v1/recipes', (call) => {
      const body = call.body as {
        title: string; modality: UserRecipe['modality']; template: string; variables: UserRecipeVersion['variables'];
        model: string; params: Record<string, unknown>; source_gallery_recipe?: string;
      };
      const n = 100 + recipes.length;
      const recipe: UserRecipe = {
        id: recipeId(n),
        title: body.title,
        modality: body.modality,
        source_gallery_recipe: body.source_gallery_recipe ?? null,
        created_at: '2026-11-14T10:00:00Z',
        updated_at: '2026-11-14T10:00:00Z',
        latest_version: {
          id: versionId(n, 1), number: 1, template: body.template, variables: body.variables ?? [],
          model: body.model, params: body.params ?? {}, created_at: '2026-11-14T10:00:00Z',
        },
        attempts: 0,
        spent_cents: 0,
      };
      recipes.unshift(recipe);
      return { status: 201, json: recipe };
    })
    .on('GET', ONE, (call) => {
      const recipe = find(call.path, ONE);
      return recipe ? { json: recipe } : notFound;
    })
    .on('PATCH', ONE, (call) => {
      const recipe = find(call.path, ONE);
      if (!recipe) return notFound;
      recipe.title = String((call.body as { title: string }).title).trim();
      recipe.updated_at = '2026-11-14T11:00:00Z';
      return { json: recipe };
    })
    .on('DELETE', ONE, (call) => {
      const at = recipes.findIndex((r) => r.id === ONE.exec(call.path)?.[1]);
      if (at === -1) return notFound;
      recipes.splice(at, 1);
      return { status: 204 };
    })
    .on('GET', VERSIONS, (call) => {
      const recipe = find(call.path, VERSIONS);
      return recipe ? { json: [recipe.latest_version, ...(older[recipe.id] ?? [])] } : notFound;
    })
    .on('POST', VERSIONS, (call) => {
      const recipe = find(call.path, VERSIONS);
      if (!recipe) return notFound;
      const body = call.body as Pick<UserRecipeVersion, 'template' | 'variables' | 'model' | 'params'>;
      const latest = recipe.latest_version;
      // Nothing changed: the latest version as it stands, and no new one.
      if (setupOf(body) === setupOf(latest)) return { status: 200, json: latest };
      const number = latest.number + 1;
      const version: UserRecipeVersion = {
        id: versionId(Number(recipe.id.slice(-12)), number), number, template: body.template,
        variables: body.variables, model: body.model, params: body.params, created_at: '2026-11-14T12:00:00Z',
      };
      older[recipe.id] = [latest, ...(older[recipe.id] ?? [])];
      recipe.latest_version = version;
      recipe.updated_at = version.created_at;
      return { status: 201, json: version };
    });
}
