/**
 * The gallery's static JSON, read by the app:
 *   /gallery/index.json     a light list of every recipe
 *   /gallery/<slug>.json    one recipe, for the hand-off into the app, with
 *                           its input slots and samples when it has them
 *   /gallery/_search.json   search data, fetched by the gallery page on first use
 *
 * With the gallery off there are no paths, so no file is written.
 */
import type { APIRoute, GetStaticPaths } from 'astro';
import { hasInputs, inputsJson } from '../../components/gallery/inputs';
import { indexJson, recipeJson, searchJson } from '../../lib/gallery-api';
import { loadGallery } from '../../lib/gallery-recipes';

export const getStaticPaths = (async () => {
  const gallery = await loadGallery();
  if (!gallery.on) return [];
  return [
    { params: { slug: 'index' } },
    { params: { slug: '_search' } },
    ...gallery.recipes.map((recipe) => ({ params: { slug: recipe.slug } })),
  ];
}) satisfies GetStaticPaths;

export const GET: APIRoute = async ({ params, site }) => {
  const { recipes, config } = await loadGallery();
  const origin = site ?? 'https://godance.ai';

  let body: unknown;
  if (params.slug === 'index') {
    // Present, and true, only for a recipe that takes an input image (round-11 coordination notes).
    const withInputs = new Set(recipes.filter(hasInputs).map((recipe) => recipe.slug));
    body = {
      recipes: indexJson(recipes, config.mediaBaseUrl).recipes.map((entry) =>
        withInputs.has(entry.slug) ? { ...entry, has_inputs: true } : entry,
      ),
    };
  } else if (params.slug === '_search') body = searchJson(recipes);
  else {
    const recipe = recipes.find((r) => r.slug === params.slug);
    if (!recipe) return new Response(null, { status: 404 });
    const { url, ...rest } = recipeJson(recipe, origin);
    // `inputs` only for a recipe that has them: each slot, with the absolute address of its sample.
    body = hasInputs(recipe) ? { ...rest, inputs: inputsJson(recipe, config.mediaBaseUrl), url } : { ...rest, url };
  }
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
};
