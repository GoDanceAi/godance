/**
 * src/lib/community.ts on its own, against the stand-in for the API's public
 * routes (fixtures.js): the switch, how every page of recipes is read, what
 * is kept of each, and how each way the API can fail leaves the community
 * off with a warning instead of failing the build.
 */
import assert from 'node:assert/strict';
import { describe, test } from 'node:test';
import {
  communityConfig,
  createSnippet,
  parseProfile,
  parseRecipe,
  readCommunity,
  requestBody,
  resolveCommunityConfig,
  runThisUrl,
  runsText,
} from '../../src/lib/community.ts';
import { AUTHOR, PROFILE, RECIPES, startPublicApi } from './fixtures.js';

/** readCommunity with the switch on against `api`, and the warnings it gave. */
async function read(api, env = {}) {
  const warnings = [];
  const community = await readCommunity(
    { COMMUNITY_ENABLED: 'true', PUBLIC_API_BASE: api.url, ...env },
    (message) => warnings.push(message),
  );
  return { community, warnings };
}

/** `n` valid recipes by `authors` handles in turn, newest first. */
function many(n, authors = ['ana-lights', 'bo-paper', 'cy-ink']) {
  return Array.from({ length: n }, (_, i) => ({
    ...RECIPES[1],
    public_id: `r${String(n - i).padStart(5, '0')}xyz`,
    title: `Recipe ${n - i}`,
    author: { handle: authors[i % authors.length], followers: i },
    published_at: new Date(Date.UTC(2026, 10, 16) - i * 60_000).toISOString(),
  }));
}

const profileOf = (handle) => ({ handle, display_name: handle.toUpperCase(), link: null, followers: 1, recipes: [] });

describe('the switch', () => {
  test('ships off, and COMMUNITY_ENABLED turns it on for a build', () => {
    assert.equal(communityConfig.enabled, false);
    assert.deepEqual(resolveCommunityConfig({}), { enabled: false });
    for (const on of ['true', 'TRUE', '1', 'on', 'yes']) assert.deepEqual(resolveCommunityConfig({ COMMUNITY_ENABLED: on }), { enabled: true });
    for (const off of ['', 'false', '0', 'off', 'no']) assert.deepEqual(resolveCommunityConfig({ COMMUNITY_ENABLED: off }), { enabled: false });
  });

  test('a malformed value throws instead of quietly leaving it off', () => {
    assert.throws(() => resolveCommunityConfig({ COMMUNITY_ENABLED: 'yes please' }), /COMMUNITY_ENABLED must be "true" or "false"/);
  });

  test('off, it asks the API nothing', async () => {
    const api = await startPublicApi();
    try {
      const community = await readCommunity({ PUBLIC_API_BASE: api.url }, () => assert.fail('no warning'));
      assert.equal(community.on, false);
      assert.deepEqual(api.requests, []);
    } finally {
      await api.close();
    }
  });
});

describe('reading the public routes', () => {
  test('reads the recipes newest first, and each author\'s profile once', async () => {
    const api = await startPublicApi();
    try {
      const { community, warnings } = await read(api);
      assert.deepEqual(warnings, []);
      assert.equal(community.on, true);
      assert.deepEqual(community.recipes.map((r) => r.public_id), RECIPES.map((r) => r.public_id));
      assert.deepEqual(community.profiles, [{ handle: AUTHOR.handle, display_name: 'Mina', link: 'https://example.test/mina', followers: 3 }]);
      assert.deepEqual(api.requests.map((r) => r.url), ['/v1/public/recipes?limit=100', `/v1/public/profiles/${AUTHOR.handle}`]);
    } finally {
      await api.close();
    }
  });

  test('asks with GET only, and sends no credential of any kind', async () => {
    const api = await startPublicApi();
    try {
      await read(api);
      for (const request of api.requests) {
        assert.equal(request.method, 'GET', request.url);
        assert.ok(request.url.startsWith('/v1/public/'), request.url);
        assert.equal(request.headers.authorization, undefined, request.url);
        assert.equal(request.headers.cookie, undefined, request.url);
      }
    } finally {
      await api.close();
    }
  });

  test('reads every page, each after the last recipe of the one before', async () => {
    const recipes = many(230);
    const api = await startPublicApi({ recipes, profiles: ['ana-lights', 'bo-paper', 'cy-ink'].map(profileOf) });
    try {
      const { community, warnings } = await read(api);
      assert.deepEqual(warnings, []);
      assert.equal(community.recipes.length, 230);
      assert.deepEqual(community.recipes.map((r) => r.public_id), recipes.map((r) => r.public_id));
      const pages = api.requests.filter((r) => r.url.startsWith('/v1/public/recipes')).map((r) => r.url);
      assert.deepEqual(pages, [
        '/v1/public/recipes?limit=100',
        `/v1/public/recipes?limit=100&before=${recipes[99].public_id}`,
        `/v1/public/recipes?limit=100&before=${recipes[199].public_id}`,
      ]);
      assert.deepEqual(community.profiles.map((p) => p.handle), ['ana-lights', 'bo-paper', 'cy-ink']);
    } finally {
      await api.close();
    }
  });

  test('a page that does not move on stops the reading, and the community stays off', async () => {
    const page = many(100);
    const api = await startPublicApi({
      answer(request, response) {
        if (!request.url.startsWith('/v1/public/recipes')) return false;
        response.writeHead(200, { 'content-type': 'application/json' });
        response.end(JSON.stringify(page)); // the same page, whatever `before` says
        return true;
      },
    });
    try {
      const { community, warnings } = await read(api);
      assert.equal(community.on, false);
      assert.match(warnings.join('\n'), /do not move on.*without community pages/s);
    } finally {
      await api.close();
    }
  });

  test('an answer that fails twice for want of a server is tried again, and the third one counts', async () => {
    let failures = 0;
    const api = await startPublicApi({
      answer(request, response) {
        if (request.url.startsWith('/v1/public/recipes') && failures < 2) {
          failures += 1;
          response.writeHead(503);
          response.end();
          return true;
        }
        return false;
      },
    });
    try {
      const { community, warnings } = await read(api);
      assert.deepEqual(warnings, []);
      assert.equal(community.recipes.length, 2);
      assert.equal(failures, 2);
    } finally {
      await api.close();
    }
  });
});

