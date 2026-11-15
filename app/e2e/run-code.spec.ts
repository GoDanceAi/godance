import { expect, test } from '@playwright/test';
import {
  ASSISTANT_PROMPT,
  ASSISTANT_PROMPT_VERSION,
  assistantPrompt,
  PROMPT_CONDITIONS,
  promptConditions,
  promptRecipeJson,
  promptWording,
  type PromptRecipe,
} from '../src/lib/assistant-prompt';
import { capCents, capState, capText, dayOf, expiryOf } from '../src/lib/key-limits';
import { n8nInputsNote, n8nRow, n8nSteps, n8nTemplate } from '../src/lib/n8n';
import {
  bodyFor,
  codeFor,
  createPath,
  fileIdPlaceholder,
  pythonLiteral,
  uploadCode,
  VIDEO_CREATE,
  type CodeLanguage,
  type CodeRequest,
} from '../src/lib/run-code';

// Round 12 brief 03: the code "Run at scale" writes, and the prompt it hands
// to an AI assistant. No page here: the modules import nothing, which is
// also what lets the marketing site show the same text.

const API = 'https://api.godance.ai';
const LANGUAGES: CodeLanguage[] = ['curl', 'python', 'java'];

/** A prompt with everything a shell, a Python string and a Java text block could trip on. */
const AWKWARD = 'It\'s "quoted", with a back\\slash, a $HOME, a `tick`, three """ quotes,\na second line, a tab\there, and 中文 — é.';

const image = (body: Record<string, unknown>, mode: CodeRequest['mode'] = 'poll'): CodeRequest =>
  ({ apiBase: API, kind: 'image', body, mode });
const video = (body: Record<string, unknown>): CodeRequest => ({ apiBase: API, kind: 'video', body, mode: 'poll' });

const PLAIN = { model: 'gpt-image-2', prompt: 'A red mug', size: '1024x1024', n: 2, async: true };
const RECIPE = {
  model: 'gpt-image-2',
  recipe: { version_id: '5ec10003-0000-4000-8000-000000000001', variables: { color: 'red', surface: 'kitchen shelf' } },
  size: '1024x1024',
  n: 1,
  async: true,
};
const EDIT = {
  model: 'gpt-image-2', prompt: 'The mug on a marble counter', size: '1024x1024', async: true,
  images: [{ file_id: 'f11e0000-0000-4000-8000-000000000001' }],
};
const VIDEO = { model: 'seedance-2.0', prompt: 'Waves at dawn', size: '1280x720', seconds: 5 };

const only = (language: CodeLanguage, request: CodeRequest): string => {
  const snippets = codeFor(language, request);
  expect(snippets).toHaveLength(1);
  return snippets[0].code;
};

/** The JSON a curl command sends: its -d word, unquoted as a shell would. */
function curlBody(code: string): unknown {
  const word = /-d '([\s\S]*)'$/.exec(code)?.[1];
  expect(word, 'the command ends with a quoted -d').toBeDefined();
  return JSON.parse((word as string).replaceAll(`'\\''`, "'"));
}

/** The JSON a Java snippet sends: its text block, with the indent and the escapes undone as javac does. */
function javaBody(code: string): unknown {
  const block = /String request = """\n([\s\S]*?)\n\s*""";/.exec(code)?.[1];
  expect(block, 'the class holds the request in a text block').toBeDefined();
  const lines = (block as string).split('\n').map((line) => line.replace(/^ {16}/, ''));
  return JSON.parse(lines.join('\n').replaceAll('\\\\', '\\'));
}

/**
 * The value of a Python literal made by pythonLiteral: it is JSON but for
 * True, False, None and a comma after the last item, none of which can sit
 * outside a string in any other way.
 */
function pythonValue(literal: string): unknown {
  let json = '';
  let inString = false;
  for (let i = 0; i < literal.length; i += 1) {
    const ch = literal[i];
    if (inString) {
      json += ch;
      if (ch === '\\') { json += literal[i + 1]; i += 1; } else if (ch === '"') inString = false;
    } else if (ch === '"') {
      inString = true;
      json += ch;
    } else if (literal.startsWith('True', i)) { json += 'true'; i += 3; }
    else if (literal.startsWith('False', i)) { json += 'false'; i += 4; }
    else if (literal.startsWith('None', i)) { json += 'null'; i += 3; }
    else json += ch;
  }
  return JSON.parse(json.replace(/,(\s*[\]}])/g, '$1'));
}

