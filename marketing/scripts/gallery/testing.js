/**
 * Shared by the tests in this folder: invented lists in the same shape as
 * the real ones, and a stand-in for the Godance API and for the host the
 * lists are read from. Nothing here is third-party text, and nothing here
 * touches the network.
 */
import { mkdtemp, mkdir, readFile, readdir, rm, writeFile } from 'node:fs/promises';
import os from 'node:os';
import path from 'node:path';

export const FAKE_KEY = 'gd_live_TESTKEY_never_written_0123456789';
export const API_BASE = 'https://api.godance.test';
export const IMAGE_COMMIT = 'a'.repeat(40);
export const VIDEO_COMMIT = 'b'.repeat(40);

export const CC0_TEXT = 'CC0 1.0 Universal\n\nStatement of Purpose\n\nThe laws of most jurisdictions…';
export const CC_BY_TEXT =
  'Creative Commons Attribution 4.0 International License (CC BY 4.0)\n\nThis work is licensed under the Creative Commons Attribution 4.0 International License.\nhttps://creativecommons.org/licenses/by/4.0/';

/** An image list in the shape of the real one: both entry forms, a duplicate, a broken case. */
export const IMAGE_LIST = {
  LICENSE: CC0_TEXT,
  'cases/poster.md': `# Poster Cases

> Part of the list

### Case 1: [Harbour Poster](https://x.com/inventedone/status/1001) (by [@inventedone](https://x.com/inventedone))

| Output |
| :----: |
| <a href="https://example.com"><img src="https://example.com/picture.jpg" width="300"></a> |

**Prompt:**

\`\`\`
A flat poster of a small harbour at dawn, {argument name="boat colour" default="red"} boats, 3:4 ratio.
\`\`\`

### Case 2: Tea Label

**Source**: [@inventedtwo](https://x.com/inventedtwo/status/1002)

**Prompt**:
\`\`\`
一张茶叶包装标签，绿色山丘，极简风格，竖版构图，纸张质感，柔和光线。
\`\`\`

**Output**:

<img src="../images/poster_case2/output.jpg" width="500">

---

### Case 3: [Broken One](https://x.com/inventedthree/status/1003) (by [@inventedthree](https://x.com/inventedthree))

**Prompt:**

\`\`\`

<!-- Case 3: spliced -->
### Case 4: [Portrait From Photo](https://x.com/inventedfour/status/1004) (by [@inventedfour](https://x.com/inventedfour))

**Prompt:**

\`\`\`
Turn the uploaded photo into a watercolour portrait.
\`\`\`
### Case 1: [Harbour Poster Again](https://twitter.com/inventedone/status/1001?s=20) (by [@inventedone](https://x.com/inventedone))

**Prompt:**

\`\`\`
The same post, listed twice under a reused case number.
\`\`\`
### Case 5: [City Card](https://x.com/inventedfive/status/1005) (by [@inventedfive](https://x.com/inventedfive))

**Prompt:**

\`\`\`
A travel card for [CITY NAME] with a skyline at dusk.
\`\`\`
### Case 6: [Lighthouse Print](https://x.com/inventedsix/status/1006) (by [@inventedsix](https://x.com/inventedsix))

**Prompt:**

\`\`\`
A linocut print of a lighthouse on a cliff, two colours, wide 16:9 frame.
\`\`\`
`,
  'cases/poster_zh-CN.md': `# 海报案例

### Case 1: [港口海报](https://x.com/inventedone/status/1001) (by [@inventedone](https://x.com/inventedone))

**Prompt:**

\`\`\`
A flat poster of a small harbour at dawn, {argument name="boat colour" default="red"} boats, 3:4 ratio.
\`\`\`
`,
  'images/poster_case1/output.jpg': 'not text: must never be requested',
};

const videoEntry = ({ title, badge, prompt, id, author, handle, status, date, featured = false }) =>
  featured
    ? `### No. ${id}: ${title}

![${badge}](https://img.shields.io/badge/lang-${encodeURIComponent(badge)}-blue)
![Featured](https://img.shields.io/badge/⭐-Featured-gold)

#### 📖 Description

An invented description.

#### 📝 Prompt

\`\`\`
${prompt}
\`\`\`

#### 🎬 Video

<a href="https://example.com/videos/${id}.mp4"><img src="https://example.com/thumb/${id}.jpg" width="700"></a>

**[🎬 Watch Video →](https://example.com/prompts?id=${id})**

#### 📌 Details

- **Author:** [${author}](https://x.com/${handle})
- **Source:** [Twitter Post](https://x.com/${handle}/status/${status})
- **Published:** ${date}

---
`
    : `### ${title}

![${badge}](https://img.shields.io/badge/lang-${encodeURIComponent(badge)}-blue)

> An invented description.

#### 📝 Prompt

\`\`\`
${prompt}
\`\`\`

<img src="https://example.com/thumb/${id}.jpg" width="600" alt="${title}">

**[🎬 Watch Video →](https://example.com/prompts?id=${id})**

**Author:** [${author}](https://x.com/${handle}) | **Source:** [Link](https://x.com/${handle}/status/${status}) | **Published:** ${date}

---
`;