describe('every way the API can fail leaves the community off, with a warning', () => {
  const cases = [
    ['an unreachable API', null, /could not be read \(fetch failed.*\)\. The build goes on without community pages\./],
    ['the community switched off on the API', (res) => {
      res.writeHead(404, { 'content-type': 'application/problem+json' });
      res.end(JSON.stringify({ status: 404, code: 'community_disabled' }));
    }, /the API has the community switched off \(community_disabled\)/],
    ['a server error, three times', (res) => {
      res.writeHead(500);
      res.end('boom');
    }, /could not be read \(HTTP 500\)/],
    ['an answer that is not a list', (res) => {
      res.writeHead(200, { 'content-type': 'application/json' });
      res.end(JSON.stringify({ recipes: RECIPES }));
    }, /the answer is not a list of recipes/],
    ['an answer that is not JSON', (res) => {
      res.writeHead(200, { 'content-type': 'text/html' });
      res.end('<html>maintenance</html>');
    }, /the answer is not a list of recipes/],
  ];
  for (const [what, fail, warning] of cases) {
    test(what, async () => {
      const api = await startPublicApi({ answer: fail ? (_, res) => (fail(res), true) : undefined });
      const url = api.url;
      if (!fail) await api.close(); // nothing listens there any more
      try {
        const { community, warnings } = await read(fail ? api : { url });
        assert.equal(community.on, false);
        assert.deepEqual(community.recipes, []);
        assert.equal(warnings.length, 1, warnings.join('\n'));
        assert.match(warnings[0], warning);
      } finally {
        if (fail) await api.close();
      }
    });
  }

  test('no public recipe yet: off, with no warning', async () => {
    const api = await startPublicApi({ recipes: [] });
    try {
      const { community, warnings } = await read(api);
      assert.equal(community.on, false);
      assert.deepEqual(warnings, []);
    } finally {
      await api.close();
    }
  });

  test('a profile that cannot be read loses only its own page', async () => {
    const api = await startPublicApi({ profiles: [] });
    try {
      const { community, warnings } = await read(api);
      assert.equal(community.on, true);
      assert.equal(community.recipes.length, 2);
      assert.deepEqual(community.profiles, []);
      assert.deepEqual(warnings, [`the profile of @${AUTHOR.handle} could not be read (HTTP 404 not_found); it has no page`]);
    } finally {
      await api.close();
    }
  });
});

