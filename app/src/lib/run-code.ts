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
export const VIDEO_ID_VAR = '$VIDEO_ID';

export const IMAGE_CREATE_PATH = '/v1/images/generations';
export const IMAGE_EDIT_PATH = '/v1/images/edits';
export const JOBS_PATH = '/v1/jobs';
export const FILES_PATH = '/v1/files';

/**
 * Where generated code sends a video: OpenAI's Videos API, POST /v1/videos
 * (round 12 brief 04). The answer is the video itself, queued, and its `id`
 * is the job's id. The code reads it at GET /v1/videos/{id} until it ends,
 * then saves it from GET /v1/videos/{id}/content.
 * The Studio's own requests do not read this: they go where lib/studio.ts says.
 */
export const VIDEO_CREATE = { path: '/v1/videos', idField: 'id' } as const;

/**
 * Godance's own video route, for a request OpenAI's shape cannot carry:
 * a last frame, reference images, more than one clip. POST /v1/videos takes
 * one image, the first frame, and answers 200 to a field it does not know
 * without using it, so such a request is sent here as it is and read as a job.
 */
export const VIDEO_GENERATIONS = { path: '/v1/videos/generations', jobIdField: 'job_id' } as const;

/** What POST /v1/videos takes as it is: OpenAI's fields, and the Godance extensions it shares with /v1/videos/generations. */
const OPENAI_VIDEO_FIELDS = ['model', 'prompt', 'recipe', 'size', 'gallery_recipe', 'webhook_url'];

/**
 * A video request in OpenAI's shape, for POST /v1/videos: the first frame
 * is `input_reference` there, and `seconds` is a string ("8"). One request
 * makes one video, so a count of 1 goes without saying. Null when the shape
 * cannot carry the whole request: a last frame, reference images, a larger
 * count or any other field would be dropped without a word, so that request
 * goes to Godance's own route instead (VIDEO_GENERATIONS).
 */
export function openAiVideoBody(body: Record<string, unknown>): Record<string, unknown> | null {
  const out: Record<string, unknown> = {};
  for (const [key, value] of Object.entries(body)) {
    if (value === undefined || key === 'async' || (key === 'n' && value === 1)) continue;
    if (key === 'first_frame') out.input_reference = value;
    else if (key === 'seconds') out.seconds = typeof value === 'number' ? String(value) : value;
    else if (OPENAI_VIDEO_FIELDS.includes(key)) out[key] = value;
    else return null;
  }
  return out;
}

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
  if (kind === 'video') return openAiVideoBody(body) ? VIDEO_CREATE.path : VIDEO_GENERATIONS.path;
  return isEdit(body) ? IMAGE_EDIT_PATH : IMAGE_CREATE_PATH;
}