const VIDEO_ENTRIES = [
  { id: 11, featured: true, badge: 'English', author: 'Invented Seven', handle: 'inventedseven', status: 2001, date: 'March 15, 2026',
    en: { title: 'Paper Boat In The Rain', prompt: 'A paper boat drifts down a rainy gutter, slow tracking shot.\n\n### Shot notes\nKeep the camera low.' },
    zh: { title: '雨中的纸船', prompt: '一只纸船顺着雨中的水沟漂流，缓慢跟拍镜头。\n\n### 镜头说明\n保持低机位。' } },
  { id: 12, badge: '中文', author: '虚构作者', handle: 'inventedeight', status: 2002, date: 'Nov 12, 2026',
    en: { title: 'Kite Over The Salt Flat', prompt: 'A red kite drifts over a white salt flat at noon, 8 seconds, wide shot.' },
    zh: { title: '盐滩上的风筝', prompt: '正午时分，一只红色风筝飘过白色盐滩，8秒，广角镜头。' } },
  { id: 13, badge: 'English', author: 'Invented Nine', handle: 'inventednine', status: 2003, date: 'Nov 11, 2026',
    en: { title: 'Dancer From The Reference', prompt: 'Animate @image1 so the dancer spins once, 15 seconds.' },
    zh: { title: '参考图中的舞者', prompt: '让 @图片1 中的舞者旋转一圈，15秒。' } },
  { id: 14, badge: '日本語', author: 'Invented Ten', handle: 'inventedten', status: 2004, date: 'Nov 10, 2026',
    en: { title: 'Lantern Festival', prompt: 'Lanterns rise over a river at night.' },
    zh: { title: '灯笼节', prompt: '夜晚，灯笼在河面上空升起。' } },
];

const videoReadme = (lang) =>
  `# Invented Video Prompts

## 📊 Statistics

| Metric | Count |
|--------|-------|
| Total Prompts | **4** |

## 🔥 Featured Prompts

${VIDEO_ENTRIES.filter((entry) => entry.featured).map((entry) => videoEntry({ ...entry, ...entry[lang] })).join('\n')}

## 🎬 All Prompts

${VIDEO_ENTRIES.filter((entry) => !entry.featured).map((entry) => videoEntry({ ...entry, ...entry[lang] })).join('\n')}

## 📚 More Prompts Available

### See the rest on the website

Nothing here is an entry.

## 📄 License

CC BY 4.0
`;

/** A video list in the shape of the real one: featured and plain entries, in two languages. */
export const VIDEO_LIST = {
  LICENSE: CC_BY_TEXT,
  'README.md': videoReadme('en'),
  'README_zh.md': videoReadme('zh'),
  'public/cover.png': 'not text: must never be requested',
};

export const SOURCES = [
  { id: 'gpt-image-2', adapter: 'gpt-image-2', repo: 'invented/image-prompts', commit: IMAGE_COMMIT, licence: 'CC0-1.0', modality: 'IMAGE', model: 'gpt-image-2', max_prompt_chars: 6000 },
  { id: 'seedance-2', adapter: 'seedance-2', repo: 'invented/video-prompts', commit: VIDEO_COMMIT, licence: 'CC-BY-4.0', modality: 'VIDEO', model: 'seedance-2.0', max_prompt_chars: 4000 },
];

export const MODEL_MAP = {
  generate: { 'gpt-image-2': 'gpt-image-2', 'seedance-2.0': 'seedance-2.0' },
  text: { classify: 'gpt-4o-mini', translate: 'gpt-4o-mini' },
  image_sizes: { square: '1024x1024', portrait: '1024x1536', landscape: '1536x1024' },
  video_sizes: { square: '1280x720', portrait: '720x1280', landscape: '1280x720' },
};

export const CATALOGUE = [
  { id: 'gpt-image-2', godance: { modality: 'IMAGE', pricing: { per_image_microcents: 53000 }, markup_percent: 12 } },
  { id: 'seedance-2.0', godance: { modality: 'VIDEO', pricing: { per_second_microcents: 140000 }, markup_percent: 15 } },
  { id: 'gpt-4o-mini', godance: { modality: 'TEXT', pricing: { input_microcents_per_1k: 150, output_microcents_per_1k: 600 }, markup_percent: 15 } },
];

