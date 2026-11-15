// A Godance request as code: curl, Python (the OpenAI SDK) and Java (Spring's
// WebClient), all three written from the one request object the app sends.
// The key is always the GODANCE_API_KEY environment variable: no code made
// here ever holds a key.
//
// The marketing site shows the same code on a gallery recipe's page and
// imports this file to do it (marketing/src/components/gallery/run-at-scale.ts).
// So this file imports nothing: nothing of the app, of the browser or of Vite.

export type CodeLanguage = 'curl' | 'python' | 'java';

export const CODE_LANGUAGES: { id: CodeLanguage; label: string }[] = [
  { id: 'curl', label: 'curl' },
  { id: 'python', label: 'Python (OpenAI SDK)' },
  { id: 'java', label: 'Java (Spring WebClient)' },
];

/**
 * How an image request is answered. poll: it asks for a job (`"async": true`)
 * and reads the job until it ends, which works with any key. wait: it sends
 * no `async`, and a key set to synchronous image responses answers with the
 * images. A video is always a job.
 */
export type AnswerMode = 'poll' | 'wait';

/** The environment variable every snippet reads the key from. */
export const API_KEY_ENV = 'GODANCE_API_KEY';
export const API_KEY_VAR = `$${API_KEY_ENV}`;
export const JOB_ID_VAR = '$JOB_ID';

export const IMAGE_CREATE_PATH = '/v1/images/generations';
export const IMAGE_EDIT_PATH = '/v1/images/edits';
export const JOBS_PATH = '/v1/jobs';
export const FILES_PATH = '/v1/files';

/**
 * Where generated code sends a video request, and the field of the answer
 * that holds the job's id. Today that is POST /v1/videos/generations. The
 * OpenAI-shaped POST /v1/videos (round 12 brief 04) is not live on this
 * branch: when it is, this constant is the one line to change (its answer
 * names the job `id`; check the body's `seconds` against that route too).
 * The Studio's own requests do not read this: they go where lib/studio.ts says.
 */
export const VIDEO_CREATE = { path: '/v1/videos/generations', jobIdField: 'job_id' } as const;

/** One request, as every language is written from it. */
export interface CodeRequest {
  /** The API's origin, without /v1: https://api.godance.ai */
  apiBase: string;
  kind: 'image' | 'video';
  /** The body as the app sends it. `async` is set or dropped here to fit `mode`. */
  body: Record<string, unknown>;
  mode: AnswerMode;
}

/** A block of code with what it is, and a line to read with it. */
export interface CodeSnippet {
  title: string;
  code: string;
  note?: string;
}

/** Whether a body carries input images of its own, which makes an image request an edit. */
function isEdit(body: Record<string, unknown>): boolean {
  return Array.isArray(body.images) && body.images.length > 0;
}

/** The route a request goes to. */
export function createPath(kind: CodeRequest['kind'], body: Record<string, unknown>): string {
  if (kind === 'video') return VIDEO_CREATE.path;
  return isEdit(body) ? IMAGE_EDIT_PATH : IMAGE_CREATE_PATH;
}

/** How a request is answered: a video is always a job, whatever was asked. */
export function answerMode(request: Pick<CodeRequest, 'kind' | 'mode'>): AnswerMode {
  return request.kind === 'video' ? 'poll' : request.mode;
}

/**
 * The body as the code sends it. An image request that polls says
 * `"async": true`; one that waits says nothing; a video never says it.
 */
export function bodyFor(request: Pick<CodeRequest, 'kind' | 'mode' | 'body'>): Record<string, unknown> {
  const { async: _async, ...rest } = request.body;
  if (request.kind === 'video' || request.mode === 'wait') return rest;
  return 'async' in request.body ? { ...request.body, async: true } : { ...rest, async: true };
}

/** The field of the first answer that holds the job's id. */
function jobIdField(kind: CodeRequest['kind']): string {
  return kind === 'video' ? VIDEO_CREATE.jobIdField : 'job_id';
}

const trimBase = (apiBase: string) => apiBase.replace(/\/+$/, '');

// ---------------------------------------------------------------------------
// curl
// ---------------------------------------------------------------------------

