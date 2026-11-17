// Optional integration check using the app's existing Chromium tooling.
// Usage: node scripts/discovery/public-index-cors.mjs APP_PROBE_SOURCE PLAYWRIGHT_CORE_MODULE
import { fileURLToPath, pathToFileURL } from 'node:url';
import ts from 'typescript';
import { startPublicApi } from '../community/fixtures.js';
import { createServer } from 'node:http';
import { readFile, mkdtemp, rm } from 'node:fs/promises';
import { spawn } from 'node:child_process';
import { resolve } from 'node:path';
import assert from 'node:assert/strict';

const [probePath, playwrightPath] = process.argv.slice(2);
if (!probePath || !playwrightPath) {
  throw new Error('Pass APP_PROBE_SOURCE and an existing app Playwright core module path.');
}
const { chromium } = await import(pathToFileURL(resolve(playwrightPath)).href);
const root = fileURLToPath(new URL('../../', import.meta.url));
const source = await readFile(resolve(probePath), 'utf8');
const results = [];
const servers = [];
const apis = [];
const scratch = [];

// Apply the actual emitted Cloudflare rules, including the existing global rules.
function headersFor(text, pathname) {
  let pattern = '';
  const result = {};
  for (const line of text.split('\n')) {
    if (!line.trim() || line.trimStart().startsWith('#')) continue;
    if (!/^\s/.test(line)) {
      pattern = line.trim();
      continue;
    }
    const star = pattern.indexOf('*');
    const match = star === -1 ? pathname === pattern
      : pathname.startsWith(pattern.slice(0, star)) && pathname.endsWith(pattern.slice(star + 1));
    if (match) {
      const colon = line.indexOf(':');
      result[line.slice(0, colon).trim()] = line.slice(colon + 1).trim();
    }
  }
  return result;
}
const listen = server => new Promise(resolve => server.listen(0, '127.0.0.1', () => {
  resolve(`http://127.0.0.1:${server.address().port}`);
}));
const close = server => new Promise(resolve => server.close(resolve));
const browser = await chromium.launch({ headless: true });

try {
  for (const state of ['available', 'off', 'empty', 'failed']) {
    const api = await startPublicApi(state === 'empty' ? { recipes: [] } : state === 'failed' ? {
      answer: (_request, response) => {
        response.writeHead(404, { 'content-type': 'application/json' });
        response.end(JSON.stringify({ code: 'community_disabled' }));
        return true;
      },
    } : {});
    apis.push(api);
    const out = await mkdtemp(resolve(root, '.astro/cors-browser-'));
    scratch.push(out);
    const env = Object.fromEntries(Object.entries(process.env).filter(
      ([key]) => !/^(PUBLIC_|GALLERY_|COMMUNITY_)/.test(key),
    ));
    const build = spawn('npm', ['run', 'build', '--', '--outDir', out], {
      cwd: root,
      env: {
        ...env, PUBLIC_SITE_DISCOVERY_ENABLED: state === 'off' ? 'false' : 'true',
        COMMUNITY_ENABLED: 'true', PUBLIC_API_BASE: api.url, ASTRO_TELEMETRY_DISABLED: '1',
      },
      stdio: ['ignore', 'pipe', 'pipe'],
    });
    let log = '';
    build.stdout.on('data', chunk => log += chunk);
    build.stderr.on('data', chunk => log += chunk);
    assert.equal(await new Promise(resolve => build.on('close', resolve)), 0, log);
    await api.close();

    const headers = await readFile(resolve(out, '_headers'), 'utf8');
    let redirect = false;
    const site = createServer(async (request, response) => {
      const pathname = new URL(request.url, 'http://localhost').pathname;
      const configured = headersFor(headers, pathname);
      if (redirect && pathname === '/community') {
        response.writeHead(308, { ...configured, Location: '/community/' });
        response.end();
        return;
      }
      try {
        const body = await readFile(resolve(out, '.' + pathname.replace(/\/$/, '') + '/index.html'));
        response.writeHead(200, { ...configured, 'content-type': 'text/html' });
        response.end(body);
      } catch {
        response.writeHead(404, configured);
        response.end();
      }
    });
    servers.push(site);
    const origin = await listen(site);
    // Only the configured public origin is supplied by the fixture. Probe logic
    // comes from the actual app source, including its final-URL validation.
    const code = ts.transpileModule(source.replace(
      /import \{ GALLERY_ORIGIN \} from '\.\/gallery';/,
      `const GALLERY_ORIGIN=${JSON.stringify(origin)};`,
    ), { compilerOptions: { target: ts.ScriptTarget.ES2022, module: ts.ModuleKind.ESNext } }).outputText;
    const app = createServer((request, response) => {
      response.writeHead(200, {
        'content-type': request.url === '/probe.js' ? 'application/javascript' : 'text/html',
      });
      response.end(request.url === '/probe.js' ? code : '<!doctype html><title>Probe test</title>');
    });
    servers.push(app);
    const appOrigin = await listen(app);
    const page = await browser.newPage();
    await page.goto(appOrigin);
    const probe = () => page.evaluate(async () => {
      const { publicCommunityAvailable } = await import('/probe.js');
      return publicCommunityAvailable(new AbortController().signal);
    });
    const direct = await probe();
    assert.equal(direct, state === 'available', `${state}: direct`);
    redirect = true;
    const canonical = await probe();
    assert.equal(canonical, state === 'available', `${state}: canonical`);
    results.push({
      state, direct, canonical,
      actualBuiltHeader: headersFor(headers, '/community')['Access-Control-Allow-Origin'] ?? null,
      credentialsHeader: headersFor(headers, '/community')['Access-Control-Allow-Credentials'] ?? null,
    });
    await page.close();
    await close(app);
    await close(site);
  }
  console.log(JSON.stringify(results, null, 2));
} finally {
  await browser.close();
  for (const server of servers) await close(server);
  for (const api of apis) await api.close();
  for (const out of scratch) await rm(out, { recursive: true, force: true });
}