/** The body of a video request that goes to POST /v1/videos; null for an image, or a video that does not. */
function openAiVideo(request: Pick<CodeRequest, 'kind' | 'mode' | 'body'>): Record<string, unknown> | null {
  return request.kind === 'video' ? openAiVideoBody(bodyFor(request)) : null;
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

/** Said with a video that goes to Godance's own route rather than to OpenAI's. */
export const VIDEO_GENERATIONS_NOTE = 'OpenAI\'s POST /v1/videos has no field for part of this request (such as a '
  + 'last frame or reference images), so it goes to Godance\'s own video route as it is, and answers 202 with a job to poll.';

/** The note for the request itself: a video says why it is not in OpenAI's shape. */
const jobNote = (request: CodeRequest) => (request.kind === 'video' ? { note: VIDEO_GENERATIONS_NOTE } : {});

function curlCode(request: CodeRequest): CodeSnippet[] {
  const video = openAiVideo(request);
  if (video) return curlVideo(request.apiBase, video);
  const post = curlPost(request.apiBase, createPath(request.kind, request.body), bodyFor(request));
  if (answerMode(request) === 'wait') return [{ title: 'Send the request', code: post, note: WAIT_NOTE }];
  return [
    { title: 'Start the job', code: post, ...jobNote(request) },
    {
      title: 'Check on it',
      code: curlGet(request.apiBase, `${JOBS_PATH}/${JOB_ID_VAR}`),
      note: 'JOB_ID is the job_id the first request answers with. '
        + 'Ask again every few seconds until "status" is DONE, then read "outputs".',
    },
  ];
}

/** A video in OpenAI's shape: start it, read it until it ends, then download it. */
function curlVideo(apiBase: string, body: Record<string, unknown>): CodeSnippet[] {
  const video = `${VIDEO_CREATE.path}/${VIDEO_ID_VAR}`;
  return [
    {
      title: 'Start the video',
      code: curlPost(apiBase, VIDEO_CREATE.path, body),
      note: 'The answer is the video, with its "id" and "status": "queued".',
    },
    {
      title: 'Check on it',
      code: curlGet(apiBase, video),
      note: `VIDEO_ID is the "${VIDEO_CREATE.idField}" the first request answers with. `
        + 'Ask again every few seconds until "status" is completed or failed.',
    },
    {
      title: 'Download it',
      code: [
        `curl -o "${VIDEO_ID_VAR}.mp4" ${trimBase(apiBase)}${video}/content \\`,
        `  -H "Authorization: Bearer ${API_KEY_VAR}"`,
      ].join('\n'),
      note: 'Once "status" is completed. The answer is the video file itself.',
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

/** What `client.videos.create_and_poll` takes by name, in this order; anything else a body holds goes in `extra_body`. */
const SDK_VIDEO_ARGUMENTS = ['model', 'prompt', 'seconds', 'size'];

/**
 * A video in OpenAI's shape is the SDK's own call. It sends a multipart
 * form, so what goes in `extra_body` becomes form fields: a reference such as
 * {"file_id": …} is written the way the API reads it, but `recipe` has to be
 * JSON text. The SDK requires a prompt, which a recipe run leaves empty.
 */
function pythonVideo(apiBase: string, body: Record<string, unknown>): string {
  const recipe = body.recipe !== undefined;
  const args: string[] = [];
  if (body.model !== undefined) args.push(`    model=${pythonLiteral(body.model, 1)},`);
  if (typeof body.prompt === 'string') args.push(`    prompt=${pythonLiteral(body.prompt, 1)},`);
  else args.push(recipe ? '    prompt="",  # the recipe is the prompt: Godance fills its template in' : '    prompt="",');
  if (body.seconds !== undefined) args.push(`    seconds=${pythonLiteral(body.seconds, 1)},`);
  if (body.size !== undefined) args.push(`    size=${pythonLiteral(body.size, 1)},`);
  const extra = Object.entries(body).filter(([key]) => !SDK_VIDEO_ARGUMENTS.includes(key));
  if (extra.length > 0) {
    args.push('    extra_body={');
    if (recipe) args.push('        # The SDK sends a form, in which "recipe" is JSON text.');
    for (const [key, value] of extra) {
      const literal = pythonLiteral(value, 2);
      args.push(`        ${JSON.stringify(key)}: ${key === 'recipe' ? `json.dumps(${literal})` : literal},`);
    }
    args.push('    },');
  }
  return [
    ...(recipe ? ['import json'] : []),
    'import os',
    '',
    'from openai import OpenAI',
    '',
    pythonClient(apiBase),
    '',
    '# Start the video, then read it every few seconds until it is completed or failed.',
    '# The SDK warns that OpenAI shut its own video API (Sora) down: these calls go to Godance.',
    'video = client.videos.create_and_poll(',
    ...args,
    ')',
    '',
    'if video.status != "completed":',
    '    raise SystemExit(f"{video.status}: {video.error.message if video.error else None}")',
    '# Save the video under its id.',
    'client.videos.download_content(video.id, variant="video").write_to_file(f"{video.id}.mp4")',
    'print(f"{video.id}.mp4")',
  ].join('\n');
}

function pythonCode(request: CodeRequest): CodeSnippet[] {
  const body = bodyFor(request);
  const title = 'Python script';
  if (isPlainImage(request, body)) return [{ title, code: pythonPlainImage(request, body), note: WAIT_NOTE }];
  const video = openAiVideo(request);
  if (video) return [{ title, code: pythonVideo(request.apiBase, video) }];

  const path = underV1(createPath(request.kind, request.body));
  // cast_to=object hands back the answer's JSON as a dict. Not httpx.Response: the
  // SDK's own HTTP library changed (openai 3 depends on httpx2), so an `import httpx`
  // fails after `pip install openai`, and object works on every version.
  const post = [
    'client.post(',
    `    ${JSON.stringify(path)},`,
    `    body=${pythonLiteral(body, 1)},`,
    '    cast_to=object,  # the answer\'s JSON, as a dict',
    ')',
  ].join('\n');

  if (answerMode(request) === 'wait') {
    return [{
      title,
      note: WAIT_NOTE,
      code: [
        'import os',
        '',
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

  return [{
    title,
    ...jobNote(request),
    code: [
      'import os',
      'import time',
      '',
      'from openai import OpenAI',
      '',
      pythonClient(request.apiBase),
      '',
      '# Start the job. The answer is 202 with a job_id.',
      `started = ${post}`,
      '',
      '# Read the job every few seconds until it ends.',
      'while True:',
      `    job = client.get(f"${underV1(JOBS_PATH)}/{started['job_id']}", cast_to=object)`,
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

/**
 * A video in OpenAI's shape: the answer is the video, read again until it
 * ends. Its file is written to disk as it streams in, so a clip of any size
 * passes WebClient's in-memory limit.
 */
function javaVideoTail(post: string[]): string[] {
  return [
    '        // Start the video. The answer is the video, with its "id" and "status": "queued".',
    ...post,
    '',
    '        // Read it every few seconds until it ends.',
    `        Object id = video.get(${JSON.stringify(VIDEO_CREATE.idField)});`,
    '        while (List.of("queued", "in_progress").contains(String.valueOf(video.get("status")))) {',
    '            Thread.sleep(5_000);',
    `            video = godance.get().uri(${JSON.stringify(`${VIDEO_CREATE.path}/{id}`)}, id)`,
    '                    .retrieve()',
    '                    .bodyToMono(JSON)',
    '                    .block();',
    '        }',
    '',
    '        if (!"completed".equals(video.get("status"))) {',
    '            throw new IllegalStateException(video.get("status") + ": " + video.get("error"));',
    '        }',
    '        // Save the video under its id.',
    '        Path file = Path.of(id + ".mp4");',
    `        DataBufferUtils.write(godance.get().uri(${JSON.stringify(`${VIDEO_CREATE.path}/{id}/content`)}, id)`,
    '                        .retrieve()',
    '                        .bodyToFlux(DataBuffer.class), file)',
    '                .block();',
    '        System.out.println(file);',
  ];
}

function javaCode(request: CodeRequest): CodeSnippet[] {
  const wait = answerMode(request) === 'wait';
  const video = openAiVideo(request);
  const post = (name: string) => [
    `        Map<String, Object> ${name} = godance.post().uri(${JSON.stringify(createPath(request.kind, request.body))})`,
    '                .contentType(MediaType.APPLICATION_JSON)',
    '                .bodyValue(request)',
    '                .retrieve()',
    '                .bodyToMono(JSON)',
    '                .block();',
  ];
  const head = [
    ...(video ? ['import java.nio.file.Path;'] : []),
    'import java.util.List;',
    'import java.util.Map;',
    '',
    'import org.springframework.core.ParameterizedTypeReference;',
    ...(video
      ? ['import org.springframework.core.io.buffer.DataBuffer;', 'import org.springframework.core.io.buffer.DataBufferUtils;']
      : []),
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
    `        String request = ${javaTextBlock(video ?? bodyFor(request), '                ')}`,
    '',
  ];
  const tail = video ? javaVideoTail(post('video')) : wait
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
        '        // Start the job. The answer is 202 with a job_id.',
        ...post('started'),
        '',
        '        // Read the job every few seconds until it ends.',
        '        Map<String, Object> job;',
        '        while (true) {',
        `            job = godance.get().uri(${JSON.stringify(`${JOBS_PATH}/{id}`)}, started.get("job_id"))`,
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
    ...(wait ? { note: WAIT_NOTE } : video ? {} : jobNote(request)),
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
