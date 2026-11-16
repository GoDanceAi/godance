import assert from 'node:assert/strict';
import { spawn } from 'node:child_process';
import { existsSync, readFileSync, readdirSync, statSync } from 'node:fs';
import { mkdir, mkdtemp, rm, writeFile } from 'node:fs/promises';
import { startPublicApi } from '../community/fixtures.js';
import { discoveryEnabled } from '../../src/lib/site-discovery.ts';
import path from 'node:path';
import { after, test } from 'node:test';
import { fileURLToPath } from 'node:url';

const ROOT = fileURLToPath(new URL('../../', import.meta.url));
const pkg = path.join(ROOT, 'node_modules/astro');
const ASTRO = path.join(pkg, JSON.parse(readFileSync(path.join(pkg, 'package.json'), 'utf8')).bin.astro);
const dirs = [];
after(async () => { for (const dir of dirs) await rm(dir, { recursive: true, force: true }); });
async function build(flag, extra = {}) {
  await mkdir(path.join(ROOT, '.astro'), { recursive: true });
  const dir = await mkdtemp(path.join(ROOT, '.astro/discovery-')); dirs.push(dir);
  const env = Object.fromEntries(Object.entries(process.env).filter(([k]) => !/^(PUBLIC_|GALLERY_|COMMUNITY_)/.test(k)));
  const child = spawn(process.execPath, [ASTRO, 'build', '--outDir', dir], { cwd: ROOT, env: { ...env, PUBLIC_SITE_DISCOVERY_ENABLED: flag, PUBLIC_APP_URL: 'https://app.example.test', ...extra, ASTRO_TELEMETRY_DISABLED: '1', NO_COLOR: '1' }, stdio: ['ignore', 'pipe', 'pipe'] });
  let output = ''; child.stdout.on('data', c => output += c); child.stderr.on('data', c => output += c);
  const status = await new Promise(resolve => child.on('close', resolve));
  return { dir, status, output };
}
const read = (dir, file) => readFileSync(path.join(dir, file), 'utf8');
function files(dir) { return readdirSync(dir).flatMap(f => statSync(path.join(dir,f)).isDirectory() ? files(path.join(dir,f)).map(x => `${f}/${x}`) : [f]); }

test('discovery off emits the legacy shell and no product pages', async () => {
  const b = await build('false'); assert.equal(b.status, 0, b.output);
  assert.equal(existsSync(path.join(b.dir, 'create/index.html')), false);
  assert.equal(existsSync(path.join(b.dir, 'integrations/index.html')), false);
  assert.doesNotMatch(read(b.dir, 'index.html'), /data-discovery-nav/);
});
test('discovery on emits the workflow and accessible groups, using configured app destinations', async () => {
  const b = await build('true'); assert.equal(b.status, 0, b.output);
  for (const route of ['index.html', 'create/index.html', 'integrations/index.html']) {
    const html = read(b.dir, route);
    assert.match(html, /data-discovery-nav/);
    for (const label of ['Create', 'Explore', 'Developers', 'Pricing']) assert.ok(html.includes(label), `${route}: ${label}`);
    assert.doesNotMatch(html, /href="\/gallery(?:"|\/)|href="\/community(?:"|\/)/);
    assert.match(html, /https:\/\/app.example.test/);
    assert.match(html, /aria-label="Main navigation"/);
  }
  const home = read(b.dir, 'index.html');
  assert.match(home, /History/); assert.match(home, /recipe/i); assert.match(home, /Run at scale/);
  assert.doesNotMatch(home, /TTS, STT|image and video run as async jobs/);
  for (const route of ['create/index.html', 'integrations/index.html']) {
    const html = read(b.dir, route);
    assert.match(html, /rel="canonical"/);
    assert.doesNotMatch(html, /\/settings\/routing|Bring your own|BYOK/);
  }
  assert.doesNotMatch(read(b.dir, 'pricing/index.html'), /For volume, SLAs, or your own provider keys/);
  assert.match(read(b.dir, 'sitemap-0.xml'), /\/create\/|\/create</);
  const output = new Set(files(b.dir));
  for (const file of output) if (file.endsWith('.html')) {
    const html = read(b.dir, file);
    for (const match of html.matchAll(/href="(\/[^"?#]*)(?:[?#][^"]*)?"/g)) {
      const route = match[1];
      assert.ok(output.has(route.slice(1)) || output.has(`${route.replace(/^\//,'').replace(/\/$/,'')}/index.html`) || route === '/', `${file}: ${route}`);
    }
  }
});
test('malformed discovery setting fails with actionable variable name', async () => {
  const b = await build('maybe'); assert.notEqual(b.status, 0);
  assert.match(b.output, /PUBLIC_SITE_DISCOVERY_ENABLED.*true.*false/);
});