/** A folder that is removed when the test ends. */
export async function tempDir(t, prefix = 'godance-import-') {
  const dir = await mkdtemp(path.join(os.tmpdir(), prefix));
  t.after(() => rm(dir, { recursive: true, force: true }));
  return dir;
}

/** A config folder like scripts/gallery/config, for a test to point the CLI at. */
export async function configDir(t, { sources = SOURCES, removed = [], terms = { rules: {} } } = {}) {
  const dir = await tempDir(t, 'godance-config-');
  await writeFile(path.join(dir, 'sources.json'), JSON.stringify({ sources }));
  await writeFile(path.join(dir, 'model-map.json'), JSON.stringify(MODEL_MAP));
  await writeFile(path.join(dir, 'screen-terms.json'), JSON.stringify(terms));
  await writeFile(path.join(dir, 'removal-list.json'), JSON.stringify({ removed }));
  return dir;
}

/** Every file under a folder, as [relative path, contents]. */
export async function allFiles(dir) {
  const found = [];
  async function walk(folder, prefix) {
    for (const entry of await readdir(folder, { withFileTypes: true }).catch(() => [])) {
      const relative = prefix ? `${prefix}/${entry.name}` : entry.name;
      if (entry.isDirectory()) await walk(path.join(folder, entry.name), relative);
      else found.push([relative, await readFile(path.join(folder, entry.name))]);
    }
  }
  await walk(dir, '');
  return found;
}

const box = (type, ...parts) => {
  const body = Buffer.concat(parts);
  const head = Buffer.alloc(8);
  head.writeUInt32BE(body.length + 8, 0);
  head.write(type, 4, 'latin1');
  return Buffer.concat([head, body]);
};

/** The smallest MP4 that states a frame size and a length. Not playable; enough to be measured. */
export function tinyMp4({ width = 1280, height = 720, seconds = 5 } = {}) {
  const mvhd = Buffer.alloc(100);
  mvhd.writeUInt32BE(1000, 12); // timescale
  mvhd.writeUInt32BE(seconds * 1000, 16); // duration
  const sound = Buffer.alloc(84); // a track with no frame size, like an audio track
  const picture = Buffer.alloc(84);
  picture.writeUInt32BE(width * 65536, 76);
  picture.writeUInt32BE(height * 65536, 80);
  return Buffer.concat([
    box('ftyp', Buffer.from('isom0000isom')),
    box('moov', box('mvhd', mvhd), box('trak', box('tkhd', sound)), box('trak', box('tkhd', picture))),
  ]);
}

/** A small PNG, made with sharp. */
export async function tinyPng({ width = 96, height = 64 } = {}) {
  const { default: sharp } = await import('sharp');
  return sharp({ create: { width, height, channels: 3, background: { r: 200, g: 120, b: 40 } } }).png().toBuffer();
}

const json = (status, body, headers = {}) => new Response(body === null ? null : JSON.stringify(body), { status, headers: { 'content-type': 'application/json', ...headers } });

/**
 * A stand-in for everything the tool talks to: the Godance API at API_BASE,
 * the storage host its output links point at, and the host the lists are
 * read from. Records every request, so a test can say what was and was not
 * sent.
 *
 * @param {object} [options]
 * @param {Record<string, Record<string, string>>} [options.lists] repo → { path: text }
 * @param {object[]} [options.models] The catalogue.
 * @param {(job: object) => object} [options.onPoll] Change how a job answers a poll.
 * @param {(call: object) => Response | 'network-error' | undefined} [options.onSubmit] Intercept a submission.
 * @param {(messages: object[]) => string} [options.onChat] The reply to a chat completion.
 * @param {number} [options.pollsUntilDone] How many polls a job takes to finish.
 */
