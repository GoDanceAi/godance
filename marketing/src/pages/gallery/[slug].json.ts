/**
 * The gallery's static JSON, read by the app:
 *   /gallery/index.json     a light list of every recipe
 *   /gallery/<slug>.json    one recipe, for the hand-off into the app
 *   /gallery/_search.json   search data, fetched by the gallery page on first use
 *
 * With the gallery off there are no paths, so no file is written.
 */
import type { APIRoute, GetStaticPaths } from 'astro';
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
  if (params.slug === 'index') body = indexJson(recipes, config.mediaBaseUrl);
  else if (params.slug === '_search') body = searchJson(recipes);
  else {
    const recipe = recipes.find((r) => r.slug === params.slug);
    if (!recipe) return new Response(null, { status: 404 });
    body = recipeJson(recipe, origin);
  }
  return new Response(JSON.stringify(body), {
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
  });
};