test('strict rollout flag accepts unset/empty/true/false only', () => {
  for (const value of [undefined, '', '  ', 'false', ' FALSE ']) assert.equal(discoveryEnabled({ PUBLIC_SITE_DISCOVERY_ENABLED: value }), false);
  assert.equal(discoveryEnabled({ PUBLIC_SITE_DISCOVERY_ENABLED: ' true ' }), true);
  for (const value of ['1','on','no','maybe']) assert.throws(() => discoveryEnabled({PUBLIC_SITE_DISCOVERY_ENABLED:value}), /PUBLIC_SITE_DISCOVERY_ENABLED/);
});
const galleryEnv = { GALLERY_MEDIA_BASE_URL:'https://media.example.test', GALLERY_REMOVAL_CONTACT:'remove@example.test', GALLERY_OUTPUT_LICENCE:'Synthetic test licence.', GALLERY_CONTENT_DIR: path.join(ROOT,'fixtures/gallery/comparisons') };
test('enabled shell preserves gallery empty, comparison threshold and independent Run this gates', async () => {
  const empty = await mkdtemp(path.join(ROOT,'.astro/discovery-empty-')); dirs.push(empty);
  const e = await build('true', {...galleryEnv,GALLERY_CONTENT_DIR:empty}); assert.equal(e.status,0,e.output);
  assert.doesNotMatch(read(e.dir,'index.html'), /href="\/gallery(?:"|\/)/);
  for (const run of ['false','true']) {
    const b = await build('true', {...galleryEnv,GALLERY_RUN_THIS:run}); assert.equal(b.status,0,b.output);
    const html=read(b.dir,'index.html');
    assert.match(html,/href="\/gallery"/);
    assert.match(html,/href="\/gallery\/compare\/gpt-image-2-vs-seedream-5.0"/);
    const recipe=read(b.dir,'gallery/amber-tram-in-snow/index.html');
    if(run==='true') assert.match(recipe,/data-run-this/); else assert.doesNotMatch(recipe,/data-run-this/);
  }
  const below = await build('true', {...galleryEnv,GALLERY_COMPARE_MIN:'12'}); assert.equal(below.status,0,below.output);
  assert.doesNotMatch(read(below.dir,'index.html'), /href="\/gallery\/compare\//);
});
test('enabled discovery never weakens malformed gallery content or config validation', async () => {
  const invalid = await build('true',{...galleryEnv,GALLERY_MEDIA_BASE_URL:'invalid'}); assert.notEqual(invalid.status,0); assert.match(invalid.output,/media base URL/);
  const dir=await mkdtemp(path.join(ROOT,'.astro/discovery-invalid-'));dirs.push(dir);
  await writeFile(path.join(dir,'bad.json'),'{}');
  const b=await build('true',{...galleryEnv,GALLERY_CONTENT_DIR:dir});assert.notEqual(b.status,0);assert.match(b.output,/bad.json/);
});
test('enabled shell shows only readable approved Community content', async () => {
  for (const state of ['available','empty','failed']) {
    const api=await startPublicApi(state==='empty'?{recipes:[]}:state==='failed'?{answer:(_req,res)=>{res.writeHead(503);res.end();return true;}}:{});
    try {
      const b=await build('true',{COMMUNITY_ENABLED:'true',PUBLIC_API_BASE:api.url});assert.equal(b.status,0,b.output);
      const home=read(b.dir,'index.html');
      assert.equal(home.includes('href="/community"'),state==='available');
      assert.equal(existsSync(path.join(b.dir,'community/index.html')),state==='available');
    } finally {await api.close();}
  }
});
test('documentation contents point at implemented subjects without rollout activation', async () => {
  const b=await build('false');assert.equal(b.status,0,b.output);const docs=read(b.dir,'docs/index.html');
  for (const id of ['studio-workflow','mcp','n8n','byok','community-workflow','routing-policies','self-hosting']) {
    assert.ok(docs.includes(`href="#${id}"`));assert.ok(docs.includes(`id="${id}"`));
  }
  assert.match(docs,/Audio and embeddings have no live endpoints/);
  assert.match(docs,/There is no app routing-policy editor/);
});