export function fakeWorld({ lists = {}, models = CATALOGUE, onPoll, onSubmit, onChat, pollsUntilDone = 2, imageCost = 6, videoCostPerSecond = 17 } = {}) {
  const world = {
    requests: [], // every request: { method, url, authorization, body }
    submissions: [], // accepted image and video jobs
    chats: [],
    jobs: new Map(),
    inFlight: 0,
    mostInFlight: 0,
    png: null,
  };

  world.fetch = async (input, init = {}) => {
    const url = new URL(String(input));
    const method = init.method ?? 'GET';
    const authorization = init.headers?.Authorization ?? init.headers?.authorization ?? null;
    const body = init.body ? JSON.parse(init.body) : null;
    world.requests.push({ method, url: url.href, authorization, body });

    // The host the lists are read from
    if (url.hostname === 'api.github.com') {
      const match = /^\/repos\/([^/]+\/[^/]+)\/git\/trees\/([0-9a-f]+)$/.exec(url.pathname);
      const files = match && lists[match[1]];
      if (!files) return json(404, { message: 'Not Found' });
      return json(200, { truncated: false, tree: Object.keys(files).map((file) => ({ path: file, type: 'blob' })) });
    }
    if (url.hostname === 'raw.githubusercontent.com') {
      const [, owner, name, , ...rest] = url.pathname.split('/');
      const text = lists[`${owner}/${name}`]?.[decodeURIComponent(rest.join('/'))];
      return text === undefined ? new Response('Not Found', { status: 404 }) : new Response(text, { status: 200 });
    }

    // Storage: where output links point. It is not the API.
    if (url.hostname === 'storage.godance.test') {
      const job = world.jobs.get(url.pathname.split('/').at(-1).split('.')[0]);
      if (!job) return new Response('gone', { status: 403 });
      if (job.modality === 'VIDEO') return new Response(tinyMp4({ seconds: job.seconds }), { status: 200, headers: { 'content-type': 'video/mp4' } });
      world.png ??= await tinyPng();
      return new Response(world.png, { status: 200, headers: { 'content-type': 'image/png' } });
    }

    if (url.origin !== API_BASE) throw new Error(`the test world has no host ${url.origin}`);

    if (method === 'GET' && url.pathname === '/v1/models') return json(200, { object: 'list', data: models });
    if (authorization !== `Bearer ${FAKE_KEY}`) return json(401, { title: 'Unauthorized', status: 401, detail: 'unknown key' });

    if (method === 'POST' && url.pathname === '/v1/chat/completions') {
      world.chats.push(body);
      const text = onChat ? onChat(body.messages) : '这是一段中文译文，用于测试。';
      return json(200, { choices: [{ message: { role: 'assistant', content: text } }], usage: { prompt_tokens: 100, completion_tokens: 50 } });
    }

    const submit = /^\/v1\/(images|videos)\/generations$/.exec(url.pathname);
    if (method === 'POST' && submit) {
      const call = { kind: submit[1], body, attempt: world.requests.filter((r) => r.method === 'POST' && r.url === url.href).length };
      const intercepted = onSubmit?.(call, world);
      if (intercepted === 'network-error') throw new TypeError('fetch failed');
      if (intercepted instanceof Response) return intercepted;
      const id = `job-${world.jobs.size + 1}`;
      const modality = submit[1] === 'videos' ? 'VIDEO' : 'IMAGE';
      world.jobs.set(id, { id, modality, model: body.model, seconds: body.seconds, polls: 0, status: 'QUEUED',
        cost: modality === 'VIDEO' ? videoCostPerSecond * body.seconds : imageCost });
      world.submissions.push({ id, ...call });
      world.inFlight++;
      world.mostInFlight = Math.max(world.mostInFlight, world.inFlight);
      return json(202, { job_id: id, status: 'QUEUED', poll_url: `/v1/jobs/${id}` });
    }

    const poll = /^\/v1\/jobs\/([^/]+)$/.exec(url.pathname);
    if (method === 'GET' && poll) {
      const job = world.jobs.get(poll[1]);
      if (!job) return json(404, { title: 'Not Found', status: 404, detail: 'no such job' });
      job.polls++;
      if (job.status !== 'DONE' && job.status !== 'FAILED' && job.polls >= pollsUntilDone) {
        job.status = 'DONE';
        world.inFlight--;
      }
      const view = {
        id: job.id, status: job.status === 'QUEUED' && job.polls > 0 ? 'RUNNING' : job.status, modality: job.modality, model: job.model,
        estimatedCostCents: job.cost, actualCostCents: job.status === 'DONE' ? job.cost : null,
        outputUrl: job.status === 'DONE' ? `https://storage.godance.test/out/${job.id}.${job.modality === 'VIDEO' ? 'mp4' : 'png'}?signature=abc` : null,
        errorMessage: null,
      };
      return json(200, onPoll ? onPoll(view, job, world) : view);
    }
    return json(404, { title: 'Not Found', status: 404 });
  };

  /** Requests that carried the key to a host other than the API. */
  world.keyLeaks = () => world.requests.filter((request) => request.authorization && !request.url.startsWith(API_BASE));
  return world;
}

/** Sleeping costs nothing in a test. */
export const noSleep = async () => {};

export async function writeFiles(dir, files) {
  for (const [name, contents] of Object.entries(files)) {
    await mkdir(path.dirname(path.join(dir, name)), { recursive: true });
    await writeFile(path.join(dir, name), typeof contents === 'string' ? contents : JSON.stringify(contents, null, 2));
  }
}
