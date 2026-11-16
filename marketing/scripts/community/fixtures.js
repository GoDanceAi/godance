/**
 * Fixtures for the community pages (round 14 brief 02): public recipes and a
 * public profile exactly as the API's public routes answer them (the
 * `PublicRecipe` and `PublicProfile` schemas in
 * backend/src/main/resources/static/openapi.yaml), and a stand-in for those
 * routes on 127.0.0.1, so a build reads them without leaving this machine.
 *
 * Every host is example.test and every id is made up.
 */
import http from 'node:http';

export const AUTHOR = { handle: 'mina-teapots', followers: 3 };

/** The example's URL as the API writes it: absolute, its own redirect route. */
const exampleUrl = (publicId, position) => `https://api.example.test/v1/public/recipes/${publicId}/examples/${position}`;

/** Two public recipes by one author, newest first, as GET /v1/public/recipes lists them. */
export const RECIPES = [
  {
    public_id: 'p8w2n4c6t0bb',
    title: 'Koi under falling rain',
    modality: 'video',
    template: 'Koi circling under {{weather}}, slow overhead shot, {{palette}} tones',
    variables: [
      { name: 'weather', default: 'falling rain' },
      { name: 'palette', default: 'muted green' },
    ],
    inputs: [],
    model: 'kling-3.0',
    params: { size: '1280x720', seconds: 5 },
    examples: [{ url: exampleUrl('p8w2n4c6t0bb', 1), content_type: 'video/mp4' }],
    made_from: { kind: 'published', id: 'k3v9x2m7q1aa' },
    runs: 0,
    runners: 0,
    licence: 'CC0-1.0',
    author: AUTHOR,
    tags: ['nature'],
    published_at: '2026-11-16T08:00:00Z',
  },
  {
    public_id: 'k3v9x2m7q1aa',
    title: 'Teapot still life',
    modality: 'image',
    template: 'A {{thing}} on a wooden table, soft window light.',
    variables: [{ name: 'thing', default: 'teapot' }],
    inputs: [],
    model: 'gpt-image-2',
    params: { size: '1024x1024' },
    examples: [
      { url: exampleUrl('k3v9x2m7q1aa', 1), content_type: 'image/png' },
      { url: exampleUrl('k3v9x2m7q1aa', 2), content_type: 'image/png' },
    ],
    made_from: { kind: 'gallery', id: 'enamel-teapot-on-marble' },
    runs: 12,
    runners: 5,
    licence: 'CC-BY-4.0',
    author: AUTHOR,
    tags: ['still-life', 'kitchen'],
    published_at: '2026-11-15T10:00:00Z',
  },
];

/** The author's public profile, as GET /v1/public/profiles/{handle} answers it. */
export const PROFILE = {
  handle: AUTHOR.handle,
  display_name: 'Mina',
  link: 'https://example.test/mina',
  followers: AUTHOR.followers,
  recipes: RECIPES,
};

const json = (response, status, body, headers = {}) => {
  response.writeHead(status, { 'content-type': 'application/json', ...headers });
  response.end(JSON.stringify(body));
};

/** The API's problem+json for a public route that has nothing to show. */
const notFound = (response, code = 'not_found') =>
  json(response, 404, { title: 'Not Found', status: 404, detail: 'not found', code }, {
    'content-type': 'application/problem+json',
  });

/**
 * A stand-in for the public routes: `GET /v1/public/recipes` (paged by
 * `limit` and `before`, filtered by `tag`, newest first), one recipe by its
 * public id, and one profile by its handle. Anything else answers 404, as
 * the API does for a route it has no such thing on.
 *
 * `answer(request)` may answer first, for a test that needs the API to
 * fail; it returns true when it did. Every request is recorded in
 * `requests`, with its method, URL and headers.
 *
 * @param {{ recipes?: object[], profiles?: object[], answer?: Function }} [options]
 */
export async function startPublicApi({ recipes = RECIPES, profiles = [PROFILE], answer } = {}) {
  const requests = [];
  const server = http.createServer((request, response) => {
    requests.push({ method: request.method, url: request.url, headers: request.headers });
    if (answer?.(request, response)) return;
    const url = new URL(request.url, 'http://127.0.0.1');
    const cached = { 'cache-control': 'public, max-age=300' };
    if (request.method !== 'GET') return notFound(response);

    if (url.pathname === '/v1/public/recipes') {
      const tag = url.searchParams.get('tag');
      let list = tag ? recipes.filter((recipe) => recipe.tags.includes(tag)) : recipes;
      const before = url.searchParams.get('before');
      if (before) {
        const at = list.findIndex((recipe) => recipe.public_id === before);
        list = at === -1 ? [] : list.slice(at + 1);
      }
      const limit = Math.min(Number(url.searchParams.get('limit') ?? 50) || 50, 100);
      return json(response, 200, list.slice(0, limit), cached);
    }
    const one = /^\/v1\/public\/recipes\/([^/]+)$/.exec(url.pathname);
    if (one) {
      const recipe = recipes.find((each) => each.public_id === decodeURIComponent(one[1]));
      return recipe ? json(response, 200, recipe, cached) : notFound(response);
    }
    const profile = /^\/v1\/public\/profiles\/([^/]+)$/.exec(url.pathname);
    if (profile) {
      const found = profiles.find((each) => each.handle === decodeURIComponent(profile[1]));
      return found ? json(response, 200, found, cached) : notFound(response);
    }
    return notFound(response);
  });
  await new Promise((resolve) => server.listen(0, '127.0.0.1', resolve));
  return {
    url: `http://127.0.0.1:${server.address().port}`,
    requests,
    close: () => new Promise((resolve) => server.close(resolve)),
  };
}
