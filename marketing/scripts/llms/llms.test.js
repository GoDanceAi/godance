/**
 * What the site tells AI assistants, checked against the API itself.
 *
 * public/llms.txt is the summary and public/llms-full.txt the complete
 * reference an assistant writes code from. A route named there that the API
 * does not have costs someone a failed first call, so every route either
 * file names must be in the backend's OpenAPI file, which a backend test
 * holds to the code. The OpenAPI file is read from the repository:
 * backend/src/main/resources/static/openapi.yaml.
 *
 * Adding a route to the API: add it to openapi.yaml (the backend's drift
 * test asks for that), and, when an API key can call it, name it in
 * llms-full.txt (the last route check here asks for that). Adding a section
 * to llms-full.txt: add its heading to SECTIONS below, in its place, and to
 * the file's "Contents:" line.
 */
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { describe, test } from 'node:test';

import { isPath, namedPaths, namedRoutes, openApiRoutes, unknownRoutes, unnamedRoutes } from './routes.js';

const read = (relative) => readFileSync(new URL(relative, import.meta.url), 'utf8');

const OPENAPI = '../../../backend/src/main/resources/static/openapi.yaml';
const routes = openApiRoutes(read(OPENAPI));
const llms = read('../../public/llms.txt');
const llmsFull = read('../../public/llms-full.txt');

/**
 * The sections of llms-full.txt, in order. `optional` ones may be absent:
 * "Batches" arrives with the batch routes, between Recipes and Errors.
 */
const SECTIONS = [
  { title: 'Authentication' },
  { title: 'Models and capabilities' },
  { title: 'Estimates' },
  { title: 'Chat' },
  { title: 'Images' },
  { title: 'Edits' },
  { title: 'Files and inputs' },
  { title: 'Video' },
  { title: 'Jobs' },
  { title: 'Recipes' },
  { title: 'Batches', optional: true },
  { title: 'Errors' },
  { title: 'Limits and retention' },
  { title: 'Regions' },
];