/** One single-quoted shell word. */
function shellWord(s: string): string {
  return `'${s.replaceAll("'", `'\\''`)}'`;
}

/** curl for a POST with a JSON body. */
export function curlPost(apiBase: string, path: string, body: unknown): string {
  return [
    `curl ${trimBase(apiBase)}${path} \\`,
    `  -H "Authorization: Bearer ${API_KEY_VAR}" \\`,
    `  -H "Content-Type: application/json" \\`,
    // The JSON body indented under -d.
    `  -d ${shellWord(JSON.stringify(body, null, 2).replaceAll('\n', '\n  '))}`,
  ].join('\n');
}

/** curl for a GET. */
export function curlGet(apiBase: string, path: string): string {
  return [
    `curl ${trimBase(apiBase)}${path} \\`,
    `  -H "Authorization: Bearer ${API_KEY_VAR}"`,
  ].join('\n');
}

const WAIT_NOTE = 'With a key set to synchronous image responses, the answer holds the images under "data". '
  + 'Any other key answers 202 with a job to poll.';

function curlCode(request: CodeRequest): CodeSnippet[] {
  const post = curlPost(request.apiBase, createPath(request.kind, request.body), bodyFor(request));
  if (answerMode(request) === 'wait') return [{ title: 'Send the request', code: post, note: WAIT_NOTE }];
  return [
    { title: 'Start the job', code: post },
    {
      title: 'Check on it',
      code: curlGet(request.apiBase, `${JOBS_PATH}/${JOB_ID_VAR}`),
      note: `JOB_ID is the ${jobIdField(request.kind)} the first request answers with. `
        + 'Ask again every few seconds until "status" is DONE, then read "outputs".',
    },
  ];
}

// ---------------------------------------------------------------------------
// Python: the OpenAI SDK, pointed at Godance with base_url
// ---------------------------------------------------------------------------

const PY_INDENT = '    ';

/** A JSON value as a Python literal. JSON's string escapes mean the same in Python. */
export function pythonLiteral(value: unknown, depth = 0): string {
  if (value === null || value === undefined) return 'None';
  if (value === true) return 'True';
  if (value === false) return 'False';
  if (typeof value === 'number') return String(value);
  if (typeof value === 'string') return JSON.stringify(value);
  const inner = PY_INDENT.repeat(depth + 1);
  const outer = PY_INDENT.repeat(depth);
  if (Array.isArray(value)) {
    if (value.length === 0) return '[]';
    return `[\n${value.map((item) => `${inner}${pythonLiteral(item, depth + 1)},\n`).join('')}${outer}]`;
  }
  const entries = Object.entries(value as Record<string, unknown>).filter(([, item]) => item !== undefined);
  if (entries.length === 0) return '{}';
  return `{\n${entries.map(([key, item]) => `${inner}${JSON.stringify(key)}: ${pythonLiteral(item, depth + 1)},\n`).join('')}${outer}}`;
}

/** The path under base_url, which already ends in /v1. */
const underV1 = (path: string) => path.replace(/^\/v1/, '');

function pythonClient(apiBase: string): string {
  return [
    'client = OpenAI(',
    `    base_url=${JSON.stringify(`${trimBase(apiBase)}/v1`)},`,
    `    api_key=os.environ[${JSON.stringify(API_KEY_ENV)}],  # the key stays in the environment, never in the code`,
    '    max_retries=0,  # a request that may have been accepted is not sent a second time',
    ')',
  ].join('\n');
}

/** What `client.images.generate` takes by name; anything else a body holds goes in `extra_body`. */
const SDK_IMAGE_ARGUMENTS = ['model', 'prompt', 'size', 'n', 'style', 'quality', 'response_format'];

/** A plain image request that waits is the SDK's own call, unchanged: what a synchronous key is for. */
function isPlainImage(request: CodeRequest, body: Record<string, unknown>): boolean {
  return request.kind === 'image' && answerMode(request) === 'wait' && !isEdit(body)
    && typeof body.prompt === 'string' && body.recipe === undefined && body.mask === undefined;
}

function pythonPlainImage(request: CodeRequest, body: Record<string, unknown>): string {
  const named = Object.entries(body).filter(([key]) => SDK_IMAGE_ARGUMENTS.includes(key));
  const extra = Object.fromEntries(Object.entries(body).filter(([key]) => !SDK_IMAGE_ARGUMENTS.includes(key)));
  const args = named.map(([key, value]) => `    ${key}=${pythonLiteral(value, 1)},`);
  if (Object.keys(extra).length > 0) args.push(`    extra_body=${pythonLiteral(extra, 1)},`);
  return [
    'import os',
    '',
    'from openai import OpenAI',
    '',
    pythonClient(request.apiBase),
    '',
    '# With a key set to synchronous image responses, the request waits for the images',
    "# and answers as OpenAI's API does.",
    'result = client.images.generate(',
    ...args,
    ')',
    '',
    '# A job that outlasts the wait answers with a job to poll instead, and no data.',
    'for image in result.data or []:',
    '    print(image.url)',
  ].join('\n');
}