/** The body a Python snippet posts: the literal after `body=`. */
function pythonBody(code: string): unknown {
  const literal = /body=(\{[\s\S]*?\n {4}\}),\n {4}cast_to=/.exec(code)?.[1];
  expect(literal, 'the script posts a body').toBeDefined();
  return pythonValue(literal as string);
}

test.describe('the request as code', () => {
  test('an image request that polls asks for a job in all three languages, and reads it until it ends', () => {
    const request = image(PLAIN, 'poll');

    const [start, check] = codeFor('curl', request);
    expect(start.title).toBe('Start the job');
    expect(start.code).toContain(`curl ${API}/v1/images/generations \\`);
    expect(start.code).toContain('-H "Authorization: Bearer $GODANCE_API_KEY"');
    expect(curlBody(start.code)).toEqual(PLAIN);
    expect(check.title).toBe('Check on it');
    expect(check.code).toBe(`curl ${API}/v1/jobs/$JOB_ID \\\n  -H "Authorization: Bearer $GODANCE_API_KEY"`);
    expect(check.note).toContain('JOB_ID is the job_id');

    const python = only('python', request);
    expect(python).toContain(`base_url="${API}/v1"`);
    expect(python).toContain('api_key=os.environ["GODANCE_API_KEY"]');
    expect(python).toContain('max_retries=0');
    expect(python).toContain('client.post(\n    "/images/generations",');
    expect(pythonBody(python)).toEqual(PLAIN);
    expect(python).toContain(`job = client.get(f"/jobs/{started['job_id']}", cast_to=httpx.Response).json()`);
    expect(python).toContain('if job["status"] in ("DONE", "FAILED", "CANCELLED"):');
    expect(python).toContain('for output in job["outputs"]:');

    const java = only('java', request);
    expect(java).toContain('public class RunOnGodance {');
    expect(java).toContain(`.baseUrl("${API}")`);
    expect(java).toContain('.defaultHeader("Authorization", "Bearer " + System.getenv("GODANCE_API_KEY"))');
    expect(java).toContain('godance.post().uri("/v1/images/generations")');
    expect(javaBody(java)).toEqual(PLAIN);
    expect(java).toContain('godance.get().uri("/v1/jobs/{id}", started.get("job_id"))');
    expect(java).toContain('throws InterruptedException');
  });

  test('an image request that waits sends no "async": the plain one is the SDK\'s own call', () => {
    const request = image(PLAIN, 'wait');
    const { async: _dropped, ...waiting } = PLAIN;

    const curl = codeFor('curl', request);
    expect(curl.map((snippet) => snippet.title)).toEqual(['Send the request']);
    expect(curlBody(curl[0].code)).toEqual(waiting);
    expect(curl[0].note).toContain('synchronous image responses');

    const python = only('python', request);
    expect(python).toContain([
      'result = client.images.generate(',
      '    model="gpt-image-2",',
      '    prompt="A red mug",',
      '    size="1024x1024",',
      '    n=2,',
      ')',
    ].join('\n'));
    expect(python).not.toContain('async');
    expect(python).not.toContain('httpx');
    expect(python).toContain('for image in result.data or []:\n    print(image.url)');

    const java = only('java', request);
    expect(javaBody(java)).toEqual(waiting);
    expect(java).toContain('if (answer.get("data") instanceof List<?> images) {');
    // A synchronous answer can hold the image itself, which is more than WebClient buffers by default.
    expect(java).toContain('.codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))');
    expect(java).not.toContain('InterruptedException');
  });

  test('what the SDK has no argument for goes in extra_body', () => {
    const python = only('python', image({ ...PLAIN, quality: 'high', gallery_recipe: 'koi-pond', webhook_url: 'https://example.test/hook' }, 'wait'));

    expect(python).toContain('    quality="high",');
    expect(python).toContain('    extra_body={\n        "gallery_recipe": "koi-pond",\n        "webhook_url": "https://example.test/hook",\n    },');
  });

  test('a video is always a job, on the video route, whatever was asked', () => {
    for (const mode of ['poll', 'wait'] as const) {
      const request: CodeRequest = { ...video(VIDEO), mode };
      const curl = codeFor('curl', request);
      expect(curl.map((snippet) => snippet.title)).toEqual(['Start the job', 'Check on it']);
      expect(curl[0].code).toContain(`curl ${API}${VIDEO_CREATE.path} \\`);
      expect(curlBody(curl[0].code)).toEqual(VIDEO);

      const python = only('python', request);
      expect(python).toContain('client.post(\n    "/videos/generations",');
      expect(pythonBody(python)).toEqual(VIDEO);
      expect(python).toContain(`started['${VIDEO_CREATE.jobIdField}']`);

      const java = only('java', request);
      expect(java).toContain(`godance.post().uri("${VIDEO_CREATE.path}")`);
      expect(javaBody(java)).toEqual(VIDEO);
    }
    // The one constant to change when POST /v1/videos is live.
    expect(VIDEO_CREATE).toEqual({ path: '/v1/videos/generations', jobIdField: 'job_id' });
    expect(createPath('video', VIDEO)).toBe(VIDEO_CREATE.path);
  });

  test('a recipe run carries `recipe` and its variables, and no prompt', () => {
    for (const mode of ['poll', 'wait'] as const) {
      const request = image(RECIPE, mode);
      const expected = mode === 'poll' ? RECIPE : (({ async: _a, ...rest }) => rest)(RECIPE);

      expect(curlBody(codeFor('curl', request)[0].code)).toEqual(expected);
      const python = only('python', request);
      // The SDK's images.generate needs a prompt, so a recipe is posted as it is, waiting or not.
      expect(python).toContain('client.post(\n    "/images/generations",');
      expect(python).not.toContain('client.images.generate');
      expect(pythonBody(python)).toEqual(expected);
      expect(javaBody(only('java', request))).toEqual(expected);
    }
    expect(only('python', image(RECIPE, 'wait'))).toContain('for image in answer.get("data") or []:\n    print(image["url"])');
  });

  test('an edit goes to the edits route with its inputs', () => {
    expect(createPath('image', EDIT)).toBe('/v1/images/edits');
    expect(createPath('image', PLAIN)).toBe('/v1/images/generations');
    // A recipe that names its own inputs goes to the generations route: the API places them.
    expect(createPath('image', { model: 'm', recipe: { version_id: 'v', variables: {}, inputs: { photo: { file_id: 'FILE_ID' } } } }))
      .toBe('/v1/images/generations');

    for (const mode of ['poll', 'wait'] as const) {
      const request = image(EDIT, mode);
      const expected = mode === 'poll' ? EDIT : (({ async: _a, ...rest }) => rest)(EDIT);
      const curl = codeFor('curl', request)[0].code;
      expect(curl).toContain(`curl ${API}/v1/images/edits \\`);
      expect(curlBody(curl)).toEqual(expected);
      const python = only('python', request);
      expect(python).toContain('client.post(\n    "/images/edits",');
      expect(pythonBody(python)).toEqual(expected);
      const java = only('java', request);
      expect(java).toContain('godance.post().uri("/v1/images/edits")');
      expect(javaBody(java)).toEqual(expected);
    }
  });

  test('`async` is set for a poll and dropped for a wait, wherever the body had it', () => {
    expect(bodyFor(image({ model: 'm', prompt: 'p' }, 'poll'))).toEqual({ model: 'm', prompt: 'p', async: true });
    expect(Object.keys(bodyFor(image({ model: 'm', async: true, prompt: 'p' }, 'poll')))).toEqual(['model', 'async', 'prompt']);
    expect(bodyFor(image({ model: 'm', prompt: 'p', async: true }, 'wait'))).toEqual({ model: 'm', prompt: 'p' });
    expect(bodyFor(video({ model: 'm', prompt: 'p', async: true }))).toEqual({ model: 'm', prompt: 'p' });
  });

  test('a prompt full of quotes, backslashes and line breaks comes out of each language unchanged', () => {
    const body = { model: 'gpt-image-2', prompt: AWKWARD, recipe_note: { nested: [AWKWARD, true, false, null, 1.5] }, async: true };
    const request = image(body, 'poll');

    expect(curlBody(codeFor('curl', request)[0].code)).toEqual(body);
    expect(pythonBody(only('python', request))).toEqual(body);
    expect(javaBody(only('java', request))).toEqual(body);
    // Nothing can end the Java text block early.
    const java = only('java', request);
    expect(java.match(/"""/g)).toHaveLength(2);
    // The plain call takes the same prompt as a Python string.
    const plain = only('python', image({ model: 'gpt-image-2', prompt: AWKWARD }, 'wait'));
    expect(pythonValue(/prompt=(".*"),\n/.exec(plain)?.[1] ?? '')).toBe(AWKWARD);
  });

  test('a Python literal is JSON with Python\'s own words for true, false and null', () => {
    expect(pythonLiteral(true)).toBe('True');
    expect(pythonLiteral(false)).toBe('False');
    expect(pythonLiteral(null)).toBe('None');
    expect(pythonLiteral('True or False')).toBe('"True or False"');
    expect(pythonLiteral([])).toBe('[]');
    expect(pythonLiteral({})).toBe('{}');
    expect(pythonLiteral({ a: [1, { b: null }], c: 'x' })).toBe(
      '{\n    "a": [\n        1,\n        {\n            "b": None,\n        },\n    ],\n    "c": "x",\n}');
  });

  test('no code ever holds a key: only the variable it is read from', () => {
    for (const language of LANGUAGES) {
      for (const request of [image(PLAIN, 'poll'), image(PLAIN, 'wait'), image(RECIPE), image(EDIT), video(VIDEO)]) {
        for (const snippet of codeFor(language, request)) {
          expect(snippet.code).not.toMatch(/gd_live_/);
          expect(snippet.code).toContain('GODANCE_API_KEY');
        }
      }
      const upload = uploadCode(language, API, 'product photo.png');
      expect(upload.code).not.toMatch(/gd_live_/);
      expect(upload.code).toContain('GODANCE_API_KEY');
    }
  });

  test('the upload of an input image, and the name that stands for its id', () => {
    expect(uploadCode('curl', API, 'product photo.png').code).toBe(
      `curl ${API}/v1/files \\\n  -H "Authorization: Bearer $GODANCE_API_KEY" \\\n  -F "file=@product-photo.png"`);
    expect(uploadCode('python', API, 'product photo.png').code)
      .toContain('uploaded = client.files.create(file=open("product-photo.png", "rb"), purpose="generation_input")');
    expect(uploadCode('java', API, 'product photo.png').code).toContain('godance.post().uri("/v1/files")');
    // A slot's name never reaches a shell or a compiler as it is.
    expect(uploadCode('curl', API, '$(rm -rf ~)";`x`.png').code).toContain('-F "file=@rm--rf-x-.png"');
    expect(uploadCode('curl', API, '///').code).toContain('-F "file=@image.png"');

    expect(fileIdPlaceholder(0, 1)).toBe('FILE_ID');
    expect([0, 1].map((i) => fileIdPlaceholder(i, 2))).toEqual(['FILE_ID_1', 'FILE_ID_2']);
  });

  test('a base address with a trailing slash is the same address', () => {
    expect(codeFor('curl', image(PLAIN))[0].code).toBe(codeFor('curl', { ...image(PLAIN), apiBase: `${API}/` })[0].code);
    expect(only('python', { ...image(PLAIN), apiBase: `${API}/` })).toContain(`base_url="${API}/v1"`);
  });
});

test.describe('the prompt for an AI assistant', () => {
  const recipe: PromptRecipe = {
    title: 'Mug on a kitchen shelf',
    modality: 'IMAGE',
    template: 'A {{color}} mug on a {{surface}}, soft morning light',
    variables: [{ name: 'color', default: 'red' }, { name: 'surface', default: 'kitchen shelf' }],
    inputs: [],
    model: 'gpt-image-2',
    params: { size: '1024x1024', n: 1 },
    version_id: '5ec10003-0000-4000-8000-000000000001',
  };
  const api = { base: API, createPath: '/v1/images/generations' };

  test('is the one constant: its blocks settled for the recipe, its three markers filled in, nothing else changed', () => {
    const prompt = assistantPrompt(recipe, api);

    expect(prompt).toBe(promptWording(promptConditions(recipe))
      .replaceAll('[[API_BASE]]', API)
      .replace('[[CREATE_PATH]]', '/v1/images/generations')
      .replace('[[RECIPE_JSON]]', () => promptRecipeJson(recipe)));
    expect(prompt).not.toContain('[[');
    expect(prompt).toContain(`API base URL: ${API}/v1`);
    expect(prompt).toContain(`POST ${API}/v1/images/generations`);
    expect(ASSISTANT_PROMPT_VERSION).toMatch(/^\d{4}-\d{2}-\d{2}\.\d+$/);
  });

  test('every block of the wording is closed and is about something a recipe can be', () => {
    const opens = ASSISTANT_PROMPT.match(/^\[\[IF [^\]]*\]\]$/gm) ?? [];
    const ends = ASSISTANT_PROMPT.match(/^\[\[END\]\]$/gm) ?? [];
    expect(opens.length).toBeGreaterThan(0);
    expect(ends).toHaveLength(opens.length);
    for (const open of opens) {
      for (const word of open.slice('[[IF '.length, -2).trim().split(/\s+/)) expect(PROMPT_CONDITIONS, open).toContain(word);
    }
    // With every block kept or every block dropped, the three markers are all that is left in brackets.
    for (const kept of [true, false]) {
      const conditions = Object.fromEntries(PROMPT_CONDITIONS.map((name) => [name, kept]));
      const left = [...new Set(promptWording(conditions).match(/\[\[[^\]]*\]\]/g))].sort();
      expect(left).toEqual(['[[API_BASE]]', '[[CREATE_PATH]]', '[[RECIPE_JSON]]']);
    }
    // A word that is no condition keeps nothing, rather than everything.
    expect(promptWording({}, 'a\n[[IF SOMETHING_ELSE]]\nb\n[[END]]\nc')).toBe('a\nc');
  });

  test('each block is in the prompt only for the recipes it is about', () => {
    // One of the user's own image recipes, without inputs.
    const saved = assistantPrompt(recipe, api);
    expect(saved).toContain('"version_id" names this recipe on Godance. Send "recipe"');
    expect(saved).not.toContain('Fill the template in yourself');
    expect(saved).not.toContain('gallery_recipe');
    expect(saved).not.toContain('/v1/files');
    expect(saved).toContain('3. An image request answers in one of two ways');
    expect(saved).not.toContain('A video request always answers 202');

    // The same recipe with an input slot: the file goes inside "recipe".
    const slot = { slot: 'product photo', role: 'image', hint: '' };
    const withSlot = assistantPrompt({ ...recipe, inputs: [slot] }, api);
    expect(withSlot).toContain('Upload each file with POST /v1/files');
    expect(withSlot).toContain('Give each slot its file inside "recipe"');
    expect(withSlot).not.toContain('Each input goes in the field its "role" names');

    // A gallery video recipe with a first frame: the script fills the template in and places the file by its role.
    const gallery = assistantPrompt(
      { ...recipe, modality: 'VIDEO', version_id: undefined, gallery_recipe: 'paper-boat', inputs: [{ ...slot, role: 'first_frame' }] },
      { base: API, createPath: '/v1/videos/generations' });
    expect(gallery).toContain('Fill the template in yourself, and send the result as "prompt".');
    expect(gallery).toContain('Send "gallery_recipe" unchanged with every request.');
    expect(gallery).toContain('Upload each file with POST /v1/files');
    expect(gallery).toContain('Each input goes in the field its "role" names');
    expect(gallery).not.toContain('inside "recipe"');
    expect(gallery).not.toContain('"version_id" names this recipe');
    expect(gallery).toContain('3. A video request always answers 202');
    expect(gallery).not.toContain('An image request answers in one of two ways');

    for (const prompt of [saved, withSlot, gallery]) {
      // The rules are numbered 1 to 8 whichever blocks were kept, and no block leaves a gap behind.
      expect(prompt.match(/^\d+\. /gm)).toEqual(['1. ', '2. ', '3. ', '4. ', '5. ', '6. ', '7. ', '8. ']);
      expect(prompt).not.toMatch(/\n\n\n/);
      expect(prompt).not.toContain('[[');
    }
  });

  test('the wording holds what the brief asks of it', () => {
    // The goal, the two links, and each rule the script must follow.
    expect(ASSISTANT_PROMPT).toContain('once for every row of a CSV file');
    expect(ASSISTANT_PROMPT).toContain('https://godance.ai/llms-full.txt');
    expect(ASSISTANT_PROMPT).toContain('https://godance.ai/docs');
    expect(ASSISTANT_PROMPT).toContain('Read the API key from the GODANCE_API_KEY environment variable. Never write the key into the code');
    expect(ASSISTANT_PROMPT).toContain('Use the OpenAI Python SDK (pip install openai) with base_url');
    expect(ASSISTANT_PROMPT).toContain('synchronous image responses');
    expect(ASSISTANT_PROMPT).toContain('poll GET /v1/jobs/{id}');
    expect(ASSISTANT_PROMPT).toContain('Estimate the cost first. Call POST /v1/estimates');
    expect(ASSISTANT_PROMPT).toContain('Stop at once on HTTP 402');
    expect(ASSISTANT_PROMPT).toContain('to the results CSV');
    // Each marker is there to be filled in.
    for (const marker of ['[[API_BASE]]', '[[CREATE_PATH]]', '[[RECIPE_JSON]]']) expect(ASSISTANT_PROMPT).toContain(marker);
    // It names no key, and it is the same for every account.
    expect(ASSISTANT_PROMPT).not.toMatch(/gd_live_/);
  });

  test('the recipe is JSON with the same keys in the same order, and the optional ones only when it has them', () => {
    const json = /The recipe, as JSON:\n\n([\s\S]*?)\n\nHow to read it:/.exec(assistantPrompt(recipe, api))?.[1] ?? '';
    expect(Object.keys(JSON.parse(json) as object)).toEqual(
      ['title', 'modality', 'template', 'variables', 'inputs', 'model', 'params', 'version_id']);
    expect(JSON.parse(json)).toEqual(recipe);

    const gallery = JSON.parse(promptRecipeJson({ ...recipe, title: undefined, version_id: undefined, gallery_recipe: 'koi-pond' })) as object;
    expect(Object.keys(gallery)).toEqual(['modality', 'template', 'variables', 'inputs', 'model', 'params', 'gallery_recipe']);
    // Only what the recipe type names is carried: anything else on the object is left behind.
    const extra = { ...recipe, email: 'ada@example.com', balance_cents: 1234 } as PromptRecipe;
    expect(promptRecipeJson(extra)).not.toContain('ada@example.com');
    expect(promptRecipeJson(extra)).not.toContain('balance_cents');
    expect(promptRecipeJson({ ...recipe, variables: [{ name: 'color', default: 'red', secret: 'x' } as PromptRecipe['variables'][number]] }))
      .not.toContain('secret');
  });

  test('a recipe that holds a marker, a block, or what a replacement would read as a pattern, is carried as written', () => {
    const tricky = {
      ...recipe,
      template: 'Costs $5, $& and $1; [[API_BASE]] and [[RECIPE_JSON]] stay as typed\n[[IF VIDEO]]\nnot a block\n[[END]]\n',
    };
    const prompt = assistantPrompt(tricky, api);

    const json = /The recipe, as JSON:\n\n([\s\S]*?)\n\nHow to read it:/.exec(prompt)?.[1] ?? '';
    expect((JSON.parse(json) as PromptRecipe).template).toBe(tricky.template);
    // The base address was filled in where the wording has it, and nowhere in the recipe.
    expect(prompt.split(API)).toHaveLength(ASSISTANT_PROMPT.split('[[API_BASE]]').length);
  });
});

test.describe('the n8n templates', () => {
  test('one for images and one for videos, as raw files on the main branch', () => {
    expect(n8nTemplate('IMAGE')).toMatchObject({
      file: 'godance-sheet-to-images.json',
      url: 'https://raw.githubusercontent.com/GoDanceAi/godance/main/integrations/n8n/godance-sheet-to-images.json',
    });
    expect(n8nTemplate('VIDEO')).toMatchObject({
      file: 'godance-sheet-to-videos.json',
      url: 'https://raw.githubusercontent.com/GoDanceAi/godance/main/integrations/n8n/godance-sheet-to-videos.json',
    });
  });

  test('four steps: the key as a credential, the import, the sheet, the run', () => {
    const steps = n8nSteps(n8nTemplate('VIDEO'));
    expect(steps).toHaveLength(4);
    expect(steps[0]).toContain('"Header Auth" and name it "Godance API"');
    expect(steps[1]).toContain('Download godance-sheet-to-videos.json');
    expect(steps[1]).toContain('Import from File');
    expect(steps[2]).toContain('Google Sheets');
    expect(steps[3]).toContain('Execute workflow');
  });

  test('a row of the sheet: the columns the template reads, from the recipe', () => {
    expect(n8nRow({ modality: 'IMAGE', model: 'gpt-image-2', params: { size: '1024x1024', n: 2 }, variables: [] })).toEqual([
      { column: 'prompt', value: 'the prompt' },
      { column: 'model', value: 'gpt-image-2' },
      { column: 'size', value: '1024x1024' },
    ]);
    expect(n8nRow({ modality: 'VIDEO', model: 'seedance-2.0', params: { seconds: 5 }, variables: [{ name: 'weather' }] })).toEqual([
      { column: 'prompt', value: 'the template, with its variables filled in for that row' },
      { column: 'model', value: 'seedance-2.0' },
      { column: 'seconds', value: '5' },
    ]);
  });

  test('what the templates cannot send is said, not hidden', () => {
    expect(n8nInputsNote('IMAGE', [])).toBeNull();
    expect(n8nInputsNote('IMAGE', [{ role: 'image' }])).toContain('sends a prompt alone');
    expect(n8nInputsNote('VIDEO', [{ role: 'first_frame' }])).toContain('first_frame_file_id');
    expect(n8nInputsNote('VIDEO', [{ role: 'first_frame' }, { role: 'last_frame' }])).toContain('takes a first frame only');
  });
});

test.describe("a key's limits, as typed", () => {
  test('a cap is dollars, sent in whole cents', () => {
    expect(capCents('5')).toBe(500);
    expect(capCents('5.5')).toBe(550);
    expect(capCents(' $5.00 ')).toBe(500);
    expect(capCents('0.01')).toBe(1);
    expect(capCents('1234.56')).toBe(123456);
    // Empty is no cap, which is not the same as wrong.
    expect(capCents('')).toBeNull();
    expect(capCents('   ')).toBeNull();
    for (const wrong of ['0', '0.00', '-5', '5.001', '5,00', 'five', '1e3', '.5', '5.', '99999999999']) {
      expect(capCents(wrong), wrong).toBe('invalid');
    }
  });

  test('a cap reads back as it was typed', () => {
    expect(capText(500)).toBe('5.00');
    expect(capText(1)).toBe('0.01');
    expect(capText(123456)).toBe('1234.56');
    expect(capText(null)).toBe('');
    expect(capText(undefined)).toBe('');
    for (const cents of [1, 99, 100, 250, 1999, 500000]) expect(capCents(capText(cents))).toBe(cents);
  });

  test('an expiry is the end of the day that was picked, and never a day already over', () => {
    const now = new Date(2026, 10, 15, 12, 0, 0).getTime();   // 15 Nov 2026, noon, wherever this runs

    const expiry = expiryOf('2026-12-31', now);
    expect(typeof expiry).toBe('string');
    const at = new Date(expiry as string);
    expect([at.getFullYear(), at.getMonth() + 1, at.getDate(), at.getHours(), at.getMinutes(), at.getSeconds()])
      .toEqual([2026, 12, 31, 23, 59, 59]);
    // In UTC and to the second, as the API keeps it.
    expect(expiry).toMatch(/^\d{4}-\d{2}-\d{2}T\d{2}:\d{2}:\d{2}Z$/);
    expect(dayOf(expiry as string)).toBe('2026-12-31');

    // Today still has hours left in it; yesterday has none.
    expect(expiryOf('2026-11-15', now)).not.toBe('invalid');
    expect(expiryOf('2026-11-14', now)).toBe('invalid');
    expect(expiryOf('', now)).toBeNull();
    for (const wrong of ['31/12/2026', '2026-12', 'soon']) expect(expiryOf(wrong, now), wrong).toBe('invalid');
    expect(dayOf(null)).toBe('');
    expect(dayOf('not a date')).toBe('');
  });

  test('spend is shown as near its cap from 80%, and over once it is reached', () => {
    expect(capState(0, 500)).toBe('ok');
    expect(capState(399, 500)).toBe('ok');
    expect(capState(400, 500)).toBe('near');
    expect(capState(499, 500)).toBe('near');
    expect(capState(500, 500)).toBe('over');
    expect(capState(900, 500)).toBe('over');
    expect(capState(900, null)).toBe('ok');
    expect(capState(900, undefined)).toBe('ok');
  });
});