describe('what is kept of each recipe', () => {
  test('exactly the public fields: anything else the API might send is dropped', () => {
    const raw = {
      ...RECIPES[1],
      user_id: '0f0e0d0c-0000-4000-8000-000000000001',
      email: 'author@example.test',
      source_job_id: '7f000101-0000-4000-8000-000000000007',
      author: { ...AUTHOR, email: 'author@example.test' },
      examples: [{ ...RECIPES[1].examples[0], source_job_id: 'x', storage_key: 'community/k3v9x2m7q1aa/1.png' }],
      inputs: [{ slot: 'product', role: 'image', hint: 'A product photo', file_id: 'file-1' }],
      params: { size: '1024x1024', nested: { secret: true } },
    };
    const recipe = parseRecipe(raw);
    assert.deepEqual(Object.keys(recipe).sort(), [
      'author', 'examples', 'inputs', 'licence', 'made_from', 'modality', 'model', 'params', 'public_id',
      'published_at', 'runners', 'runs', 'tags', 'template', 'title', 'variables',
    ]);
    assert.deepEqual(recipe.author, AUTHOR);
    assert.deepEqual(recipe.examples, [RECIPES[1].examples[0]]);
    assert.deepEqual(recipe.inputs, [{ slot: 'product', role: 'image', hint: 'A product photo' }]);
    assert.deepEqual(recipe.params, { size: '1024x1024' });
    for (const secret of ['author@example.test', '0f0e0d0c-0000-4000-8000-000000000001', '7f000101', 'storage_key', 'file-1']) {
      assert.ok(!JSON.stringify(recipe).includes(secret), secret);
    }

    const profile = parseProfile({ ...PROFILE, email: 'author@example.test', user_id: 'x' });
    assert.deepEqual(Object.keys(profile).sort(), ['display_name', 'followers', 'handle', 'link']);
  });

  test('a recipe without what a page needs, or whose id or handle could not be a path, is left out', () => {
    const base = RECIPES[1];
    for (const broken of [
      { ...base, public_id: '../../etc/passwd' },
      { ...base, public_id: 'K3V9X2M7Q1AA' },
      { ...base, public_id: 'a/b' },
      { ...base, title: '   ' },
      { ...base, modality: 'audio' },
      { ...base, template: '' },
      { ...base, model: '' },
      { ...base, licence: null },
      { ...base, published_at: 'yesterday' },
      { ...base, author: { handle: 'Not A Handle', followers: 1 } },
      { ...base, author: { handle: 'two--hyphens', followers: 1 } },
      { ...base, author: null },
      null,
      'a recipe',
    ]) {
      assert.equal(parseRecipe(broken), null, JSON.stringify(broken)?.slice(0, 80));
    }
  });

  test('examples must be http(s) pictures or videos; links to a profile must be https', () => {
    const recipe = parseRecipe({
      ...RECIPES[1],
      examples: [
        { url: 'javascript:alert(1)', content_type: 'image/png' },
        { url: 'https://user:pass@api.example.test/x', content_type: 'image/png' },
        { url: 'https://api.example.test/ok', content_type: 'text/html' },
        { url: 'https://api.example.test/ok', content_type: 'image/webp' },
      ],
      made_from: { kind: 'gallery', id: '../x' },
      tags: ['ok', 'Not OK', 'two--hyphens', 'ok'],
    });
    assert.deepEqual(recipe.examples, [{ url: 'https://api.example.test/ok', content_type: 'image/webp' }]);
    assert.equal(recipe.made_from, null);
    assert.deepEqual(recipe.tags, ['ok']);
    assert.equal(parseProfile({ ...PROFILE, link: 'http://example.test/mina' }).link, null);
    assert.equal(parseProfile({ ...PROFILE, link: 'javascript:alert(1)' }).link, null);
  });
});

describe('links and words', () => {
  test('"Run this" opens the Studio for the recipe\'s kind with ?published=', () => {
    assert.equal(
      runThisUrl('https://app.godance.ai', RECIPES[1]),
      'https://app.godance.ai/create/image?published=k3v9x2m7q1aa&utm_source=godance&utm_medium=community&utm_campaign=k3v9x2m7q1aa',
    );
    assert.match(runThisUrl('https://app.godance.ai', RECIPES[0]), /\/create\/video\?published=p8w2n4c6t0bb&/);
  });

  test('the API call runs the published recipe with its model, params and defaults', () => {
    assert.deepEqual(requestBody(RECIPES[0]), {
      model: 'kling-3.0',
      recipe: { published_id: 'p8w2n4c6t0bb', variables: { weather: 'falling rain', palette: 'muted green' } },
      size: '1280x720',
      seconds: 5,
    });
    const snippet = createSnippet(RECIPES[0]);
    assert.ok(snippet.startsWith('curl https://api.godance.ai/v1/videos/generations \\\n'));
    assert.deepEqual(JSON.parse(snippet.split("<<'JSON'\n")[1].replace(/\nJSON$/, '')), requestBody(RECIPES[0]));
  });

  test('runs count others: none yet, one, many', () => {
    assert.equal(runsText({ runs: 0, runners: 0 }), 'Not run by anyone else yet');
    assert.equal(runsText({ runs: 1, runners: 1 }), 'Run 1 time by 1 person');
    assert.equal(runsText({ runs: 1200, runners: 31 }), 'Run 1,200 times by 31 people');
  });
});