function pythonCode(request: CodeRequest): CodeSnippet[] {
  const body = bodyFor(request);
  const title = 'Python script';
  if (isPlainImage(request, body)) return [{ title, code: pythonPlainImage(request, body), note: WAIT_NOTE }];

  const path = underV1(createPath(request.kind, request.body));
  const post = [
    'client.post(',
    `    ${JSON.stringify(path)},`,
    `    body=${pythonLiteral(body, 1)},`,
    '    cast_to=httpx.Response,',
    ').json()',
  ].join('\n');

  if (answerMode(request) === 'wait') {
    return [{
      title,
      note: WAIT_NOTE,
      code: [
        'import os',
        '',
        'import httpx',
        'from openai import OpenAI',
        '',
        pythonClient(request.apiBase),
        '',
        '# With a key set to synchronous image responses, the request waits for the images.',
        `answer = ${post}`,
        '',
        '# 200 holds the images under "data". A job that outlasts the wait answers 202',
        '# with a job_id to poll instead, and no "data".',
        'for image in answer.get("data") or []:',
        '    print(image["url"])',
      ].join('\n'),
    }];
  }

  const idField = jobIdField(request.kind);
  return [{
    title,
    code: [
      'import os',
      'import time',
      '',
      'import httpx',
      'from openai import OpenAI',
      '',
      pythonClient(request.apiBase),
      '',
      `# Start the job. The answer is 202 with a ${idField}.`,
      `started = ${post}`,
      '',
      '# Read the job every few seconds until it ends.',
      'while True:',
      `    job = client.get(f"${underV1(JOBS_PATH)}/{started['${idField}']}", cast_to=httpx.Response).json()`,
      '    if job["status"] in ("DONE", "FAILED", "CANCELLED"):',
      '        break',
      '    time.sleep(3)',
      '',
      'if job["status"] != "DONE":',
      `    raise SystemExit(f"{job['status']}: {job.get('errorMessage')}")`,
      'for output in job["outputs"]:',
      '    print(output["url"])',
    ].join('\n'),
  }];
}

// ---------------------------------------------------------------------------
// Java: Spring's WebClient
// ---------------------------------------------------------------------------

/** The class every Java snippet declares; the file is named after it. */
export const JAVA_CLASS = 'RunOnGodance';

/**
 * A JSON body as the lines of a Java text block. A text block reads a
 * backslash as an escape, so each one is doubled; JSON never puts three
 * quotes in a row, so nothing can end the block early.
 */
function javaTextBlock(body: unknown, indent: string): string {
  const lines = JSON.stringify(body, null, 2).replaceAll('\\', '\\\\').split('\n');
  return ['"""', ...lines.map((line) => `${indent}${line}`), `${indent}""";`].join('\n');
}

function javaClient(apiBase: string, largeAnswers: boolean): string[] {
  return [
    '        WebClient godance = WebClient.builder()',
    `                .baseUrl(${JSON.stringify(trimBase(apiBase))})`,
    '                // The key stays in the environment, never in the code.',
    `                .defaultHeader("Authorization", "Bearer " + System.getenv(${JSON.stringify(API_KEY_ENV)}))`,
    ...(largeAnswers
      ? [
          '                // A synchronous answer can carry an image itself, as base64.',
          '                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(32 * 1024 * 1024))',
        ]
      : []),
    '                .build();',
  ];
}

function javaCode(request: CodeRequest): CodeSnippet[] {
  const wait = answerMode(request) === 'wait';
  const idField = jobIdField(request.kind);
  const post = (name: string) => [
    `        Map<String, Object> ${name} = godance.post().uri(${JSON.stringify(createPath(request.kind, request.body))})`,
    '                .contentType(MediaType.APPLICATION_JSON)',
    '                .bodyValue(request)',
    '                .retrieve()',
    '                .bodyToMono(JSON)',
    '                .block();',
  ];
  const head = [
    'import java.util.List;',
    'import java.util.Map;',
    '',
    'import org.springframework.core.ParameterizedTypeReference;',
    'import org.springframework.http.MediaType;',
    'import org.springframework.web.reactive.function.client.WebClient;',
    '',
    `public class ${JAVA_CLASS} {`,
    '',
    '    private static final ParameterizedTypeReference<Map<String, Object>> JSON =',
    '            new ParameterizedTypeReference<>() { };',
    '',
    `    public static void main(String[] args)${wait ? '' : ' throws InterruptedException'} {`,
    ...javaClient(request.apiBase, wait),
    '',
    `        String request = ${javaTextBlock(bodyFor(request), '                ')}`,
    '',
  ];
  const tail = wait
    ? [
        '        // With a key set to synchronous image responses, the request waits for the images.',
        ...post('answer'),
        '',
        '        // 200 holds the images under "data". A job that outlasts the wait answers 202',
        '        // with a job_id to poll instead, and no "data".',
        '        if (answer.get("data") instanceof List<?> images) {',
        '            for (Object image : images) {',
        '                System.out.println(((Map<?, ?>) image).get("url"));',
        '            }',
        '        }',
      ]
    : [
        `        // Start the job. The answer is 202 with a ${idField}.`,
        ...post('started'),
        '',
        '        // Read the job every few seconds until it ends.',
        '        Map<String, Object> job;',
        '        while (true) {',
        `            job = godance.get().uri(${JSON.stringify(`${JOBS_PATH}/{id}`)}, started.get(${JSON.stringify(idField)}))`,
        '                    .retrieve()',
        '                    .bodyToMono(JSON)',
        '                    .block();',
        '            if (List.of("DONE", "FAILED", "CANCELLED").contains(String.valueOf(job.get("status")))) {',
        '                break;',
        '            }',
        '            Thread.sleep(3_000);',
        '        }',
        '',
        '        if (!"DONE".equals(job.get("status"))) {',
        '            throw new IllegalStateException(job.get("status") + ": " + job.get("errorMessage"));',
        '        }',
        '        for (Object output : (List<?>) job.get("outputs")) {',
        '            System.out.println(((Map<?, ?>) output).get("url"));',
        '        }',
      ];
  return [{
    title: 'Java class',
    code: [...head, ...tail, '    }', '}'].join('\n'),
    ...(wait ? { note: WAIT_NOTE } : {}),
  }];
}