/** llms-full.txt cut at its `## ` headings: [{ title, body }]. */
function sections(text) {
  const found = [];
  for (const part of text.split(/^## /m).slice(1)) {
    const end = part.indexOf('\n');
    found.push({ title: part.slice(0, end).trim(), body: part.slice(end + 1) });
  }
  return found;
}

describe('the OpenAPI file, as this check reads it', () => {
  test('is found in the repository and has the API\'s routes', () => {
    assert.ok(routes.length > 80, `read ${routes.length} operations from ${OPENAPI}`);
    const has = (method, path) => routes.some((route) => route.method === method && route.path === path);
    assert.ok(has('POST', '/v1/chat/completions'));
    assert.ok(has('GET', '/v1/jobs/{id}'));
    assert.ok(has('POST', '/v1/videos'));
    assert.ok(has('GET', '/healthz'));
  });

  test('says which routes an API key can call', () => {
    const route = (method, path) => routes.find((r) => r.method === method && r.path === path);
    assert.equal(route('POST', '/v1/videos').apiKey, true);
    assert.equal(route('GET', '/v1/jobs/{id}').apiKey, true);
    assert.equal(route('POST', '/v1/keys').apiKey, false, 'keys are managed from a signed-in session');
    assert.equal(route('GET', '/v1/models').apiKey, false, 'public: no credential at all');
  });
});

describe('reading routes out of text', () => {
  test('finds a method and path, without its query string', () => {
    const text = 'Poll `GET /v1/jobs/{id}`, or list with GET /v1/videos?limit=5&after=x. Then\nDELETE\n  /v1/files/{id}.';
    assert.deepEqual(
      namedRoutes(text).map((r) => `${r.method} ${r.path}`),
      ['GET /v1/jobs/{id}', 'GET /v1/videos', 'DELETE /v1/files/{id}'],
    );
  });

  test('finds a path in a URL, in code and in an example body', () => {
    const text = [
      'curl https://api.godance.ai/v1/videos/$VIDEO_ID/content \\',
      'requests.get(f"{API}/v1/jobs/{body[\'job_id\']}", headers=HEADERS)',
      '{"poll_url": "/v1/jobs/6f1c0b9e-2d4a-4c3b-9a57-0e8d4b2f7c11"}',
      'base_url="https://api.godance.ai/v1"',
      'curl https://api.godance.ai/healthz',
    ].join('\n');
    assert.deepEqual(
      namedPaths(text).map((p) => p.path),
      ['/v1/videos/$VIDEO_ID/content', "/v1/jobs/{body['job_id']}", '/v1/jobs/6f1c0b9e-2d4a-4c3b-9a57-0e8d4b2f7c11', '/healthz'],
    );
  });

  test('matches a named path to an OpenAPI path, variable by variable', () => {
    assert.ok(isPath('/v1/jobs/{id}', '/v1/jobs/{id}'));
    assert.ok(isPath('/v1/jobs/{id}', '/v1/jobs/$JOB_ID'));
    assert.ok(isPath('/v1/jobs/{id}', "/v1/jobs/{body['job_id']}"));
    assert.ok(isPath('/v1/videos/{id}/content', '/v1/videos/{video.id}/content'));
    assert.ok(!isPath('/v1/jobs/saved', '/v1/jobs/{id}'), 'a literal segment is not a variable');
    assert.ok(!isPath('/v1/jobs/{id}', '/v1/jobs/{id}/saved'));
    assert.ok(!isPath('/v1/jobs/{id}', '/v1/job/{id}'));
  });

  test('reports a route the API does not have, and stays quiet about one it has', () => {
    const text = 'Use POST /v1/audio/speech or https://api.godance.ai/v1/embeddings, then GET /v1/jobs/{id}.\nDELETE /v1/models is not a thing.';
    assert.deepEqual(unknownRoutes(text, routes), [
      'line 1: POST /v1/audio/speech',
      'line 2: DELETE /v1/models',
      'line 1: /v1/audio/speech',
      'line 1: /v1/embeddings',
    ]);
  });
});

describe('llms-full.txt', () => {
  test('names only routes the API has', () => {
    assert.ok(namedRoutes(llmsFull).length > 40, 'the file names its routes as METHOD /path');
    assert.deepEqual(unknownRoutes(llmsFull, routes), [],
      'routes named in public/llms-full.txt that are not in the backend\'s openapi.yaml');
  });

  test('names every route an API key can call', () => {
    // What Godance does not offer of OpenAI's Videos API has a route only to say so.
    const offered = routes.filter((route) => route.apiKey && !route.summary.startsWith('Not supported'));
    assert.ok(offered.length > 25, `${offered.length} routes for API keys`);
    assert.deepEqual(unnamedRoutes(llmsFull, offered), [],
      'routes an API key can call (openapi.yaml) that public/llms-full.txt never names as METHOD /path');
  });

  test('has its sections in order', () => {
    const titles = sections(llmsFull).map((section) => section.title);
    const expected = SECTIONS.filter((s) => !s.optional || titles.includes(s.title)).map((s) => s.title);
    assert.deepEqual(titles, expected);
  });

  test('lists every section in its contents line', () => {
    const contents = /^Contents: ([\s\S]*?)\n\n/m.exec(llmsFull);
    assert.ok(contents, 'a "Contents:" paragraph');
    const listed = contents[1].replace(/\n/g, ' ').split('·').map((title) => title.trim());
    assert.deepEqual(listed, sections(llmsFull).map((section) => section.title));
  });

  test('gives every section a curl example and a Python example', () => {
    for (const { title, body } of sections(llmsFull)) {
      const blocks = [...body.matchAll(/^```(\w*)\n([\s\S]*?)^```/gm)].map((m) => ({ language: m[1], code: m[2] }));
      assert.ok(blocks.some((b) => b.language === 'bash' && /\bcurl\b/.test(b.code)), `"${title}" has a curl example`);
      assert.ok(blocks.some((b) => b.language === 'python' && b.code.trim() !== ''), `"${title}" has a Python example`);
    }
  });

  test('uses a video model whose provider still serves it, never one of OpenAI\'s own', () => {
    // OpenAI shut down its Videos API and the sora-2 models on 2026-09-24. Examples
    // an assistant copies must run, so no example sends one.
    const code = [...llmsFull.matchAll(/^```(?:bash|python|json)\n([\s\S]*?)^```/gm)].map((m) => m[1]).join('\n');
    assert.doesNotMatch(code, /sora-2/);
    assert.match(code, /"seedance-2\.0"/);
  });
});

describe('both files', () => {
  test('say nothing of the gallery, which may be switched off', () => {
    // While the gallery is off, nothing the site publishes mentions it (the "gallery off"
    // checks in scripts/gallery-pages). These files are the same either way, so they leave
    // out the request and response fields that exist only for it.
    assert.doesNotMatch(llmsFull, /gallery/i, 'public/llms-full.txt');
    assert.doesNotMatch(llms, /gallery/i, 'public/llms.txt');
  });
});

describe('llms.txt', () => {
  test('names only routes the API has', () => {
    assert.deepEqual(unknownRoutes(llms, routes), [],
      'routes named in public/llms.txt that are not in the backend\'s openapi.yaml');
  });

  test('points to the complete reference and to the OpenAPI description', () => {
    assert.match(llms, /https:\/\/godance\.ai\/llms-full\.txt/);
    assert.match(llms, /https:\/\/api\.godance\.ai\/v1\/openapi\.yaml/);
  });
});