/** The request as code in one language: one block, or for curl with polling, two. */
export function codeFor(language: CodeLanguage, request: CodeRequest): CodeSnippet[] {
  switch (language) {
    case 'curl': return curlCode(request);
    case 'python': return pythonCode(request);
    case 'java': return javaCode(request);
  }
}

// ---------------------------------------------------------------------------
// Uploading an input image
// ---------------------------------------------------------------------------

/** What stands in a request for the id of a file the reader uploads: FILE_ID, or FILE_ID_1, FILE_ID_2 … for several. */
export function fileIdPlaceholder(index: number, of: number): string {
  return of === 1 ? 'FILE_ID' : `FILE_ID_${index + 1}`;
}

/** A file name made of plain characters only, so nothing in it is read by a shell or a compiler. */
function plainFileName(fileName: string): string {
  const plain = fileName.replace(/[^A-Za-z0-9._-]+/g, '-').replace(/^-+|-+$/g, '');
  return plain === '' ? 'image.png' : plain;
}

/**
 * The upload of one image, whose id a request then names. `fileName` is
 * where the reader's own image is; it is reduced to plain characters.
 */
export function uploadCode(language: CodeLanguage, apiBase: string, fileName: string): CodeSnippet {
  const file = plainFileName(fileName);
  const title = 'Upload an image';
  const note = 'The answer is 201 with the file\'s "id".';
  switch (language) {
    case 'curl':
      return {
        title,
        note,
        code: [
          `curl ${trimBase(apiBase)}${FILES_PATH} \\`,
          `  -H "Authorization: Bearer ${API_KEY_VAR}" \\`,
          `  -F "file=@${file}"`,
        ].join('\n'),
      };
    case 'python':
      return {
        title,
        note,
        code: [
          'import os',
          '',
          'from openai import OpenAI',
          '',
          pythonClient(apiBase),
          '',
          `uploaded = client.files.create(file=open(${JSON.stringify(file)}, "rb"), purpose="generation_input")`,
          'print(uploaded.id)',
        ].join('\n'),
      };
    case 'java':
      return {
        title,
        note,
        code: [
          'import java.util.Map;',
          '',
          'import org.springframework.core.ParameterizedTypeReference;',
          'import org.springframework.core.io.FileSystemResource;',
          'import org.springframework.http.client.MultipartBodyBuilder;',
          'import org.springframework.web.reactive.function.BodyInserters;',
          'import org.springframework.web.reactive.function.client.WebClient;',
          '',
          'public class UploadToGodance {',
          '',
          '    public static void main(String[] args) {',
          ...javaClient(apiBase, false),
          '',
          '        MultipartBodyBuilder form = new MultipartBodyBuilder();',
          `        form.part("file", new FileSystemResource(${JSON.stringify(file)}));`,
          `        Map<String, Object> uploaded = godance.post().uri(${JSON.stringify(FILES_PATH)})`,
          '                .body(BodyInserters.fromMultipartData(form.build()))',
          '                .retrieve()',
          '                .bodyToMono(new ParameterizedTypeReference<Map<String, Object>>() { })',
          '                .block();',
          '        System.out.println(uploaded.get("id"));',
          '    }',
          '}',
        ].join('\n'),
      };
  }
}
